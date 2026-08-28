package com.recoverx.eval;

import com.recoverx.domain.FailedTransaction;
import com.recoverx.executor.AttemptResult;
import com.recoverx.executor.IdempotencyGuard;
import com.recoverx.ledger.JsonlLedger;
import com.recoverx.ledger.Ledger;
import com.recoverx.ledger.LedgerEntry;
import com.recoverx.policy.ActionType;
import com.recoverx.policy.PolicyConfig;
import com.recoverx.policy.RecoveryDecision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The execution loop, shared by every arm.
 *
 * <p>Decide, attempt, learn the outcome, decide again - walking each transaction
 * forward so attempt two is taken with attempt one's failure already in the ledger.
 * The clock moves per transaction to the moment the previous result would have been
 * known, because a real system reacts to each failure as it arrives rather than
 * looking at a month of declines at once.
 *
 * <p>Every arm shares this loop, this ledger implementation, and this simulator. The
 * only thing that varies is the {@link DecisionSource}. That is what makes the
 * comparison a comparison of strategies rather than of harnesses.
 */
public class RecoveryCycle {

    private static final Duration PICKUP_DELAY = Duration.ofMinutes(5);
    private static final Duration OUTCOME_DELAY = Duration.ofMinutes(1);

    /** Fires just after an outcome is written, or just before. Used only by the chaos runs. */
    public interface CrashHook {
        void afterAttempt(int attemptsMade, int outcomesWritten, int round);
    }

    public record ArmResult(
            String arm,
            int transactions,
            int attempts,
            int successes,
            long recoveredPaise,
            long costPaise,
            long ceilingPaise,
            long totalFailedPaise,
            int escalations,
            int complianceViolations,
            int doubleCharges,
            int wastedAttempts,
            int replays,
            int reconciled,
            Path ledgerPath
    ) {
        public long netPaise() {
            return recoveredPaise - costPaise;
        }

        public double recoveryRate() {
            return ceilingPaise == 0 ? 0.0 : (double) recoveredPaise / ceilingPaise;
        }

        /** What the same number looks like against the denominator that flatters it. */
        public double rateAgainstTotalFailed() {
            return totalFailedPaise == 0 ? 0.0 : (double) recoveredPaise / totalFailedPaise;
        }
    }

    private final List<FailedTransaction.AgentView> transactions;
    private final Map<String, FailedTransaction.GroundTruth> truth;
    private final PolicyConfig config;

    public RecoveryCycle(List<FailedTransaction.AgentView> transactions,
                         Map<String, FailedTransaction.GroundTruth> truth,
                         PolicyConfig config) {
        this.transactions = transactions;
        this.truth = truth;
        this.config = config;
    }

    public ArmResult run(DecisionSource source, Path armDir, boolean resume, CrashHook crashHook)
            throws IOException {
        Files.createDirectories(armDir);
        Path ledgerPath = armDir.resolve("ledger.jsonl");
        Path keysPath = armDir.resolve("gateway_keys.txt");
        if (!resume) {
            Files.deleteIfExists(ledgerPath);
            Files.deleteIfExists(keysPath);
        }

        Ledger ledger = new JsonlLedger(ledgerPath, Clock.systemUTC());
        IdempotencyGuard guard = new IdempotencyGuard(ledger);
        SimulatedExecutor executor = new SimulatedExecutor(transactions, truth, guard, keysPath);

        // Anything a previous process left in flight blocks its transaction until it is
        // answered. Do that before deciding anything new.
        for (LedgerEntry pending : ledger.pendingAttempts()) {
            ledger.recordOutcome(executor.reconcile(pending));
        }

        Map<String, Instant> nextDecisionAt = new HashMap<>();
        Set<String> finished = new HashSet<>();
        for (FailedTransaction.AgentView txn : transactions) {
            nextDecisionAt.put(txn.txnId(), txn.failedAt().plus(PICKUP_DELAY));
        }
        seedFromLedger(ledger, nextDecisionAt, finished);

        int attemptsMade = 0;
        int outcomesWritten = 0;

        for (int round = 1; round <= config.maxAttemptsPerTxn(); round++) {
            int attemptsThisRound = 0;

            for (FailedTransaction.AgentView txn : transactions) {
                if (finished.contains(txn.txnId())) {
                    continue;
                }
                RecoveryDecision decision =
                        source.decide(txn, nextDecisionAt.get(txn.txnId()), ledger);
                ledger.recordDecision(decision);

                if (!PolicyConfig.movesMoney(decision.action())) {
                    finished.add(txn.txnId());
                    continue;
                }

                AttemptResult result = executor.execute(decision);
                attemptsMade++;
                attemptsThisRound++;

                if (crashHook != null) {
                    crashHook.afterAttempt(attemptsMade, outcomesWritten, round);
                }

                ledger.recordOutcome(result);
                outcomesWritten++;

                if (crashHook != null) {
                    crashHook.afterAttempt(attemptsMade, outcomesWritten, round);
                }

                if (result.succeeded()) {
                    finished.add(txn.txnId());
                } else {
                    nextDecisionAt.put(txn.txnId(), decision.scheduledFor().plus(OUTCOME_DELAY));
                }
            }

            if (attemptsThisRound == 0) {
                break;
            }
        }

        return summarise(source.name(), ledger, executor, ledgerPath);
    }

    private void seedFromLedger(Ledger ledger, Map<String, Instant> nextDecisionAt, Set<String> finished) {
        for (FailedTransaction.AgentView txn : transactions) {
            for (LedgerEntry row : ledger.forTxn(txn.txnId())) {
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

    private ArmResult summarise(String arm, Ledger ledger, SimulatedExecutor executor, Path ledgerPath) {
        long recovered = 0;
        long cost = 0;
        int attempts = 0;
        int successes = 0;
        int escalations = 0;
        int wasted = 0;
        Map<String, Integer> successesPerTxn = new HashMap<>();

        for (LedgerEntry row : ledger.all()) {
            if (row.type() == LedgerEntry.EntryType.OUTCOME) {
                attempts++;
                cost += row.costPaise();
                if (Boolean.TRUE.equals(row.succeeded())) {
                    successes++;
                    recovered += row.recoveredPaise();
                    successesPerTxn.merge(row.txnId(), 1, Integer::sum);
                } else {
                    FailedTransaction.GroundTruth groundTruth = truth.get(row.txnId());
                    // An attempt on something nobody could have recovered. Not a violation,
                    // but not free either - it costs money and customer patience.
                    if (groundTruth != null && !groundTruth.truth().recoverable()) {
                        wasted++;
                    }
                }
            } else if (row.action() == ActionType.ESCALATE_HUMAN) {
                escalations++;
            }
        }

        int doubleCharges = (int) successesPerTxn.values().stream().filter(n -> n > 1).count();

        long ceiling = 0;
        long totalFailed = 0;
        for (FailedTransaction.AgentView txn : transactions) {
            totalFailed += txn.amountPaise();
            FailedTransaction.GroundTruth groundTruth = truth.get(txn.txnId());
            if (groundTruth != null && groundTruth.truth().recoverable()) {
                ceiling += txn.amountPaise();
            }
        }

        return new ArmResult(arm, transactions.size(), attempts, successes, recovered, cost,
                ceiling, totalFailed, escalations, executor.complianceViolations(), doubleCharges,
                wasted, executor.replays(), executor.reconciledCount(), ledgerPath);
    }
}
