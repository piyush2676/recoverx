package com.recoverx.eval;

import com.recoverx.classify.Classification;
import com.recoverx.classify.FailureClassifier;
import com.recoverx.classify.LlmClassifier;
import com.recoverx.classify.RuleBasedClassifier;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.eval.ClassificationScorer.ArmMetrics;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Runs both classifier arms over the same batch and writes the head-to-head report.
 *
 * <p>The rule-based arm always runs. The LLM arm runs only when credentials resolve;
 * without them the run prints why it skipped and still produces the rules-only
 * report. A missing API key should not cost you the baseline numbers.
 */
@Component
public class ClassifierRunner implements CommandLineRunner {

    private static final String DEFAULT_MODEL = "claude-opus-5";

    @Override
    public void run(String... args) throws Exception {
        if (!Arrays.asList(args).contains("--classify")) {
            return;
        }

        Path dataDir = argPath(args, "--data=", "data");
        Path outDir = argPath(args, "--out=", "eval/out");
        String model = argValue(args, "--model=", DEFAULT_MODEL);

        DatasetLoader loader = new DatasetLoader();
        List<FailedTransaction.AgentView> transactions = loader.loadTransactions(dataDir);
        Map<String, FailedTransaction.GroundTruth> truth = loader.loadGroundTruth(dataDir);
        System.out.printf("loaded %d transactions from %s%n", transactions.size(), dataDir.toAbsolutePath());

        List<ArmMetrics> allMetrics = new ArrayList<>();
        StringBuilder notes = new StringBuilder();

        // ---- arm 1: rules ----
        FailureClassifier rules = new RuleBasedClassifier();
        Map<String, Classification> ruleResults = new HashMap<>();
        long ruleStart = System.nanoTime();
        for (FailedTransaction.AgentView txn : transactions) {
            ruleResults.put(txn.txnId(), rules.classify(txn));
        }
        long ruleMillis = (System.nanoTime() - ruleStart) / 1_000_000;
        allMetrics.addAll(scoreAllSlices(rules.name(), transactions, truth, ruleResults));
        notes.append("- `").append(rules.name()).append("`: ").append(transactions.size())
                .append(" rows in ").append(ruleMillis).append(" ms, zero cost.\n");

        // ---- arm 2: llm ----
        if (LlmClassifier.credentialsAvailable()) {
            System.out.println("classifying with " + model + " ...");
            LlmClassifier llm = new LlmClassifier(model, outDir.resolve("llm_cache.jsonl"));
            long llmStart = System.nanoTime();
            Map<String, Classification> llmResults = llm.classifyAll(transactions);
            long llmSeconds = (System.nanoTime() - llmStart) / 1_000_000_000;
            allMetrics.addAll(scoreAllSlices(llm.name(), transactions, truth, llmResults));
            notes.append("- `").append(llm.name()).append("`: ").append(transactions.size())
                    .append(" rows in ").append(llmSeconds).append(" s across ")
                    .append(llm.apiCalls()).append(" API calls; ")
                    .append(llm.cacheHits()).append(" served from cache; ")
                    .append(llm.rejections()).append(" rejected by the validator; ")
                    .append(llm.transportFailures()).append(" never reached the validator.\n");
            if (llm.firstError() != null) {
                notes.append("- First transport error: `").append(llm.firstError()).append("`\n");
                System.out.println("LLM arm hit transport errors. First: " + llm.firstError());
            }
        } else {
            String skip = "- LLM arm skipped: no Anthropic credentials resolved. "
                    + "Set ANTHROPIC_API_KEY (or run `ant auth login`) and re-run to populate it.\n";
            notes.append(skip);
            System.out.println("skipping LLM arm - no credentials. Rules-only report will still be written.");
        }

        Path reportPath = outDir.resolve("classification_report.md");
        new ClassificationReport().write(reportPath, allMetrics, notes.toString());

        System.out.println();
        System.out.printf("%-22s %-12s %8s %8s %8s%n", "arm", "slice", "n", "accuracy", "coverage");
        for (ArmMetrics arm : allMetrics) {
            System.out.printf("%-22s %-12s %8d %7.1f%% %7.1f%%%n",
                    arm.arm(), arm.slice(), arm.total(),
                    arm.accuracy() * 100, arm.coverage() * 100);
        }
        System.out.println();
        System.out.println("report -> " + reportPath.toAbsolutePath());
    }

    /**
     * The same arm scored three ways. The "novel" slice is the one that decides
     * whether an LLM call is worth its cost here - it is the only slice the rule
     * table was never written against.
     */
    private List<ArmMetrics> scoreAllSlices(String arm,
                                            List<FailedTransaction.AgentView> transactions,
                                            Map<String, FailedTransaction.GroundTruth> truth,
                                            Map<String, Classification> results) {
        ClassificationScorer scorer = new ClassificationScorer();
        Predicate<FailedTransaction.GroundTruth> all = t -> true;
        Predicate<FailedTransaction.GroundTruth> documented = t -> !t.novelErrorForm();
        Predicate<FailedTransaction.GroundTruth> novel = FailedTransaction.GroundTruth::novelErrorForm;

        return List.of(
                scorer.score(arm, "all", transactions, truth, results, all),
                scorer.score(arm, "documented", transactions, truth, results, documented),
                scorer.score(arm, "novel", transactions, truth, results, novel));
    }

    private static String argValue(String[] args, String prefix, String fallback) {
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return arg.substring(prefix.length());
            }
        }
        return fallback;
    }

    private static Path argPath(String[] args, String prefix, String fallback) {
        return Path.of(argValue(args, prefix, fallback));
    }
}
