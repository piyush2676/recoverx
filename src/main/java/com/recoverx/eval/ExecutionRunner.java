package com.recoverx.eval;

import com.recoverx.classify.Classification;
import com.recoverx.classify.FailureClassifier;
import com.recoverx.classify.LlmClassifier;
import com.recoverx.classify.RuleBasedClassifier;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.executor.AttemptResult;
import com.recoverx.executor.IdempotencyGuard;
import com.recoverx.ledger.JsonlLedger;
import com.recoverx.ledger.Ledger;
import com.recoverx.ledger.LedgerEntry;
import com.recoverx.policy.DefaultRecoveryPolicy;
import com.recoverx.policy.PolicyConfig;
import com.recoverx.policy.RecoveryDecision;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replays the whole recovery cycle: decide, attempt, learn the outcome, decide again.
 *
 * <p>A single decision pass cannot measure recovery, because most declines take a
 * second look. This runner walks each transaction forward through up to the configured
 * attempt ceiling, moving its clock to the moment the previous attempt's result would
 * have been known, so attempt two is decided with attempt one's failure already in the
 * ledger - which is what the policy engine's backoff rules were written for.
 *
 * <p>{@code --crash-after=N} halts the process abruptly once N outcomes have been
 * written. Re-running with {@code --resume} continues against the existing ledger. That
 * pair is the double-charge demonstration, and it is a real kill, not a simulated one.
 */
@Component
public class ExecutionRunner implements CommandLineRunner {

    private static final Duration PICKUP_DELAY = Duration.ofMinutes(5);
    private static final Duration OUTCOME_DELAY = Duration.ofMinutes(1);
    private static final String DEFAULT_MODEL = "claude-opus-5";

    @Override
    public void run(String... args) throws Exception {
        List<String> argList = Arrays.asList(args);
        if (!argList.contains("--execute")) {
            return;
        }

        Path dataDir = pathArg(args, "--data=", "data");
        Path outDir = pathArg(args, "--out=", "eval/out");
        String model = stringArg(args, "--model=", DEFAULT_MODEL);
        boolean useLlm = argList.contains("--llm");
        boolean resume = argList.contains("--resume");
        int crashAfter = Integer.parseInt(stringArg(args, "--crash-after=", "0"));
        int crashBeforeOutcome = Integer.parseInt(stringArg(args, "--crash-before-outcome=", "0"));

        Path ledgerPath = outDir.resolve("ledger.jsonl");
        Path keysPath = outDir.resolve("gateway_keys.txt");
        if (!resume) {
            Files.deleteIfExists(ledgerPath);
            Files.deleteIfExists(keysPath);
        }

        DatasetLoader loader = new DatasetLoader();
        List<FailedTransaction.AgentView> transactions = loader.loadTransactions(dataDir);
        Map<String, FailedTransaction.GroundTruth> truth = loader.loadGroundTruth(dataDir);

        PolicyConfig config = PolicyConfig.defaults();
        Ledger ledger = new JsonlLedger(ledgerPath, Clock.systemUTC());
        IdempotencyGuard guard = new IdempotencyGuard(ledger);
        SimulatedExecutor executor = new SimulatedExecutor(transactions, truth, guard, keysPath);

        if (resume) {
            System.out.printf("resuming against %d existing ledger rows%n", ledger.all().size());
        }

        // Anything left in flight by a previous process blocks its transaction until it
        // is answered. Do that first, before deciding anything new.
        int reconciledNow = 0;
        for (LedgerEntry pending : ledger.pendingAttempts()) {
            ledger.recordOutcome(executor.reconcile(pending));
            reconciledNow++;
        }
        if (reconciledNow > 0) {
            System.out.printf("reconciled %d attempt(s) left in flight by a previous run%n", reconciledNow);
        }

        Map<String, Classification> verdicts = classify(transactions, useLlm, model, outDir);

        Map<String, Instant> nextDecisionAt = new HashMap<>();
        Set<String> finished = new HashSet<>();
        for (FailedTransaction.AgentView txn : transactions) {
            nextDecisionAt.put(txn.txnId(), txn.failedAt().plus(PICKUP_DELAY));
        }
        seedFromLedger(ledger, transactions, nextDecisionAt, finished);

        int outcomesWritten = 0;
        int attemptsMade = 0;
        for (int round = 1; round <= config.maxAttemptsPerTxn(); round++) {
            int attemptsThisRound = 0;

            for (FailedTransaction.AgentView txn : transactions) {
                if (finished.contains(txn.txnId())) {
                    continue;
                }
                Clock clock = Clock.fixed(nextDecisionAt.get(txn.txnId()), ZoneOffset.UTC);
                RecoveryDecision decision =
                        new DefaultRecoveryPolicy(config, clock).decide(txn, verdicts.get(txn.txnId()), ledger);
                ledger.recordDecision(decision);

                if (!PolicyConfig.movesMoney(decision.action())) {
                    finished.add(txn.txnId()); // stopped or escalated; nothing more to do
                    continue;
                }

                AttemptResult result = executor.execute(decision);
                attemptsMade++;

                if (crashBeforeOutcome > 0 && attemptsMade >= crashBeforeOutcome) {
                    // The dangerous window: the gateway has been called, and the process dies
                    // before the outcome row exists. On resume the ledger shows nothing, so the
                    // local guard cannot help - only the gateway's own duplicate check stands
                    // between this and a second charge. That is what --resume then proves.
                    System.err.printf(
                            "%n-- injected crash after %d gateway calls, before the outcome was written --%n"
                                    + "   the ledger has no record of this attempt; re-run with --resume%n",
                            attemptsMade);
                    System.err.flush();
                    Runtime.getRuntime().halt(4);
                }

                ledger.recordOutcome(result);
                outcomesWritten++;
                attemptsThisRound++;

                if (crashAfter > 0 && outcomesWritten >= crashAfter) {
                    System.err.printf(
                            "%n-- injected crash after %d outcomes (round %d) --%n"
                                    + "   re-run with --resume to continue against this ledger%n",
                            outcomesWritten, round);
                    System.err.flush();
                    // halt, not exit: no shutdown hooks, no flush, exactly like a kill -9.
                    Runtime.getRuntime().halt(3);
                }

                if (result.succeeded()) {
                    finished.add(txn.txnId());
                } else {
                    // The failure is known shortly after the attempt was due.
                    nextDecisionAt.put(txn.txnId(), decision.scheduledFor().plus(OUTCOME_DELAY));
                }
            }

            System.out.printf("round %d: %d attempts%n", round, attemptsThisRound);
            if (attemptsThisRound == 0) {
                break;
            }
        }

        Summary summary = summarise(ledger, transactions, truth, executor);
        writeReport(outDir.resolve("execution_summary.md"), summary, config, executor);
        print(summary, executor, ledgerPath);
    }

