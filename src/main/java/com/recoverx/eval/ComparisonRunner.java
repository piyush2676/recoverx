package com.recoverx.eval;

import com.recoverx.classify.Classification;
import com.recoverx.classify.FailureClassifier;
import com.recoverx.classify.LlmClassifier;
import com.recoverx.classify.RuleBasedClassifier;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.eval.RecoveryCycle.ArmResult;
import com.recoverx.policy.PolicyConfig;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs every arm over the same batch and writes the comparison.
 *
 * <p>Three columns, always: what a merchant already does, what RecoverX does, and what
 * perfect knowledge would have done. A recovery figure on its own is unreadable - it
 * could be excellent or it could be most of what a one-line retry loop already gets for
 * free. The baseline says which, and the ceiling says how much room was left.
 */
@Component
public class ComparisonRunner implements CommandLineRunner {

    private static final String DEFAULT_MODEL = "claude-opus-5";

    @Override
    public void run(String... args) throws Exception {
        List<String> argList = Arrays.asList(args);
        if (!argList.contains("--compare")) {
            return;
        }

        Path dataDir = pathArg(args, "--data=", "data");
        Path outDir = pathArg(args, "--out=", "eval/out");
        String model = stringArg(args, "--model=", DEFAULT_MODEL);

        DatasetLoader loader = new DatasetLoader();
        List<FailedTransaction.AgentView> transactions = loader.loadTransactions(dataDir);
        Map<String, FailedTransaction.GroundTruth> truth = loader.loadGroundTruth(dataDir);
        System.out.printf("comparing arms over %d transactions%n%n", transactions.size());

        PolicyConfig config = PolicyConfig.defaults();
        RecoveryCycle cycle = new RecoveryCycle(transactions, truth, config);
        Path arms = outDir.resolve("arms");

        List<ArmResult> results = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        // Two baselines: the quick integration, and the retry cron a real merchant runs.
        results.add(cycle.run(new NaiveDecisionSource(java.time.Duration.ZERO, "naive-immediate"),
                arms.resolve("naive-immediate"), false, null));
        results.add(cycle.run(new NaiveDecisionSource(java.time.Duration.ofHours(1), "naive-hourly"),
                arms.resolve("naive-hourly"), false, null));

        Map<String, Classification> ruleVerdicts = classifyWithRules(transactions);
        results.add(cycle.run(new PolicyDecisionSource(config, ruleVerdicts, "recoverx-rules"),
                arms.resolve("recoverx-rules"), false, null));

        if (LlmClassifier.credentialsAvailable()) {
            System.out.println("classifying with " + model + " ...");
            LlmClassifier llm = new LlmClassifier(model, outDir.resolve("llm_cache.jsonl"));
            Map<String, Classification> llmVerdicts = llm.classifyAll(transactions);
            results.add(cycle.run(new PolicyDecisionSource(config, llmVerdicts, "recoverx-llm"),
                    arms.resolve("recoverx-llm"), false, null));
            notes.add("`recoverx-llm` used `" + model + "`, " + llm.apiCalls() + " API calls, "
                    + llm.rejections() + " responses rejected by the validator.");
        } else {
            notes.add("`recoverx-llm` was not run: no Anthropic credentials resolved. "
                    + "Set `ANTHROPIC_API_KEY` and re-run to fill that row.");
            System.out.println("skipping the LLM arm - no credentials");
        }

        ArmResult oracle = cycle.run(new OracleDecisionSource(truth), arms.resolve("oracle"), false, null);
        results.add(oracle);

        // The harness checking itself. If the oracle cannot collect the ceiling, then
        // OracleDecisionSource and SimulatedExecutor disagree about the resolution rules
        // and every other number here is suspect.
        String selfCheck = oracle.recoveredPaise() == oracle.ceilingPaise()
                ? "PASS - the oracle collected exactly the ceiling."
                : String.format("FAIL - the oracle collected Rs %s of a Rs %s ceiling. "
                        + "The oracle and the simulator disagree; treat every row below as suspect.",
                rupees(oracle.recoveredPaise()), rupees(oracle.ceilingPaise()));
        notes.add("Harness self-check: " + selfCheck);

        writeReport(outDir.resolve("comparison_report.md"), results, config, notes, truth, transactions);
        writeJson(outDir.resolve("comparison.json"), results, truth, transactions, selfCheck);
        print(results, selfCheck, outDir);
    }

    /**
     * The same results as machine-readable JSON, so the dashboard can display a run
     * without recomputing anything. One source of truth on disk, two renderings.
     */
    private void writeJson(Path path,
                           List<ArmResult> results,
                           Map<String, FailedTransaction.GroundTruth> truth,
                           List<FailedTransaction.AgentView> transactions,
                           String selfCheck) throws IOException {
        Map<String, Long> amounts = new HashMap<>();
        transactions.forEach(txn -> amounts.put(txn.txnId(), txn.amountPaise()));

        Map<String, Long> ceilingByReason = new LinkedHashMap<>();
        for (FailedTransaction.AgentView txn : transactions) {
            FailedTransaction.GroundTruth groundTruth = truth.get(txn.txnId());
            if (groundTruth != null && groundTruth.truth().recoverable()) {
                ceilingByReason.merge(groundTruth.trueReason().name(), txn.amountPaise(), Long::sum);
            }
        }
        Map<String, Long> sortedCeiling = new LinkedHashMap<>();
        ceilingByReason.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> sortedCeiling.put(entry.getKey(), entry.getValue()));

