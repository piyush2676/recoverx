package com.recoverx.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recoverx.domain.FailedTransaction;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Classifies gateway declines with Google's Gemini models.
 *
 * <p>Same contract as {@link LlmClassifier}: it returns a verdict and nothing else.
 * Nothing here moves money, every response is validated by {@link ResponseParser}
 * before anything downstream believes it, and verdicts are cached by content so a
 * re-run is free and reproducible.
 *
 * <p>Both providers are driven by {@link ClassifierPrompt}, verbatim. That is what
 * makes a provider comparison a comparison of models rather than of prompts.
 *
 * <p><b>Raw HTTP rather than a client library</b>, for the same reason as the Razorpay
 * executor: the REST contract is documented and stable, it adds no dependency, and
 * there are no credentials in CI to integration-test a library against. The key is
 * sent in the {@code x-goog-api-key} header rather than the {@code ?key=} query
 * parameter the docs also allow - a secret in a URL ends up in proxy logs and browser
 * history.
 */
public class GeminiClassifier implements FailureClassifier {

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponseParser parser = new ResponseParser();

    private final String model;
    private final String apiKey;
    private final ClassificationCache cache;

    private int apiCalls = 0;
    private int cacheHits = 0;
    private int rejections = 0;
    private int transportFailures = 0;
    private String firstError = null;

    public GeminiClassifier(String model, Path cachePath) throws IOException {
        this.model = model;
        this.apiKey = System.getenv("GEMINI_API_KEY");
        this.cache = new ClassificationCache(cachePath);
    }

    public static boolean credentialsAvailable() {
        String key = System.getenv("GEMINI_API_KEY");
        return key != null && !key.isBlank();
    }

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
            List<FailedTransaction.AgentView> batch = pending.subList(
                    start, Math.min(start + ClassifierPrompt.BATCH_SIZE, pending.size()));

            int failuresBefore = transportFailures;
            Map<String, Classification> batchResults = classifyBatch(batch);
            results.putAll(batchResults);
            if (transportFailures == failuresBefore) {
                rejections += (int) batchResults.values().stream().filter(c -> !c.accepted()).count();
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
        return "gemini:" + model;
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

    /** Rows that never reached the validator because the call itself failed. */
    public int transportFailures() {
        return transportFailures;
    }

    public String firstError() {
        return firstError;
    }

    // ---------- internals ----------

    private Map<String, Classification> classifyBatch(List<FailedTransaction.AgentView> batch) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(String.format(ENDPOINT, model)))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("x-goog-api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body(batch), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                // Model ids get retired. Rather than leave the operator to go and find out
                // which, ask the API what this key can actually reach and say so.
                return recordTransportFailure(batch, "model \"" + model + "\" is not available to "
                        + "this key (it may have been retired). Reachable models: " + availableModels()
                        + ". Re-run with --model=<one of those>.");
            }
            if (response.statusCode() / 100 != 2) {
                return recordTransportFailure(batch,
                        "HTTP " + response.statusCode() + ": " + firstLine(response.body()));
            }

            apiCalls++;
            return parser.parse(extractText(response.body()), batch);

        } catch (IOException e) {
            return recordTransportFailure(batch,
                    "transport failure: " + e.getClass().getSimpleName() + " " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return recordTransportFailure(batch, "interrupted while calling Gemini");
        } catch (RuntimeException e) {
            return recordTransportFailure(batch, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    String body(List<FailedTransaction.AgentView> batch) {
        ObjectNode root = mapper.createObjectNode();

        ArrayNode contents = root.putArray("contents");
        ObjectNode turn = contents.addObject();
        turn.put("role", "user");
        turn.putArray("parts").addObject().put("text", ClassifierPrompt.renderBatch(batch));

        root.putObject("systemInstruction")
                .putArray("parts").addObject().put("text", ClassifierPrompt.SYSTEM);

        ObjectNode config = root.putObject("generationConfig");
        // Classification, not prose. Deterministic output keeps the report stable.
        config.put("temperature", 0);
        config.put("maxOutputTokens", 8192);
        config.put("responseMimeType", "text/plain");
        return root.toString();
    }

    /**
     * Pulls the generated text out of the response.
     *
     * <p>Returns an empty string rather than throwing when the shape is unexpected or
     * the model was cut off - the validator then declines every row in the batch and
     * they go to a human, which is the correct outcome for output we cannot read.
     */
    String extractText(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                return "";
            }
            JsonNode candidate = candidates.get(0);

            // SAFETY, BLOCKED or MAX_TOKENS means the text is absent or truncated.
            String finish = candidate.path("finishReason").asText("");
            if (!finish.isEmpty() && !"STOP".equals(finish)) {
                return "";
            }

            StringBuilder text = new StringBuilder();
            for (JsonNode part : candidate.path("content").path("parts")) {
                text.append(part.path("text").asText(""));
            }
            return text.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private Map<String, Classification> recordTransportFailure(
            List<FailedTransaction.AgentView> batch, String note) {
        transportFailures += batch.size();
        if (firstError == null) {
            firstError = note;
        }
        Map<String, Classification> out = new HashMap<>();
        for (FailedTransaction.AgentView txn : batch) {
            out.put(txn.txnId(), Classification.rejected(note + "; routed to human review"));
        }
        return out;
    }

    private void persist(List<FailedTransaction.AgentView> batch, Map<String, Classification> results) {
        for (FailedTransaction.AgentView txn : batch) {
            Classification classification = results.get(txn.txnId());
            // Rejections are usually transient; caching one would freeze a bad run
            // into every later report.
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

    /**
     * Best-effort lookup of the models this key can use, for the 404 message. Failure
     * here must not mask the original error, so anything unexpected returns a note
     * rather than throwing.
     */
    private String availableModels() {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("https://generativelanguage.googleapis.com/v1beta/models"))
                    .timeout(Duration.ofSeconds(30))
                    .header("x-goog-api-key", apiKey)
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return "(could not list models: HTTP " + response.statusCode() + ")";
            }

            List<String> names = new ArrayList<>();
            for (JsonNode entry : mapper.readTree(response.body()).path("models")) {
                String name = entry.path("name").asText("").replace("models/", "");
                // Only what this classifier can actually use.
                boolean generates = false;
                for (JsonNode method : entry.path("supportedGenerationMethods")) {
                    generates |= "generateContent".equals(method.asText());
                }
                if (generates && !name.isBlank()) {
                    names.add(name);
                }
            }
            return names.isEmpty() ? "(none reported)" : String.join(", ", names);
        } catch (IOException | RuntimeException e) {
            return "(could not list models: " + e.getClass().getSimpleName() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "(interrupted while listing models)";
        }
    }

    private String firstLine(String body) {
        if (body == null) {
            return "";
        }
        String head = body.replace('\n', ' ').trim();
        return head.length() > 240 ? head.substring(0, 240) : head;
    }
}
