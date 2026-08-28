package com.recoverx.classify;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.recoverx.domain.FailedTransaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Classifies gateway declines with Claude.
 *
 * <p>Three properties matter more than the accuracy number:
 *
 * <ol>
 *   <li><b>It can only ever return a verdict.</b> Nothing here moves money. The
 *       policy engine consumes {@link Classification} and makes every gated
 *       decision in plain Java.</li>
 *   <li><b>Every response is validated before it is trusted.</b> Unparseable output,
 *       an unknown bucket, a confidence outside 0..1, a transaction the model
 *       invented - each becomes a rejected classification that routes to a human.
 *       There is no path from a malformed response to a charge.</li>
 *   <li><b>Verdicts are cached by content.</b> Re-running the eval is free and
 *       reproducible.</li>
 * </ol>
 *
 * <p>Transactions are sent in batches with a cached system prompt, so the taxonomy is
 * billed once per batch rather than once per transaction. The prompt itself lives in
 * {@link ClassifierPrompt} and is shared verbatim with every other provider, so a
 * provider comparison measures the model and not the wording.
 */
public class LlmClassifier implements FailureClassifier {

    private final AnthropicClient client;
    private final String model;
    private final ClassificationCache cache;
    private final ResponseParser parser = new ResponseParser();

    private int apiCalls = 0;
    private int cacheHits = 0;
    private int rejections = 0;
    private int transportFailures = 0;
    private String firstError = null;

    public LlmClassifier(String model, Path cachePath) throws IOException {
        this.client = AnthropicOkHttpClient.fromEnv();
        this.model = model;
        this.cache = new ClassificationCache(cachePath);
    }

    /**
     * True when a credential source exists. Lets the runner skip this arm and still
     * produce the rule-based report rather than burning a run on auth failures.
     *
     * <p>Deliberately does not probe by constructing a client: the SDK builds one
     * happily with no credentials and only fails at request time, so a construction
     * check reports success and then every call 401s.
     */
    public static boolean credentialsAvailable() {
        if (notBlank(System.getenv("ANTHROPIC_API_KEY")) || notBlank(System.getenv("ANTHROPIC_AUTH_TOKEN"))) {
            return true;
        }
        // `ant auth login` writes a profile here that the SDK picks up with no env var.
        return Files.isDirectory(Path.of(System.getProperty("user.home", ""), ".config", "anthropic"));
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Classifies a whole batch. Prefer this over the single-transaction method: it is
     * what keeps the taxonomy prompt billed once per 25 rows instead of once per row.
     */
    public Map<String, Classification> classifyAll(List<FailedTransaction.AgentView> transactions) {
        Map<String, Classification> results = new HashMap<>();
        List<FailedTransaction.AgentView> pending = new ArrayList<>();

        for (FailedTransaction.AgentView txn : transactions) {
            String key = ClassificationCache.key(ClassifierPrompt.VERSION, txn);
            if (cache.has(key)) {
                results.put(txn.txnId(), cache.get(key));
                cacheHits++;
            } else {
                pending.add(txn);
            }
        }

        for (int start = 0; start < pending.size(); start += ClassifierPrompt.BATCH_SIZE) {
            List<FailedTransaction.AgentView> batch =
                    pending.subList(start, Math.min(start + ClassifierPrompt.BATCH_SIZE, pending.size()));
            int failuresBefore = transportFailures;
            Map<String, Classification> batchResults = classifyBatch(batch);
            results.putAll(batchResults);
            if (transportFailures == failuresBefore) {
                rejections += (int) batchResults.values().stream()
                        .filter(c -> !c.accepted()).count();
            }
            persist(batch, batchResults);
            System.out.printf("  classified %d/%d%n",
                    Math.min(start + ClassifierPrompt.BATCH_SIZE, pending.size()), pending.size());
        }
        return results;
    }

    @Override
    public Classification classify(FailedTransaction.AgentView txn) {
        return classifyAll(List.of(txn))
                .getOrDefault(txn.txnId(), Classification.rejected("no verdict returned"));
    }

    @Override
    public String name() {
        return "llm:" + model;
    }

    public int apiCalls() {
        return apiCalls;
    }

    public int cacheHits() {
        return cacheHits;
    }

    public int rejections() {
        return rejections;
    }

    /** Rows that never reached the validator because the API call itself failed. */
    public int transportFailures() {
        return transportFailures;
    }

    /** First transport error seen, so a failed run is diagnosable from the report. */
    public String firstError() {
        return firstError;
    }

    // ---------- internals ----------

    private Map<String, Classification> classifyBatch(List<FailedTransaction.AgentView> batch) {
        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(16000L)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    // Bucketing a decline is not hard reasoning. Low effort keeps the
                    // cost defensible at 500 rows without measurably moving accuracy -
                    // and the report shows both cost and accuracy, so that claim is checkable.
                    .outputConfig(OutputConfig.builder()
                            .effort(OutputConfig.Effort.LOW)
                            .build())
                    .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                            .text(ClassifierPrompt.SYSTEM)
                            .cacheControl(CacheControlEphemeral.builder().build())
                            .build()))
                    .addUserMessage(ClassifierPrompt.renderBatch(batch))
                    .build();

            Message response = client.messages().create(params);
            apiCalls++;
            return parser.parse(collectText(response), batch);

        } catch (AnthropicServiceException e) {
            // The API said no. Every transaction in this batch goes to a human; not one
            // of them proceeds on a guess.
            String note = "API error" + e.errorType().map(t -> " (" + t + ")").orElse("")
                    + ": " + e.getMessage();
            return recordTransportFailure(batch, note);
        } catch (RuntimeException e) {
            return recordTransportFailure(batch,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * The API never answered. These rows still go to a human - nothing proceeds on a
     * guess - but they are counted apart from validator rejections. Conflating the two
     * would let an auth failure masquerade as "the model produced bad output".
     */
    private Map<String, Classification> recordTransportFailure(
            List<FailedTransaction.AgentView> batch, String note) {
        transportFailures += batch.size();
        if (firstError == null) {
            firstError = note;
        }
        return rejectAll(batch, note + "; routed to human review");
    }

    private Map<String, Classification> rejectAll(List<FailedTransaction.AgentView> batch, String note) {
        Map<String, Classification> out = new HashMap<>();
        for (FailedTransaction.AgentView txn : batch) {
            out.put(txn.txnId(), Classification.rejected(note));
        }
        return out;
    }

    private void persist(List<FailedTransaction.AgentView> batch, Map<String, Classification> results) {
        for (FailedTransaction.AgentView txn : batch) {
            Classification classification = results.get(txn.txnId());
            // Do not cache rejections. They are usually transient (an API error, a
            // truncated response) and caching them would freeze a bad run into the report.
            if (classification == null || !classification.accepted()) {
                continue;
            }
            try {
                cache.put(ClassificationCache.key(ClassifierPrompt.VERSION, txn), classification);
            } catch (IOException e) {
                System.err.println("  cache write failed for " + txn.txnId() + ": " + e.getMessage());
            }
        }
    }

    private String collectText(Message response) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(text -> sb.append(text.text()));
        }
        return sb.toString();
    }
}
