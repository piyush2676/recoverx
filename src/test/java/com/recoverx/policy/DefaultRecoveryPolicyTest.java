package com.recoverx.policy;

import com.recoverx.classify.Classification;
import com.recoverx.domain.CustomerProfile;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.FailureReason;
import com.recoverx.domain.PaymentMethod;
import com.recoverx.executor.AttemptResult;
import com.recoverx.ledger.JsonlLedger;
import com.recoverx.ledger.Ledger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every gate gets a test. These are the rules that stand between the agent and a
 * charge it should not make, so "it looked right" is not good enough for any of them.
 */
class DefaultRecoveryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private DefaultRecoveryPolicy policy;
    private Ledger ledger;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws IOException {
        policy = new DefaultRecoveryPolicy(PolicyConfig.defaults(), CLOCK);
        ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
    }

    private static FailedTransaction.AgentView txn(Instant failedAt, boolean hasSavedCard, int payday) {
        return new FailedTransaction.AgentView(
                "pay_1", "order_1", "acc_TEST", 250000L, "INR", PaymentMethod.UPI,
                failedAt, "U30", "insufficient balance", "HDFC",
                new CustomerProfile("cust_1", payday, 0.8, PaymentMethod.UPI, 1, hasSavedCard), 1);
    }

    private static FailedTransaction.AgentView recentTxn() {
        return txn(NOW.minus(Duration.ofHours(3)), true, 1);
    }

    private static Classification confident(FailureReason reason) {
        return Classification.of(reason, 0.9, "test justification");
    }

    // ---------- gates ----------

    @Test
    void aRejectedClassificationGoesToAHumanNotToARetry() {
        RecoveryDecision decision = policy.decide(recentTxn(),
                Classification.rejected("could not read the error"), ledger);

        assertEquals(ActionType.ESCALATE_HUMAN, decision.action());
        assertNull(decision.scheduledFor());
        assertEquals(0L, decision.costPaise());
    }

    @Test
    void aRiskBlockedDeclineIsAHardStop() {
        RecoveryDecision decision = policy.decide(recentTxn(), confident(FailureReason.RISK_BLOCKED), ledger);

        assertEquals(ActionType.DO_NOTHING, decision.action());
        assertTrue(decision.gateReason().contains("compliance violation"));
        assertNull(decision.idempotencyKey());
    }

    /**
     * The risk gate runs before the confidence floor. A hesitant guess of RISK_BLOCKED
     * must still stop the money rather than falling through to the next gate.
     */
    @Test
    void riskBlockedStopsEvenBelowTheConfidenceFloor() {
        Classification unsure = Classification.of(FailureReason.RISK_BLOCKED, 0.31, "not sure, but risk");
        RecoveryDecision decision = policy.decide(recentTxn(), unsure, ledger);

        assertEquals(ActionType.DO_NOTHING, decision.action());
        assertTrue(decision.gateReason().contains("hard stop"));
    }

    @Test
    void lowConfidenceGoesToAHuman() {
        Classification unsure = Classification.of(FailureReason.INSUFFICIENT_FUNDS, 0.55, "maybe");
        RecoveryDecision decision = policy.decide(recentTxn(), unsure, ledger);

        assertEquals(ActionType.ESCALATE_HUMAN, decision.action());
        assertTrue(decision.gateReason().contains("below the 0.70 floor"));
    }

    @Test
    void theAttemptCeilingIsEnforced() {
        FailedTransaction.AgentView txn = recentTxn();
        for (int i = 0; i < PolicyConfig.defaults().maxAttemptsPerTxn(); i++) {
            RecoveryDecision decision = policy.decide(txn, confident(FailureReason.NETWORK_TIMEOUT), ledger);
            assertTrue(PolicyConfig.movesMoney(decision.action()), "attempt " + (i + 1) + " should move money");
            ledger.recordDecision(decision);
            settle(decision);
        }

        RecoveryDecision fourth = policy.decide(txn, confident(FailureReason.NETWORK_TIMEOUT), ledger);
        assertEquals(ActionType.DO_NOTHING, fourth.action());
        assertTrue(fourth.gateReason().contains("ceiling reached"));
    }

    @Test
    void onlyOneAttemptMayBeInFlightAtATime() {
        FailedTransaction.AgentView txn = recentTxn();
        RecoveryDecision first = policy.decide(txn, confident(FailureReason.NETWORK_TIMEOUT), ledger);
        ledger.recordDecision(first); // decided, but no outcome written yet

        RecoveryDecision second = policy.decide(txn, confident(FailureReason.NETWORK_TIMEOUT), ledger);
        assertEquals(ActionType.DO_NOTHING, second.action());
        assertTrue(second.gateReason().contains("already in flight"));
    }

    @Test
    void anOldFailureIsOutsideTheWindow() {
        FailedTransaction.AgentView stale = txn(NOW.minus(Duration.ofDays(9)), true, 1);
        RecoveryDecision decision = policy.decide(stale, confident(FailureReason.NETWORK_TIMEOUT), ledger);

        assertEquals(ActionType.DO_NOTHING, decision.action());
        assertTrue(decision.gateReason().contains("older than 7 days"));
    }

    /**
     * Insufficient funds recovers when the salary lands, which can be most of a month
     * out. Under a flat 7-day cutoff the engine refused to attempt the largest and most
     * recoverable bucket at all - the stopping rule was cancelling the only mechanism
     * that works on it. Hence the separate salary window.
     */
    @Test
    void aPaydayNearlyAMonthOutIsStillScheduled() {
        // Failure on the 20th, payday on the 15th of next month: 26 days out.
        FailedTransaction.AgentView txn = txn(NOW.minus(Duration.ofHours(1)), true, 15);
        RecoveryDecision decision = policy.decide(txn, confident(FailureReason.INSUFFICIENT_FUNDS), ledger);

        assertEquals(ActionType.RETRY_SAME_METHOD, decision.action());
        assertEquals("2026-09-15T05:00:00Z", decision.scheduledFor().toString());
    }

    /** The salary window is longer, not absent. Past it, the engine still stops. */
    @Test
    void aPaydayBeyondTheSalaryWindowMeansNoAttempt() {
        PolicyConfig tight = new PolicyConfig(3, 7, 7, 0.70, 25L, 0L);
        DefaultRecoveryPolicy strict = new DefaultRecoveryPolicy(tight, CLOCK);
        FailedTransaction.AgentView txn = txn(NOW.minus(Duration.ofHours(1)), true, 15);

        RecoveryDecision decision = strict.decide(txn, confident(FailureReason.INSUFFICIENT_FUNDS), ledger);
        assertEquals(ActionType.DO_NOTHING, decision.action());
        assertTrue(decision.gateReason().contains("outside the 7-day window"));
    }

    @Test
    void onlyInsufficientFundsGetsTheLongerWindow() {
        PolicyConfig config = PolicyConfig.defaults();
        assertEquals(35, config.maxAgeDaysFor(FailureReason.INSUFFICIENT_FUNDS));
        assertEquals(7, config.maxAgeDaysFor(FailureReason.NETWORK_TIMEOUT));
        assertEquals(7, config.maxAgeDaysFor(FailureReason.AUTHENTICATION_FAILED));
    }

    // ---------- action selection ----------

    @Test
    void insufficientFundsWaitsForPayday() {
        FailedTransaction.AgentView txn = txn(NOW.minus(Duration.ofHours(2)), true, 22);
        RecoveryDecision decision = policy.decide(txn, confident(FailureReason.INSUFFICIENT_FUNDS), ledger);

        assertEquals(ActionType.RETRY_SAME_METHOD, decision.action());
        assertEquals("2026-08-22T05:00:00Z", decision.scheduledFor().toString()); // 10:30 IST
        assertTrue(decision.gateReason().contains("salary credit"));
    }

    @Test
    void anAbandonedAuthenticationGetsALinkNotASilentRetry() {
        RecoveryDecision decision = policy.decide(recentTxn(),
                confident(FailureReason.AUTHENTICATION_FAILED), ledger);

        assertEquals(ActionType.SEND_PAYMENT_LINK, decision.action());
        assertEquals(25L, decision.costPaise());
    }

    @Test
    void anExpiredCardIsNeverRetriedOnTheSameRail() {
        RecoveryDecision decision = policy.decide(recentTxn(), confident(FailureReason.EXPIRED_CARD), ledger);
        assertEquals(ActionType.SEND_PAYMENT_LINK, decision.action());
    }

    @Test
    void anInvalidVpaSwitchesToTheSavedCardWhenThereIsOne() {
        RecoveryDecision withCard = policy.decide(txn(NOW.minus(Duration.ofHours(1)), true, 1),
                confident(FailureReason.INVALID_VPA), ledger);
        assertEquals(ActionType.RETRY_ALTERNATE_METHOD, withCard.action());
        assertEquals(PaymentMethod.CARD, withCard.method());
    }

    @Test
    void anInvalidVpaWithNoCardOnFileSendsALink() {
        RecoveryDecision noCard = policy.decide(txn(NOW.minus(Duration.ofHours(1)), false, 1),
                confident(FailureReason.INVALID_VPA), ledger);
        assertEquals(ActionType.SEND_PAYMENT_LINK, noCard.action());
        assertNull(noCard.method());
    }

    @Test
    void issuerOutagesBackOffFurtherEachAttempt() {
        FailedTransaction.AgentView txn = recentTxn();
        RecoveryDecision first = policy.decide(txn, confident(FailureReason.ISSUER_DOWN), ledger);
        ledger.recordDecision(first);
        settle(first);
        RecoveryDecision second = policy.decide(txn, confident(FailureReason.ISSUER_DOWN), ledger);

        assertTrue(second.scheduledFor().isAfter(first.scheduledFor()),
                "the second attempt must wait longer than the first");
    }

    @Test
    void aScheduledAttemptIsNeverInThePast() {
        // A timeout six hours old: the natural +30min slot has already gone by.
        FailedTransaction.AgentView txn = txn(NOW.minus(Duration.ofHours(6)), true, 1);
        RecoveryDecision decision = policy.decide(txn, confident(FailureReason.NETWORK_TIMEOUT), ledger);

        assertTrue(!decision.scheduledFor().isBefore(NOW));
    }

    // ---------- idempotency ----------

    @Test
    void theIdempotencyKeyIsDerivedAndStable() {
        RecoveryDecision decision = policy.decide(recentTxn(), confident(FailureReason.NETWORK_TIMEOUT), ledger);
        assertNotNull(decision.idempotencyKey());
        assertEquals(decision.idempotencyKey(), decision.idempotencyKey());
        assertEquals(32, decision.idempotencyKey().length());
    }

    @Test
    void differentAttemptsOfTheSameTransactionGetDifferentKeys() {
        FailedTransaction.AgentView txn = recentTxn();
        RecoveryDecision first = policy.decide(txn, confident(FailureReason.NETWORK_TIMEOUT), ledger);
        ledger.recordDecision(first);
        settle(first);
        RecoveryDecision second = policy.decide(txn, confident(FailureReason.NETWORK_TIMEOUT), ledger);

        assertTrue(!first.idempotencyKey().equals(second.idempotencyKey()));
    }

    @Test
    void aStoppedDecisionCarriesNoIdempotencyKey() {
        RecoveryDecision decision = policy.decide(recentTxn(), confident(FailureReason.RISK_BLOCKED), ledger);
        assertNull(decision.idempotencyKey());
    }

    private void settle(RecoveryDecision decision) {
        ledger.recordOutcome(AttemptResult.failure(decision.txnId(), decision.idempotencyKey(),
                decision.costPaise(), NOW, "settled in test"));
    }
}
