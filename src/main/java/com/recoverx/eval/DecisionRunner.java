package com.recoverx.eval;

import com.recoverx.classify.Classification;
import com.recoverx.classify.FailureClassifier;
import com.recoverx.classify.LlmClassifier;
import com.recoverx.classify.RuleBasedClassifier;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.ledger.JsonlLedger;
import com.recoverx.ledger.Ledger;
import com.recoverx.policy.ActionType;
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
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs classify - decide - record over the batch and writes the audit trail.
 *
 * <p><b>The clock is set per transaction, not once for the run.</b> A real recovery
 * system reacts to each failure minutes after it happens; it does not wake up on day
 * 30 and look at a month of declines at once. Deciding the whole batch under one
 * wall-clock "now" would push most of it past the 7-day cutoff and measure nothing
 * except that the batch is old. So each decision is taken as of five minutes after
 * that transaction failed.
 *
 * <p>This is a simulation of arrival, and it is stated plainly here because a reviewer
 * should be able to see the assumption rather than infer it from a number.
 */
@Component
public class DecisionRunner implements CommandLineRunner {

    private static final Duration PICKUP_DELAY = Duration.ofMinutes(5);
    private static final String DEFAULT_MODEL = "claude-opus-5";

    @Override
    public void run(String... args) throws Exception {
        if (!Arrays.asList(args).contains("--decide")) {
            return;
        }

        Path dataDir = pathArg(args, "--data=", "data");
        Path outDir = pathArg(args, "--out=", "eval/out");
        String model = stringArg(args, "--model=", DEFAULT_MODEL);
        boolean useLlm = Arrays.asList(args).contains("--llm");

        DatasetLoader loader = new DatasetLoader();
        List<FailedTransaction.AgentView> transactions = loader.loadTransactions(dataDir);

        PolicyConfig config = PolicyConfig.defaults();
        Path ledgerPath = outDir.resolve("ledger.jsonl");
        Files.deleteIfExists(ledgerPath); // a run starts a fresh trail; old ones are archived by the caller
        Ledger ledger = new JsonlLedger(ledgerPath, Clock.systemUTC());

        Map<String, Classification> verdicts = classify(transactions, useLlm, model, outDir);

        Map<ActionType, int[]> byAction = new EnumMap<>(ActionType.class);
        Map<ActionType, long[]> valueByAction = new EnumMap<>(ActionType.class);
        Map<String, Integer> gateReasons = new HashMap<>();
        long plannedCostPaise = 0L;

        for (FailedTransaction.AgentView txn : transactions) {
            // Each transaction is decided as of shortly after it failed.
            Clock asOfArrival = Clock.fixed(txn.failedAt().plus(PICKUP_DELAY), ZoneOffset.UTC);
            DefaultRecoveryPolicy policy = new DefaultRecoveryPolicy(config, asOfArrival);

            RecoveryDecision decision = policy.decide(txn, verdicts.get(txn.txnId()), ledger);
            ledger.recordDecision(decision);

            byAction.computeIfAbsent(decision.action(), key -> new int[1])[0]++;
            valueByAction.computeIfAbsent(decision.action(), key -> new long[1])[0] += txn.amountPaise();
            gateReasons.merge(shorten(decision.gateReason()), 1, Integer::sum);
            plannedCostPaise += decision.costPaise();
        }

        writeSummary(outDir.resolve("decisions_summary.md"), config, transactions.size(),
                byAction, valueByAction, gateReasons, plannedCostPaise, ledger);

        System.out.println();
        System.out.printf("%-24s %6s %16s%n", "action", "count", "value at stake");
        for (ActionType action : ActionType.values()) {
            int count = byAction.getOrDefault(action, new int[1])[0];
            long value = valueByAction.getOrDefault(action, new long[1])[0];
            System.out.printf("%-24s %6d %14s%n", action, count, "Rs " + rupees(value));
        }
        System.out.printf("%nplanned attempt cost: Rs %s%n", rupees(plannedCostPaise));
        System.out.println("ledger  -> " + ledgerPath.toAbsolutePath());
        System.out.println("summary -> " + outDir.resolve("decisions_summary.md").toAbsolutePath());
    }

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

    private void writeSummary(Path path,
                              PolicyConfig config,
                              int total,
                              Map<ActionType, int[]> byAction,
                              Map<ActionType, long[]> valueByAction,
                              Map<String, Integer> gateReasons,
                              long plannedCostPaise,
                              Ledger ledger) throws IOException {
        Files.createDirectories(path.getParent());

        StringBuilder sb = new StringBuilder();
        sb.append("# Decision pass\n\n")
                .append(total).append(" transactions, each decided as of five minutes after it failed.\n\n")
                .append("## Safety envelope in force\n\n")
                .append("| Rule | Value |\n|---|---:|\n")
                .append("| Max attempts per transaction | ").append(config.maxAttemptsPerTxn()).append(" |\n")
                .append("| Age cutoff | ").append(config.maxAgeDays()).append(" days |\n")
                .append("| Classifier confidence floor | ").append(config.minClassifierConfidence()).append(" |\n")
                .append("| Payment link cost | Rs ").append(rupees(config.paymentLinkCostPaise())).append(" |\n\n")
                .append("## Actions chosen\n\n")
                .append("| Action | Count | Value at stake | Moves money |\n|---|---:|---:|:--:|\n");

        for (ActionType action : ActionType.values()) {
            sb.append("| ").append(action).append(" | ")
                    .append(byAction.getOrDefault(action, new int[1])[0]).append(" | Rs ")
                    .append(rupees(valueByAction.getOrDefault(action, new long[1])[0])).append(" | ")
                    .append(PolicyConfig.movesMoney(action) ? "yes" : "no").append(" |\n");
        }

        sb.append("\nPlanned attempt cost across the batch: Rs ").append(rupees(plannedCostPaise))
                .append(". Recovered value is reported net of this.\n\n")
                .append("## Why the engine stopped where it did\n\n")
                .append("| Gate reason | Count |\n|---|---:|\n");
        gateReasons.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(entry -> sb.append("| ").append(entry.getKey()).append(" | ")
                        .append(entry.getValue()).append(" |\n"));

        sb.append("\n## Audit trail\n\n")
                .append(ledger.all().size()).append(" rows written to `ledger.jsonl`. ")
                .append("Every row carries the classifier's reason and confidence, the model's own\n")
                .append("justification, and the gate that allowed or blocked the action. The file is\n")
                .append("append-only: a correction is a new row, never an edit.\n");

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Gate reasons embed counts and timestamps; trim to the shape for grouping. */
    private String shorten(String gateReason) {
        int semicolon = gateReason.indexOf(';');
        String head = semicolon > 0 ? gateReason.substring(semicolon + 1).trim() : gateReason;
        return head.length() > 80 ? head.substring(0, 77) + "..." : head;
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
