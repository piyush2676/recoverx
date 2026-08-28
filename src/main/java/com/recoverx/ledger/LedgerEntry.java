package com.recoverx.ledger;

import java.time.Instant;

import com.recoverx.domain.FailureReason;
import com.recoverx.domain.PaymentMethod;
import com.recoverx.policy.ActionType;

/**
 * One immutable row. Append-only: a correction is a new row, never an update.
 *
 * <p>Two row types share the table. A {@code DECISION} row records what the policy
 * concluded and why; an {@code OUTCOME} row records what actually happened when the
 * executor carried it out. They are linked by {@code txnId} and {@code idempotencyKey}.
 *
 * <p>This table is the audit trail, and it is what goes on screen in the pitch video.
 */
public record LedgerEntry(
        long sequence,
        Instant recordedAt,
        EntryType type,
        String txnId,
        FailureReason classifiedReason,
        double confidence,
        ActionType action,
        int attemptNumber,
        Instant scheduledFor,
        PaymentMethod method,
        String justification,
        String gateReason,
        String idempotencyKey,
        Boolean succeeded,
        long recoveredPaise,
        long costPaise,
        boolean replayed,
        boolean complianceViolation
) {
    public enum EntryType {
        DECISION,
        OUTCOME
    }
}
