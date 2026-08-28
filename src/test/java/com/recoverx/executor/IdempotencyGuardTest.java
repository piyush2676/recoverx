package com.recoverx.executor;

import com.recoverx.domain.FailureReason;
import com.recoverx.ledger.JsonlLedger;
import com.recoverx.ledger.Ledger;
import com.recoverx.ledger.LedgerEntry;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The guard is the innermost of three layers that prevent a double charge, and in a
 * normal crash-and-resume it never fires, because the policy's in-flight gate stops
 * re-presentation first. That is defence in depth working correctly - and it is also
 * why the guard needs direct tests. An untested layer that is never reached in practice
 * is a layer nobody knows is broken.
 */
class IdempotencyGuardTest {

    private static final Instant NOW = Instant.parse("2026-08-20T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static RecoveryDecision decision(String txnId, int attempt) {
        return new RecoveryDecision(txnId, ActionType.RETRY_SAME_METHOD, 250000L, attempt,
                NOW.plusSeconds(3600), null, 0L, FailureReason.NETWORK_TIMEOUT, 0.9,
                "justification", "gate");
    }

    @Test
    void withNoPriorOutcomeTheGuardStandsAside(@TempDir Path tmp) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        IdempotencyGuard guard = new IdempotencyGuard(ledger);

        assertTrue(guard.previousOutcome(decision("pay_1", 1)).isEmpty());
    }

    @Test
    void anAlreadyRecordedOutcomeIsReturnedInsteadOfACall(@TempDir Path tmp) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        IdempotencyGuard guard = new IdempotencyGuard(ledger);
        RecoveryDecision attempt = decision("pay_1", 1);

        ledger.recordDecision(attempt);
        ledger.recordOutcome(AttemptResult.success("pay_1", attempt.idempotencyKey(),
                250000L, 0L, NOW, "rzp_ref_1"));

        Optional<AttemptResult> replayed = guard.previousOutcome(attempt);
        assertTrue(replayed.isPresent());
        assertTrue(replayed.get().succeeded());
        assertEquals(250000L, replayed.get().recoveredPaise());
        assertTrue(replayed.get().replayed(), "a replay must be visible in the trail, not silent");
        assertEquals(0L, replayed.get().costPaise(), "a replay costs nothing; no call was made");
    }

    /** The key is per attempt, so attempt 2 is a genuinely new action, not a repeat. */
    @Test
    void aLaterAttemptOfTheSameTransactionIsNotBlocked(@TempDir Path tmp) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        IdempotencyGuard guard = new IdempotencyGuard(ledger);
        RecoveryDecision first = decision("pay_1", 1);

        ledger.recordDecision(first);
        ledger.recordOutcome(AttemptResult.failure("pay_1", first.idempotencyKey(), 0L, NOW, "declined"));

        assertTrue(guard.previousOutcome(decision("pay_1", 2)).isEmpty());
    }

    @Test
    void aDecisionThatMovesNoMoneyHasNothingToGuard(@TempDir Path tmp) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        IdempotencyGuard guard = new IdempotencyGuard(ledger);
        RecoveryDecision stopped = new RecoveryDecision("pay_1", ActionType.DO_NOTHING, 250000L, 1,
                null, null, 0L, FailureReason.RISK_BLOCKED, 0.9, "j", "hard stop");

        assertTrue(guard.previousOutcome(stopped).isEmpty());
    }

    /**
     * The whole guard rests on the ledger surviving a restart. If a fresh process starts
     * with an empty index it recomputes the same keys, finds nothing, and re-presents
     * every attempt - which is the double charge this design exists to prevent.
     */
    @Test
    void theGuardStillWorksAfterTheProcessRestarts(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("ledger.jsonl");
        RecoveryDecision attempt = decision("pay_1", 1);

        Ledger first = new JsonlLedger(file, CLOCK);
        first.recordDecision(attempt);
        first.recordOutcome(AttemptResult.success("pay_1", attempt.idempotencyKey(),
                250000L, 0L, NOW, "rzp_ref_1"));

        // a new process, same file
        Ledger restarted = new JsonlLedger(file, CLOCK);
        assertEquals(2, restarted.all().size(), "the ledger must be rebuilt from disk");
        assertTrue(new IdempotencyGuard(restarted).previousOutcome(attempt).isPresent(),
                "a restarted process must recognise an attempt it already made");
    }

    @Test
    void aTruncatedFinalRowIsDroppedRatherThanFatal(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("ledger.jsonl");
        Ledger ledger = new JsonlLedger(file, CLOCK);
        ledger.recordDecision(decision("pay_1", 1));

        // the shape a process killed mid-write leaves behind
        Files.writeString(file, Files.readString(file) + "{\"sequence\":2,\"type\":\"OUT");

        Ledger restarted = new JsonlLedger(file, CLOCK);
        assertEquals(1, restarted.all().size());
    }

    @Test
    void anAttemptWithNoOutcomeIsReportedAsPending(@TempDir Path tmp) throws IOException {
        Ledger ledger = new JsonlLedger(tmp.resolve("ledger.jsonl"), CLOCK);
        RecoveryDecision settled = decision("pay_1", 1);
        RecoveryDecision orphan = decision("pay_2", 1);

        ledger.recordDecision(settled);
        ledger.recordOutcome(AttemptResult.failure("pay_1", settled.idempotencyKey(), 0L, NOW, "declined"));
        ledger.recordDecision(orphan);
        ledger.recordDecision(new RecoveryDecision("pay_3", ActionType.ESCALATE_HUMAN, 1000L, 1,
                null, null, 0L, FailureReason.NETWORK_TIMEOUT, 0.9, "j", "g"));

        List<LedgerEntry> pending = ledger.pendingAttempts();
        assertEquals(1, pending.size(), "only the orphaned money-moving attempt is pending");
        assertEquals("pay_2", pending.get(0).txnId());
        assertFalse(pending.stream().anyMatch(e -> e.txnId().equals("pay_3")),
                "an escalation moves no money and is never pending");
    }
}