    // ---------- resume support ----------

    /**
     * Rebuilds per-transaction progress from what is already on disk, so a resumed run
     * picks up where the dead one stopped instead of starting the batch over.
     */
    private void seedFromLedger(Ledger ledger,
                                List<FailedTransaction.AgentView> transactions,
                                Map<String, Instant> nextDecisionAt,
                                Set<String> finished) {
        for (FailedTransaction.AgentView txn : transactions) {
            List<LedgerEntry> rows = ledger.forTxn(txn.txnId());
            if (rows.isEmpty()) {
                continue;
            }
            for (LedgerEntry row : rows) {
                if (row.type() == LedgerEntry.EntryType.OUTCOME && Boolean.TRUE.equals(row.succeeded())) {
                    finished.add(txn.txnId());
                }
                if (row.type() == LedgerEntry.EntryType.DECISION) {
                    if (!PolicyConfig.movesMoney(row.action())) {
                        finished.add(txn.txnId());
                    } else if (row.scheduledFor() != null) {
                        nextDecisionAt.put(txn.txnId(), row.scheduledFor().plus(OUTCOME_DELAY));
                    }
                }
            }
        }
    }

    // ---------- reporting ----------

    private record Summary(
            int transactions,
            int attempts,
            int successes,
            long recoveredPaise,
            long costPaise,
            long ceilingPaise,
            long totalFailedPaise,
            int complianceViolations,
            int doubleCharges,
            int escalations
    ) {
        long netPaise() {
            return recoveredPaise - costPaise;
        }

        double recoveryRate() {
            return ceilingPaise == 0 ? 0.0 : (double) recoveredPaise / ceilingPaise;
        }
    }

    private Summary summarise(Ledger ledger,
                              List<FailedTransaction.AgentView> transactions,
                              Map<String, FailedTransaction.GroundTruth> truth,
                              SimulatedExecutor executor) {
        long recovered = 0;
        long cost = 0;
        int attempts = 0;
        int successes = 0;
        int escalations = 0;
        Map<String, Integer> successesPerTxn = new HashMap<>();

        for (LedgerEntry row : ledger.all()) {
            if (row.type() == LedgerEntry.EntryType.OUTCOME) {
                attempts++;
                cost += row.costPaise();
                if (Boolean.TRUE.equals(row.succeeded())) {
                    successes++;
                    recovered += row.recoveredPaise();
                    successesPerTxn.merge(row.txnId(), 1, Integer::sum);
                }
            } else if (row.action() == com.recoverx.policy.ActionType.ESCALATE_HUMAN) {
                escalations++;
            }
        }

        // A transaction collected more than once is the failure this whole design exists
        // to prevent. Counted directly from the trail rather than assumed to be zero.
        int doubleCharges = (int) successesPerTxn.values().stream().filter(n -> n > 1).count();

        // The ceiling needs the amounts, which live on the transactions, and the
        // recoverability, which lives on the truth. Neither file has both.
        long ceiling = 0;
        long totalFailed = 0;
        for (FailedTransaction.AgentView txn : transactions) {
            totalFailed += txn.amountPaise();
            FailedTransaction.GroundTruth groundTruth = truth.get(txn.txnId());
            if (groundTruth != null && groundTruth.truth().recoverable()) {
                ceiling += txn.amountPaise();
            }
        }

        return new Summary(transactions.size(), attempts, successes, recovered, cost,
                ceiling, totalFailed, executor.complianceViolations(), doubleCharges, escalations);
    }

