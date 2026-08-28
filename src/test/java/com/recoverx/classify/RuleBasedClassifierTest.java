package com.recoverx.classify;

import com.recoverx.domain.CustomerProfile;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.FailureReason;
import com.recoverx.domain.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedClassifierTest {

    private final RuleBasedClassifier classifier = new RuleBasedClassifier();

    private static FailedTransaction.AgentView txn(String code, String message) {
        return new FailedTransaction.AgentView(
                "pay_x", "order_x", "acc_TEST", 50000L, "INR", PaymentMethod.UPI,
                Instant.parse("2026-08-01T10:00:00Z"), code, message, "HDFC",
                new CustomerProfile("cust_1", 1, 0.8, PaymentMethod.UPI, 1, true), 1);
    }

    @Test
    void aDocumentedCodeIsDecisive() {
        Classification result = classifier.classify(txn("U30", "anything at all"));
        assertEquals(FailureReason.INSUFFICIENT_FUNDS, result.reason());
        assertEquals(0.95, result.confidence(), 1e-9);
    }

    @Test
    void fallsBackToKeywordsWhenTheCodeIsUnknown() {
        Classification result = classifier.classify(
                txn("SOMETHING_NEW", "Issuer bank is down. Please try again after some time."));
        assertEquals(FailureReason.ISSUER_DOWN, result.reason());
        assertEquals(0.75, result.confidence(), 1e-9);
    }

    /** When two readings are possible, the one that stops the money wins. */
    @Test
    void riskWinsOverAmbiguity() {
        Classification result = classifier.classify(
                txn("UNKNOWN", "velocity limit hit; suspected fraud, do not retry"));
        assertEquals(FailureReason.RISK_BLOCKED, result.reason());
    }

    /**
     * The point of the baseline: it has no way to read a wording nobody wrote a rule
     * for. It declines rather than guessing, which is safe - and it is exactly the gap
     * the LLM arm is measured against.
     */
    @Test
    void declinesOnAnIso8583FormItWasNeverWrittenFor() {
        Classification result = classifier.classify(
                txn("ISO8583", "DE39=116 | acq=HDFC | narration: khaate mein paisa kam hai"));
        assertFalse(result.accepted());
        assertTrue(result.justification().contains("routed to a human"));
    }

    @Test
    void aDeclinedRowCarriesNoReasonAndNoConfidence() {
        Classification result = classifier.classify(txn("WHO_KNOWS", "completely opaque vendor string"));
        assertFalse(result.accepted());
        assertEquals(0.0, result.confidence(), 1e-9);
    }
}