        List<Map<String, Object>> arms = new ArrayList<>();
        for (ArmResult arm : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("arm", arm.arm());
            row.put("attempts", arm.attempts());
            row.put("successes", arm.successes());
            row.put("recoveredPaise", arm.recoveredPaise());
            row.put("costPaise", arm.costPaise());
            row.put("netPaise", arm.netPaise());
            row.put("recoveryRate", arm.recoveryRate());
            row.put("rateAgainstTotalFailed", arm.rateAgainstTotalFailed());
            row.put("complianceViolations", arm.complianceViolations());
            row.put("doubleCharges", arm.doubleCharges());
            row.put("wastedAttempts", arm.wastedAttempts());
            row.put("escalations", arm.escalations());
            row.put("recoveredByReason", recoveredByReason(arm, truth, amounts));
            arms.add(row);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", java.time.Instant.now().toString());
        root.put("transactions", results.get(0).transactions());
        root.put("ceilingPaise", results.get(0).ceilingPaise());
        root.put("totalFailedPaise", results.get(0).totalFailedPaise());
        root.put("selfCheck", selfCheck);
        root.put("ceilingByReason", sortedCeiling);
        root.put("arms", arms);

        Files.writeString(path, new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(root), StandardCharsets.UTF_8);
    }

    // ---------- reporting ----------

    private void print(List<ArmResult> results, String selfCheck, Path outDir) {
        System.out.printf("%-16s %8s %8s %14s %10s %8s %8s%n",
                "arm", "attempts", "wins", "net", "% ceiling", "violations", "wasted");
        for (ArmResult arm : results) {
            System.out.printf("%-16s %8d %8d %14s %9.1f%% %8d %8d%n",
                    arm.arm(), arm.attempts(), arm.successes(), "Rs " + rupees(arm.netPaise()),
                    arm.recoveryRate() * 100, arm.complianceViolations(), arm.wastedAttempts());
        }
        System.out.println();
        System.out.println("self-check: " + selfCheck);
        System.out.println("report -> " + outDir.resolve("comparison_report.md").toAbsolutePath());
    }

