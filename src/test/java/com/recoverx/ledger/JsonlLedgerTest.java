package com.recoverx.ledger;

import com.recoverx.domain.FailureReason;
import com.recoverx.executor.AttemptResult;
import com.recoverx.policy.ActionType;
import com.recoverx.policy.RecoveryDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonlLedgerTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static RecoveryDecision decision(String txnId, ActionType action, int attempt) {
        return new RecoveryDecision(txnId, action, 250000L, attempt,
                action == ActionType.DO_NOTHING ? null : NOW.plusSeconds(3600),
                null, 25L, FailureReason.NETWORK_TIMEOUT, 0.9,
                "model justification", "gate reason");
    }

    @Test
    void everyRowIsWrittenToDiskAsItIsAppended(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("nested/ledger.jsonl");
        Ledger ledger = new JsonlLedger(file, CLOCK);

        ledger.recordDecision(decision("pay_1", ActionType.RETRY_SAME_METHOD, 1));
        ledger.recordDecision(decision("pay_2", ActionType.SEND_PAYMENT_LINK, 1));

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("pay_1"));
        assertTrue(lines.get(1).contains("pay_2"));
    }

    @Test
    void sequenceNumbersAreContiguousAndOrdered(@TempDir Path tmp) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        ledger.recordDecision(decision("pay_1", ActionType.RETRY_SAME_METHOD, 1));
        ledger.recordDecision(decision("pay_2", ActionType.RETRY_SAME_METHOD, 1));
        ledger.recordDecision(decision("pay_3", ActionType.DO_NOTHING, 1));

        List<LedgerEntry> all = ledger.all();
        assertEquals(List.of(1L, 2L, 3L), all.stream().map(LedgerEntry::sequence).toList());
    }

    /** Escalations and stops are recorded, but they are not attempts against the ceiling. */
    @Test
    void onlyMoneyMovingActionsCountAgainstTheCeiling(@TempDir Path tmp) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        ledger.recordDecision(decision("pay_1", ActionType.ESCALATE_HUMAN, 1));
        ledger.recordDecision(decision("pay_1", ActionType.DO_NOTHING, 1));
        assertEquals(0, ledger.attemptCount("pay_1"));

        ledger.recordDecision(decision("pay_1", ActionType.RETRY_SAME_METHOD, 1));
        ledger.recordDecision(decision("pay_1", ActionType.SEND_PAYMENT_LINK, 2));
        assertEquals(2, ledger.attemptCount("pay_1"));
    }

    @Test
    void anAttemptIsPendingUntilItsOutcomeIsWritten(@TempDir Path tmp) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        RecoveryDecision attempt = decision("pay_1", ActionType.RETRY_SAME_METHOD, 1);

        ledger.recordDecision(attempt);
        assertTrue(ledger.hasPendingAttempt("pay_1"));

        ledger.recordOutcome(AttemptResult.success("pay_1", attempt.idempotencyKey(),
                250000L, 0L, NOW, "rzp_ref"));
        assertFalse(ledger.hasPendingAttempt("pay_1"));
    }

    /**
     * An outcome for a different attempt must not settle the pending one. Matching on
     * txnId alone would let a stale outcome unlock a second live charge.
     */
    @Test
    void anOutcomeForAnotherAttemptDoesNotSettleThisOne(@TempDir Path tmp) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        RecoveryDecision first = decision("pay_1", ActionType.RETRY_SAME_METHOD, 1);
        RecoveryDecision second = decision("pay_1", ActionType.RETRY_SAME_METHOD, 2);

        ledger.recordDecision(second);
        ledger.recordOutcome(AttemptResult.failure("pay_1", first.idempotencyKey(),
                0L, NOW, "unrelated attempt"));

        assertTrue(ledger.hasPendingAttempt("pay_1"),
                "attempt 2 is still in flight; an outcome for attempt 1 must not clear it");
    }

    @Test
    void aStoppedDecisionIsNeverPending(@TempDir Path tmp) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        ledger.recordDecision(decision("pay_1", ActionType.DO_NOTHING, 1));
        assertFalse(ledger.hasPendingAttempt("pay_1"));
    }

    @Test
    void rowsAreRetrievableByTransaction(@TempDir Path tmp) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        ledger.recordDecision(decision("pay_1", ActionType.RETRY_SAME_METHOD, 1));
        ledger.recordDecision(decision("pay_2", ActionType.RETRY_SAME_METHOD, 1));
        ledger.recordDecision(decision("pay_1", ActionType.DO_NOTHING, 2));

        assertEquals(2, ledger.forTxn("pay_1").size());
        assertEquals(1, ledger.forTxn("pay_2").size());
        assertEquals(0, ledger.forTxn("pay_missing").size());
    }

    @Test
    void everyDecisionRowCarriesItsJustificationAndGateReason(@TempDir Path tmp) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        LedgerEntry entry = ledger.recordDecision(decision("pay_1", ActionType.RETRY_SAME_METHOD, 1));

        assertEquals("model justification", entry.justification());
        assertEquals("gate reason", entry.gateReason());
        assertEquals(FailureReason.NETWORK_TIMEOUT, entry.classifiedReason());
        assertEquals(LedgerEntry.EntryType.DECISION, entry.type());
    }
}
