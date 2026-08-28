package com.recoverx.eval;

import com.recoverx.domain.CustomerProfile;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.FailureReason;
import com.recoverx.domain.PaymentMethod;
import com.recoverx.domain.RecoveryTruth;
import com.recoverx.executor.AttemptResult;
import com.recoverx.executor.IdempotencyGuard;
import com.recoverx.ledger.JsonlLedger;
import com.recoverx.ledger.Ledger;
import com.recoverx.policy.ActionType;
import com.recoverx.policy.RecoveryDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The simulator decides every rupee in the final report, so its resolution rules get
 * the same scrutiny as the policy gates. A generous bug here would inflate the headline
 * number and nothing else would catch it.
 */
class SimulatedExecutorTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long AMOUNT = 250000L;

    private static FailedTransaction.AgentView txn(String id, PaymentMethod method) {
        return new FailedTransaction.AgentView(id, "order_" + id, "acc_TEST", AMOUNT, "INR",
                method, NOW.minus(Duration.ofHours(2)), "U30", "insufficient balance", "HDFC",
                new CustomerProfile("cust_1", 1, 0.8, PaymentMethod.UPI, 1, true), 1);
    }

    private static FailedTransaction.GroundTruth truth(String id, RecoveryTruth recovery) {
        return new FailedTransaction.GroundTruth(id, FailureReason.INSUFFICIENT_FUNDS, recovery, false);
    }

    private static RecoveryDecision decision(String id, ActionType action, Instant at,
                                             PaymentMethod method, int attempt) {
        return new RecoveryDecision(id, action, AMOUNT, attempt, at, method, 25L,
                FailureReason.INSUFFICIENT_FUNDS, 0.9, "justification", "gate");
    }

    private record Fixture(SimulatedExecutor executor, Ledger ledger) {
    }

    private static Fixture fixture(Path tmp, String id, RecoveryTruth recovery,
                                   PaymentMethod method) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        SimulatedExecutor executor = new SimulatedExecutor(
                List.of(txn(id, method)), Map.of(id, truth(id, recovery)),
                new IdempotencyGuard(ledger), tmp.resolve("keys.txt"));
        return new Fixture(executor, ledger);
    }

    @Test
    void anAttemptInsideTheWindowOnAnyRailSucceeds(@TempDir Path tmp) throws IOException {
        RecoveryTruth recovery = new RecoveryTruth(true, NOW, null, 1, false);
        Fixture f = fixture(tmp, "pay_1", recovery, PaymentMethod.UPI);

        AttemptResult result = f.executor().execute(
                decision("pay_1", ActionType.RETRY_SAME_METHOD, NOW.plusSeconds(60), null, 1));

        assertTrue(result.succeeded());
        assertEquals(AMOUNT, result.recoveredPaise());
    }

    @Test
    void anAttemptBeforeTheWindowOpensFails(@TempDir Path tmp) throws IOException {
        RecoveryTruth recovery = new RecoveryTruth(true, NOW.plus(Duration.ofDays(3)), null, 1, false);
        Fixture f = fixture(tmp, "pay_1", recovery, PaymentMethod.UPI);

        AttemptResult result = f.executor().execute(
                decision("pay_1", ActionType.RETRY_SAME_METHOD, NOW, null, 1));

        assertFalse(result.succeeded());
        assertTrue(result.failureNote().contains("before recovery was possible"));
    }

    @Test
    void anUnrecoverableDeclineNeverSucceeds(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, "pay_1", RecoveryTruth.unrecoverable(false), PaymentMethod.UPI);

        AttemptResult result = f.executor().execute(
                decision("pay_1", ActionType.RETRY_SAME_METHOD, NOW.plusSeconds(60), null, 1));
        assertFalse(result.succeeded());
    }

    @Test
    void theWrongRailFailsEvenInsideTheWindow(@TempDir Path tmp) throws IOException {
        RecoveryTruth recovery = new RecoveryTruth(true, NOW, PaymentMethod.NETBANKING, 1, false);
        Fixture f = fixture(tmp, "pay_1", recovery, PaymentMethod.CARD);

        AttemptResult result = f.executor().execute(
                decision("pay_1", ActionType.RETRY_SAME_METHOD, NOW.plusSeconds(60), null, 1));

        assertFalse(result.succeeded());
        assertTrue(result.failureNote().contains("wrong rail"));
    }

    /** The documented modelling choice: a link lets the customer pick a rail that works. */
    @Test
    void aPaymentLinkSatisfiesARailRequirement(@TempDir Path tmp) throws IOException {
        RecoveryTruth recovery = new RecoveryTruth(true, NOW, PaymentMethod.NETBANKING, 1, false);
        Fixture f = fixture(tmp, "pay_1", recovery, PaymentMethod.CARD);

        AttemptResult result = f.executor().execute(
                decision("pay_1", ActionType.SEND_PAYMENT_LINK, NOW.plusSeconds(60), null, 1));
        assertTrue(result.succeeded());
    }

    @Test
    void aDeclineThatNeedsTwoAttemptsDoesNotSucceedOnTheFirst(@TempDir Path tmp) throws IOException {
        RecoveryTruth recovery = new RecoveryTruth(true, NOW, null, 2, false);
        Fixture f = fixture(tmp, "pay_1", recovery, PaymentMethod.UPI);

        AttemptResult first = f.executor().execute(
                decision("pay_1", ActionType.RETRY_SAME_METHOD, NOW.plusSeconds(60), null, 1));
        assertFalse(first.succeeded());

        AttemptResult second = f.executor().execute(
                decision("pay_1", ActionType.RETRY_SAME_METHOD, NOW.plusSeconds(120), null, 2));
        assertTrue(second.succeeded());
    }

    @Test
    void touchingARiskBlockedInstrumentIsRecordedAsAComplianceViolation(@TempDir Path tmp) throws IOException {
        Fixture f = fixture(tmp, "pay_1", RecoveryTruth.unrecoverable(true), PaymentMethod.CARD);

        AttemptResult result = f.executor().execute(
                decision("pay_1", ActionType.RETRY_SAME_METHOD, NOW.plusSeconds(60), null, 1));

        assertFalse(result.succeeded());
        assertTrue(result.complianceViolation());
        assertEquals(1, f.executor().complianceViolations());
    }

    /**
     * The middle layer: the gateway's own duplicate check. It matters only when a
     * process called out and died before recording the outcome, so the ledger cannot
     * know the attempt happened. The key is written before the attempt resolves, which
     * is what makes it survive that crash.
     */
    @Test
    void thegatewayRejectsAKeyItHasAlreadySeen(@TempDir Path tmp) throws IOException {
        RecoveryTruth recovery = new RecoveryTruth(true, NOW, null, 1, false);
        Fixture f = fixture(tmp, "pay_1", recovery, PaymentMethod.UPI);
        RecoveryDecision attempt = decision("pay_1", ActionType.RETRY_SAME_METHOD,
                NOW.plusSeconds(60), null, 1);

        assertTrue(f.executor().execute(attempt).succeeded());

        // same attempt again, with an empty ledger so the local guard cannot help
        AttemptResult repeat = f.executor().execute(attempt);
        assertFalse(repeat.succeeded());
        assertTrue(repeat.failureNote().contains("duplicate reference_id"));
        assertEquals(1, f.executor().duplicatesRejectedByGateway());
    }

    @Test
    void thePresentedKeysSurviveARestart(@TempDir Path tmp) throws IOException {
        RecoveryTruth recovery = new RecoveryTruth(true, NOW, null, 1, false);
        RecoveryDecision attempt = decision("pay_1", ActionType.RETRY_SAME_METHOD,
                NOW.plusSeconds(60), null, 1);

        fixture(tmp, "pay_1", recovery, PaymentMethod.UPI).executor().execute(attempt);

        // a new process against the same key store, and a ledger that never saw it
        Fixture restarted = fixture(tmp.resolve("fresh"), "pay_1", recovery, PaymentMethod.UPI);
        SimulatedExecutor executor = new SimulatedExecutor(
                List.of(txn("pay_1", PaymentMethod.UPI)),
                Map.of("pay_1", truth("pay_1", recovery)),
                new IdempotencyGuard(restarted.ledger()), tmp.resolve("keys.txt"));

        AttemptResult repeat = executor.execute(attempt);
        assertFalse(repeat.succeeded(), "the gateway must remember the key across a restart");
        assertEquals(1, executor.duplicatesRejectedByGateway());
    }

    @Test
    void reconcilingAnOrphanAnswersItWithoutRePresentingIt(@TempDir Path tmp) throws IOException {
        RecoveryTruth recovery = new RecoveryTruth(true, NOW, null, 1, false);
        Fixture f = fixture(tmp, "pay_1", recovery, PaymentMethod.UPI);
        RecoveryDecision attempt = decision("pay_1", ActionType.RETRY_SAME_METHOD,
                NOW.plusSeconds(60), null, 1);

        var pending = f.ledger().recordDecision(attempt);
        assertTrue(f.ledger().hasPendingAttempt("pay_1"));

        AttemptResult reconciled = f.executor().reconcile(pending);
        f.ledger().recordOutcome(reconciled);

        assertTrue(reconciled.succeeded(), "the attempt had in fact gone through");
        assertEquals(1, f.executor().reconciledCount());
        assertEquals(0, f.executor().duplicatesRejectedByGateway(),
                "reconciliation is a read, not a second presentation");
        assertFalse(f.ledger().hasPendingAttempt("pay_1"), "the transaction is unblocked");
    }
}