    private void writeReport(Path path,
                             List<ArmResult> results,
                             PolicyConfig config,
                             List<String> notes,
                             Map<String, FailedTransaction.GroundTruth> truth,
                             List<FailedTransaction.AgentView> transactions) throws IOException {
        Files.createDirectories(path.getParent());
        ArmResult reference = results.get(0);

        StringBuilder sb = new StringBuilder();
        sb.append("# Recovery comparison\n\n")
                .append("Every arm ran over the same ").append(reference.transactions())
                .append(" transactions, through the same execution loop, ledger and simulator. ")
                .append("The only thing that differs between rows is the strategy.\n\n")
                .append("| Arm | Attempts | Recoveries | Recovered | Cost | Net | % of ceiling |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|\n");
        for (ArmResult arm : results) {
            sb.append("| `").append(arm.arm()).append("` | ").append(arm.attempts()).append(" | ")
                    .append(arm.successes()).append(" | Rs ").append(rupees(arm.recoveredPaise()))
                    .append(" | Rs ").append(rupees(arm.costPaise()))
                    .append(" | **Rs ").append(rupees(arm.netPaise())).append("** | ")
                    .append(String.format("%.1f%%", arm.recoveryRate() * 100)).append(" |\n");
        }

        sb.append("\n`naive-immediate` re-presents the instant a payment fails; `naive-hourly`\n")
                .append("waits an hour, which is what a merchant's retry cron actually does. The\n")
                .append("hourly variant is a far stronger opponent - it sweeps up timeouts and a\n")
                .append("good share of issuer outages while knowing nothing at all. Reporting only\n")
                .append("the immediate variant would have flattered RecoverX considerably.\n");

        sb.append("\nThe ceiling is Rs ").append(rupees(reference.ceilingPaise()))
                .append(" of Rs ").append(rupees(reference.totalFailedPaise()))
                .append(" in failed value - the rest was never recoverable by anyone.\n\n")
                .append("## The same numbers against the flattering denominator\n\n")
                .append("Quoting recovery against total failed value inflates it with money nobody\n")
                .append("could have collected. Both are shown so the difference is visible rather\n")
                .append("than a choice made quietly in a slide.\n\n")
                .append("| Arm | vs ceiling (honest) | vs total failed (inflated) |\n|---|---:|---:|\n");
        for (ArmResult arm : results) {
            sb.append("| `").append(arm.arm()).append("` | ")
                    .append(String.format("%.1f%%", arm.recoveryRate() * 100)).append(" | ")
                    .append(String.format("%.1f%%", arm.rateAgainstTotalFailed() * 100)).append(" |\n");
        }

        sb.append("\n## What the money figure costs\n\n")
                .append("A recovery number is only worth what was done to get it.\n\n")
                .append("| Arm | Compliance violations | Double charges | Wasted attempts | Escalated to a human |\n")
                .append("|---|---:|---:|---:|---:|\n");
        for (ArmResult arm : results) {
            sb.append("| `").append(arm.arm()).append("` | ")
                    .append(arm.complianceViolations()).append(" | ")
                    .append(arm.doubleCharges()).append(" | ")
                    .append(arm.wastedAttempts()).append(" | ")
                    .append(arm.escalations()).append(" |\n");
        }
        sb.append("\n**Compliance violations** are attempts on instruments flagged as never to be\n")
                .append("re-presented. **Wasted attempts** are attempts on declines nobody could have\n")
                .append("recovered - not a breach, but they cost money and customer patience.\n")
                .append("**Escalated** transactions were handed to a person rather than automated;\n")
                .append("they are the exception queue, not a failure.\n\n");

        appendByReason(sb, results, truth, transactions);

        sb.append("## Safety envelope\n\n")
                .append("| Rule | Value |\n|---|---:|\n")
                .append("| Max attempts per transaction | ").append(config.maxAttemptsPerTxn()).append(" |\n")
                .append("| Age cutoff | ").append(config.maxAgeDays()).append(" days |\n")
                .append("| Salary window (insufficient funds) | ").append(config.paydayWindowDays()).append(" days |\n")
                .append("| Classifier confidence floor | ").append(config.minClassifierConfidence()).append(" |\n")
                .append("| Payment link cost | Rs ").append(rupees(config.paymentLinkCostPaise())).append(" |\n\n")
                .append("## Notes\n\n");
        for (String note : notes) {
            sb.append("- ").append(note).append('\n');
        }
        sb.append("- A payment link is treated as satisfying any required rail, because the\n")
                .append("  customer chooses how to pay when they open it. This favours the policy arms\n")
                .append("  and is stated rather than buried.\n")
                .append("- Each transaction is decided as of five minutes after it failed, simulating\n")
                .append("  a system that reacts to arrivals rather than reading a month-old batch.\n")
                .append("- Per-arm audit trails: `arms/<arm>/ledger.jsonl`.\n");

        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Where each arm's money actually came from, and where it left money behind. */
    private void appendByReason(StringBuilder sb,
                                List<ArmResult> results,
                                Map<String, FailedTransaction.GroundTruth> truth,
                                List<FailedTransaction.AgentView> transactions) throws IOException {
        Map<String, Long> amounts = new HashMap<>();
        transactions.forEach(txn -> amounts.put(txn.txnId(), txn.amountPaise()));

        sb.append("## Recovered value by decline type\n\n")
                .append("| Decline | Ceiling |");
        for (ArmResult arm : results) {
            sb.append(" `").append(arm.arm()).append("` |");
        }
        sb.append("\n|---|---:|");
        results.forEach(arm -> sb.append("---:|"));
        sb.append('\n');

        Map<String, Map<String, Long>> recoveredByArm = new HashMap<>();
        for (ArmResult arm : results) {
            recoveredByArm.put(arm.arm(), recoveredByReason(arm, truth, amounts));
        }

        Map<String, Long> ceilingByReason = new HashMap<>();
        for (FailedTransaction.AgentView txn : transactions) {
            FailedTransaction.GroundTruth groundTruth = truth.get(txn.txnId());
            if (groundTruth != null && groundTruth.truth().recoverable()) {
                ceilingByReason.merge(groundTruth.trueReason().name(), txn.amountPaise(), Long::sum);
            }
        }

        ceilingByReason.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .forEach(entry -> {
                    sb.append("| ").append(entry.getKey()).append(" | Rs ")
                            .append(rupees(entry.getValue())).append(" |");
                    for (ArmResult arm : results) {
                        sb.append(" Rs ").append(rupees(
                                recoveredByArm.get(arm.arm()).getOrDefault(entry.getKey(), 0L))).append(" |");
                    }
                    sb.append('\n');
                });
        sb.append('\n');
    }

    private Map<String, Long> recoveredByReason(ArmResult arm,
                                                Map<String, FailedTransaction.GroundTruth> truth,
                                                Map<String, Long> amounts) throws IOException {
        Map<String, Long> byReason = new HashMap<>();
        for (String line : Files.readAllLines(arm.ledgerPath(), StandardCharsets.UTF_8)) {
            if (line.isBlank() || !line.contains("\"succeeded\":true")) {
                continue;
            }
            String txnId = between(line, "\"txnId\":\"", "\"");
            FailedTransaction.GroundTruth groundTruth = truth.get(txnId);
            if (groundTruth != null) {
                byReason.merge(groundTruth.trueReason().name(), amounts.getOrDefault(txnId, 0L), Long::sum);
            }
        }
        return byReason;
    }

    private String between(String source, String open, String close) {
        int start = source.indexOf(open);
        if (start < 0) {
            return "";
        }
        start += open.length();
        int end = source.indexOf(close, start);
        return end < 0 ? "" : source.substring(start, end);
    }

    private Map<String, Classification> classifyWithRules(List<FailedTransaction.AgentView> transactions) {
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
