package com.recoverx.classify;

import com.recoverx.domain.FailedTransaction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks whichever model provider has credentials.
 *
 * <p>Both integrations are kept. They share {@link ClassifierPrompt} verbatim, the same
 * validator, and the same content-hash cache, so switching provider changes the model
 * and nothing else - which is the only way the arms remain comparable.
 *
 * <p>Selection is by credential rather than by flag, so a run does the most it can with
 * whatever is set. When neither is present the caller is told plainly and the rule
 * baseline still runs; a missing key should never cost you the rest of the report.
 */
public final class ModelClassifiers {

    /** Free tier at aistudio.google.com. Override with --model= if your key reaches a different one. */
    public static final String DEFAULT_GEMINI_MODEL = "gemini-2.0-flash";

    public static final String DEFAULT_CLAUDE_MODEL = "claude-opus-5";

    private ModelClassifiers() {
    }

    /**
     * A provider-agnostic handle: the arm's label, its verdicts, and the counters that
     * keep an auth failure from being reported as bad model output.
     */
    public record Arm(
            String name,
            Map<String, Classification> verdicts,
            int apiCalls,
            int cacheHits,
            int rejections,
            int transportFailures,
            String firstError
    ) {
    }

    public static boolean anyCredentialsAvailable() {
        return GeminiClassifier.credentialsAvailable() || LlmClassifier.credentialsAvailable();
    }

    /** What a caller should print when it wants to say which provider will be used. */
    public static String describeAvailable() {
        if (GeminiClassifier.credentialsAvailable()) {
            return "Gemini (GEMINI_API_KEY)";
        }
        if (LlmClassifier.credentialsAvailable()) {
            return "Claude (ANTHROPIC_API_KEY)";
        }
        return "none";
    }

    /**
     * Runs the model arm, or returns empty when no provider has credentials.
     *
     * @param model null to use the selected provider's default
     */
    public static Optional<Arm> run(List<FailedTransaction.AgentView> transactions,
                                    String model,
                                    Path cachePath) throws IOException {
        if (GeminiClassifier.credentialsAvailable()) {
            String chosen = model != null ? model : DEFAULT_GEMINI_MODEL;
            GeminiClassifier gemini = new GeminiClassifier(chosen, cachePath);
            System.out.println("classifying with " + gemini.name() + " ...");
            Map<String, Classification> verdicts = gemini.classifyAll(transactions);
            return Optional.of(new Arm(gemini.name(), verdicts, gemini.apiCalls(), gemini.cacheHits(),
                    gemini.rejections(), gemini.transportFailures(), gemini.firstError()));
        }

        if (LlmClassifier.credentialsAvailable()) {
            String chosen = model != null ? model : DEFAULT_CLAUDE_MODEL;
            LlmClassifier claude = new LlmClassifier(chosen, cachePath);
            System.out.println("classifying with " + claude.name() + " ...");
            Map<String, Classification> verdicts = claude.classifyAll(transactions);
            return Optional.of(new Arm(claude.name(), verdicts, claude.apiCalls(), claude.cacheHits(),
                    claude.rejections(), claude.transportFailures(), claude.firstError()));
        }

        return Optional.empty();
    }
}