    private void writeReport(Path path, Summary summary, PolicyConfig config, SimulatedExecutor executor)
            throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("# Execution pass\n\n")
                .append("Attempts resolved against the hidden truth by `SimulatedExecutor`.\n\n")
                .append("| Metric | Value |\n|---|---:|\n")
                .append("| Transactions | ").append(summary.transactions()).append(" |\n")
                .append("| Attempts made | ").append(summary.attempts()).append(" |\n")
                .append("| Successful recoveries | ").append(summary.successes()).append(" |\n")
                .append("| Recovered | Rs ").append(rupees(summary.recoveredPaise())).append(" |\n")
                .append("| Attempt cost | Rs ").append(rupees(summary.costPaise())).append(" |\n")
                .append("| **Net recovered** | **Rs ").append(rupees(summary.netPaise())).append("** |\n")
                .append("| Escalated to a human | ").append(summary.escalations()).append(" |\n\n")
                .append("## Safety counters\n\n")
                .append("These are the numbers that decide whether the rest of the table is\n")
                .append("worth anything. All three should be zero.\n\n")
                .append("| Counter | Value | Target |\n|---|---:|---:|\n")
                .append("| Compliance violations | ").append(summary.complianceViolations()).append(" | 0 |\n")
                .append("| Double charges | ").append(summary.doubleCharges()).append(" | 0 |\n")
                .append("| Duplicates caught by the gateway | ")
                .append(executor.duplicatesRejectedByGateway()).append(" | 0 |\n")
                .append("| Attempts replayed from the ledger | ")
                .append(executor.replays()).append(" | 0 in a clean run |\n\n")
                .append("A non-zero replay or gateway-duplicate count is expected after a\n")
                .append("`--crash-after` run and is the guard doing its job. In an uninterrupted\n")
                .append("run both are zero.\n\n")
                .append("## Safety envelope\n\n")
                .append("| Rule | Value |\n|---|---:|\n")
                .append("| Max attempts per transaction | ").append(config.maxAttemptsPerTxn()).append(" |\n")
                .append("| Age cutoff | ").append(config.maxAgeDays()).append(" days |\n")
                .append("| Salary window (insufficient funds) | ").append(config.paydayWindowDays()).append(" days |\n")
                .append("| Confidence floor | ").append(config.minClassifierConfidence()).append(" |\n");
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private void print(Summary summary, SimulatedExecutor executor, Path ledgerPath) {
        System.out.println();
        System.out.printf("attempts made           : %d%n", summary.attempts());
        System.out.printf("successful recoveries   : %d%n", summary.successes());
        System.out.printf("recovered               : Rs %s%n", rupees(summary.recoveredPaise()));
        System.out.printf("attempt cost            : Rs %s%n", rupees(summary.costPaise()));
        System.out.printf("net recovered           : Rs %s%n", rupees(summary.netPaise()));
        System.out.printf("oracle ceiling          : Rs %s%n", rupees(summary.ceilingPaise()));
        System.out.printf("recovery rate           : %.1f%% of ceiling%n", summary.recoveryRate() * 100);
        System.out.println();
        System.out.printf("compliance violations   : %d  (target 0)%n", summary.complianceViolations());
        System.out.printf("double charges          : %d  (target 0)%n", summary.doubleCharges());
        System.out.printf("gateway duplicate stops : %d%n", executor.duplicatesRejectedByGateway());
        System.out.printf("ledger replays          : %d%n", executor.replays());
        System.out.printf("orphans reconciled      : %d%n", executor.reconciledCount());
        System.out.println();
        System.out.println("ledger -> " + ledgerPath.toAbsolutePath());
    }

    // ---------- plumbing ----------

    private Map<String, Classification> classify(List<FailedTransaction.AgentView> transactions,
                                                 boolean useLlm,
                                                 String model,
                                                 Path outDir) throws IOException {
        if (useLlm && LlmClassifier.credentialsAvailable()) {
            System.out.println("classifying with " + model + " ...");
            return new LlmClassifier(model, outDir.resolve("llm_cache.jsonl")).classifyAll(transactions);
        }
        if (useLlm) {
            System.out.println("--llm requested but no credentials resolved; using the rule baseline.");
        }
        FailureClassifier rules = new RuleBasedClassifier();
        Map<String, Classification> verdicts = new HashMap<>();
        for (FailedTransaction.AgentView txn : transactions) {
            verdicts.put(txn.txnId(), rules.classify(txn));
        }
        return verdicts;
    }

    private static String rupees(long paise) {
        return String.format("%.2f", paise / 100.0);
    }

    private static String stringArg(String[] args, String prefix, String fallback) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return fallback;
    }

    private static Path pathArg(String[] args, String prefix, String fallback) {
        return Path.of(stringArg(args, prefix, fallback));
    }
}
