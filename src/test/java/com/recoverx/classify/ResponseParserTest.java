package com.recoverx.classify;

import com.recoverx.domain.CustomerProfile;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.FailureReason;
import com.recoverx.domain.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The validator is the only thing standing between a bad model response and a charge,
 * so every failure mode gets a test. None of these need an API key.
 */
class ResponseParserTest {

    private final ResponseParser parser = new ResponseParser();

    static FailedTransaction.AgentView txn(String id) {
        return new FailedTransaction.AgentView(
                id, "order_" + id, "acc_TEST", 50000L, "INR", PaymentMethod.UPI,
                Instant.parse("2026-08-01T10:00:00Z"), "U30",
                "UPI decline U30 - insufficient balance in linked account", "HDFC",
                new CustomerProfile("cust_1", 1, 0.8, PaymentMethod.UPI, 1, true), 1);
    }

    @Test
    void acceptsWellFormedOutput() {
        var batch = List.of(txn("pay_a"), txn("pay_b"));
        String response = """
                {"txn_id":"pay_a","reason":"INSUFFICIENT_FUNDS","confidence":0.93,"justification":"Text says insufficient balance."}
                {"txn_id":"pay_b","reason":"RISK_BLOCKED","confidence":0.71,"justification":"Text says compliance hold."}
                """;

        Map<String, Classification> results = parser.parse(response, batch);
        assertEquals(FailureReason.INSUFFICIENT_FUNDS, results.get("pay_a").reason());
        assertEquals(0.93, results.get("pay_a").confidence(), 1e-9);
        assertEquals(FailureReason.RISK_BLOCKED, results.get("pay_b").reason());
    }

    @Test
    void survivesAMarkdownFence() {
        var batch = List.of(txn("pay_a"));
        String response = """
                ```json
                {"txn_id":"pay_a","reason":"NETWORK_TIMEOUT","confidence":0.8,"justification":"Read timeout."}
                ```
                """;
        assertEquals(FailureReason.NETWORK_TIMEOUT, parser.parse(response, batch).get("pay_a").reason());
    }

    @Test
    void rejectsAnUnknownBucket() {
        var batch = List.of(txn("pay_a"));
        String response = """
                {"txn_id":"pay_a","reason":"CUSTOMER_CHANGED_MIND","confidence":0.9,"justification":"Made up."}
                """;
        Classification result = parser.parse(response, batch).get("pay_a");
        assertFalse(result.accepted());
        assertTrue(result.justification().contains("unknown bucket"));
    }

    @Test
    void rejectsConfidenceOutOfRange() {
        var batch = List.of(txn("pay_a"));
        String response = """
                {"txn_id":"pay_a","reason":"ISSUER_DOWN","confidence":1.7,"justification":"Bank down."}
                """;
        assertFalse(parser.parse(response, batch).get("pay_a").accepted());
    }

    @Test
    void rejectsMissingOrNonNumericConfidence() {
        var batch = List.of(txn("pay_a"), txn("pay_b"));
        String response = """
                {"txn_id":"pay_a","reason":"ISSUER_DOWN","justification":"No confidence field."}
                {"txn_id":"pay_b","reason":"ISSUER_DOWN","confidence":"high","justification":"Not a number."}
                """;
        assertFalse(parser.parse(response, batch).get("pay_a").accepted());
        assertFalse(parser.parse(response, batch).get("pay_b").accepted());
    }

    @Test
    void rejectsAnEmptyJustification() {
        var batch = List.of(txn("pay_a"));
        String response = """
                {"txn_id":"pay_a","reason":"EXPIRED_CARD","confidence":0.9,"justification":"   "}
                """;
        Classification result = parser.parse(response, batch).get("pay_a");
        assertFalse(result.accepted());
        assertTrue(result.justification().contains("audit row"));
    }

    /** A model that answers about a transaction nobody asked about must not be believed. */
    @Test
    void ignoresAHallucinatedTransactionId() {
        var batch = List.of(txn("pay_a"));
        String response = """
                {"txn_id":"pay_NEVER_SENT","reason":"INSUFFICIENT_FUNDS","confidence":0.9,"justification":"Invented."}
                """;
        Map<String, Classification> results = parser.parse(response, batch);
        assertEquals(1, results.size());
        assertFalse(results.get("pay_a").accepted());
    }

    @Test
    void rejectsEveryRowWhenTheResponseIsGarbage() {
        var batch = List.of(txn("pay_a"), txn("pay_b"));
        Map<String, Classification> results = parser.parse("I'm sorry, I can't help with that.", batch);
        assertEquals(2, results.size());
        results.values().forEach(result -> assertFalse(result.accepted()));
    }

    @Test
    void rejectsOnlyTheRowsThatWereTruncatedAway() {
        var batch = List.of(txn("pay_a"), txn("pay_b"), txn("pay_c"));
        String response = """
                {"txn_id":"pay_a","reason":"LIMIT_EXCEEDED","confidence":0.85,"justification":"Daily cap."}
                {"txn_id":"pay_b","reason":"LIMIT_EX
                """;
        Map<String, Classification> results = parser.parse(response, batch);
        assertTrue(results.get("pay_a").accepted());
        assertFalse(results.get("pay_b").accepted());
        assertFalse(results.get("pay_c").accepted());
    }

    @Test
    void firstLineWinsWhenATransactionIsAnsweredTwice() {
        var batch = List.of(txn("pay_a"));
        String response = """
                {"txn_id":"pay_a","reason":"INVALID_VPA","confidence":0.9,"justification":"First."}
                {"txn_id":"pay_a","reason":"RISK_BLOCKED","confidence":0.4,"justification":"Second."}
                """;
        assertEquals(FailureReason.INVALID_VPA, parser.parse(response, batch).get("pay_a").reason());
    }

    @Test
    void everyTransactionInTheBatchGetsAVerdict() {
        var batch = List.of(txn("pay_a"), txn("pay_b"), txn("pay_c"));
        Map<String, Classification> results = parser.parse("", batch);
        assertEquals(3, results.size());
        batch.forEach(t -> assertTrue(results.containsKey(t.txnId())));
    }
}
