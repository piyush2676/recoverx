package com.recoverx.ledger;

import java.util.List;

import com.recoverx.executor.AttemptResult;
import com.recoverx.policy.RecoveryDecision;

/**
 * Append-only record of every decision and every outcome.
 *
 * <p>The policy engine reads this before deciding: the attempt ceiling and the
 * one-live-attempt-at-a-time rule are enforced by counting rows, not by trusting an
 * in-memory flag that a restart would lose.
 */
public interface Ledger {

    LedgerEntry recordDecision(RecoveryDecision decision);

    LedgerEntry recordOutcome(AttemptResult result);

    List<LedgerEntry> forTxn(String txnId);

    /** Money-moving attempts already decided for this transaction. Escalations do not count. */
    int attemptCount(String txnId);

    /** True when an attempt was decided but no outcome has been written for it yet. */
    boolean hasPendingAttempt(String txnId);

    /**
     * Decision rows that moved money but never got an outcome - the shape a process
     * leaves behind when it dies between calling the gateway and recording the result.
     * Each one permanently blocks its transaction until reconciled.
     */
    List<LedgerEntry> pendingAttempts();

    List<LedgerEntry> all();
}
