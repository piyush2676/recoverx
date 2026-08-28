package com.recoverx.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverx.domain.CustomerProfile;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two halves of the Gemini integration that can be wrong without a network
 * call: the request body we send, and how a response is read.
 *
 * <p>The parsing of the model's actual verdicts is {@link ResponseParser}'s job and is
 * already covered there, so these tests deliberately stop at the boundary.
 */
class GeminiClassifierTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private GeminiClassifier classifier;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws IOException {
        classifier = new GeminiClassifier("gemini-2.0-flash", tmp.resolve("cache.jsonl"));
    }

    private static FailedTransaction.AgentView txn(String id) {
        return new FailedTransaction.AgentView(id, "order_" + id, "acc_TEST", 50000L, "INR",
                PaymentMethod.UPI, Instant.parse("2026-08-01T10:00:00Z"), "U30",
                "UPI decline U30 - insufficient balance", "HDFC",
                new CustomerProfile("cust_1", 1, 0.8, PaymentMethod.UPI, 1, true), 1);
    }

    @Test
    void theRequestBodyMatchesTheDocumentedShape() throws IOException {
        JsonNode body = mapper.readTree(classifier.body(List.of(txn("pay_a"), txn("pay_b"))));

        assertEquals("user", body.path("contents").get(0).path("role").asText());
        assertTrue(body.path("contents").get(0).path("parts").get(0).path("text").asText()
                .contains("pay_a"));
        assertTrue(body.path("systemInstruction").path("parts").get(0).path("text").asText()
                .contains("INSUFFICIENT_FUNDS"));
        assertEquals(0, body.path("generationConfig").path("temperature").asInt());
    }

    /** Both providers must send the identical prompt, or the comparison is of prompts. */
    @Test
    void theSystemInstructionIsTheSharedPrompt() throws IOException {
        JsonNode body = mapper.readTree(classifier.body(List.of(txn("pay_a"))));
        assertEquals(ClassifierPrompt.SYSTEM,
                body.path("systemInstruction").path("parts").get(0).path("text").asText());
    }

    @Test
    void extractsTextFromAWellFormedResponse() {
        String response = """
                {"candidates":[{"content":{"parts":[{"text":"line one\\nline two"}],"role":"model"},
                "finishReason":"STOP"}],"usageMetadata":{"totalTokenCount":60}}
                """;
        assertEquals("line one\nline two", classifier.extractText(response));
    }

    @Test
    void joinsMultipleParts() {
        String response = """
                {"candidates":[{"content":{"parts":[{"text":"first "},{"text":"second"}]},
                "finishReason":"STOP"}]}
                """;
        assertEquals("first second", classifier.extractText(response));
    }

    /**
     * A truncated or blocked completion yields no usable text. Returning empty makes the
     * validator decline every row in the batch, which sends them to a human - the right
     * outcome for output we cannot read.
     */
    @Test
    void aTruncatedOrBlockedCompletionYieldsNothing() {
        assertEquals("", classifier.extractText(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"half a li\"}]},\"finishReason\":\"MAX_TOKENS\"}]}"));
        assertEquals("", classifier.extractText(
                "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}"));
    }

    @Test
    void malformedOrEmptyResponsesYieldNothingRatherThanThrowing() {
        assertEquals("", classifier.extractText("not json at all"));
        assertEquals("", classifier.extractText("{}"));
        assertEquals("", classifier.extractText("{\"candidates\":[]}"));
        assertEquals("", classifier.extractText(""));
    }

    /** The end-to-end consequence: unreadable output never becomes a verdict. */
    @Test
    void unreadableOutputSendsEveryRowToAHuman() {
        var batch = List.of(txn("pay_a"), txn("pay_b"));
        var results = new ResponseParser().parse(classifier.extractText("{\"candidates\":[]}"), batch);

        assertEquals(2, results.size());
        results.values().forEach(r -> assertFalse(r.accepted()));
    }

    @Test
    void credentialsAreReportedAbsentWhenTheEnvVarIsUnset() {
        // The suite runs without GEMINI_API_KEY set; if that ever changes this test is
        // the thing that notices, rather than a run silently billing someone.
        if (System.getenv("GEMINI_API_KEY") == null) {
            assertFalse(GeminiClassifier.credentialsAvailable());
        }
    }
}
