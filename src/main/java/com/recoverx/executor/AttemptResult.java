package com.recoverx.executor;

import java.time.Instant;

/**
 * What happened when one decision was carried out.
 *
 * @param idempotencyKey     derived from (txnId, attemptNumber); the join key back to
 *                           the decision row
 * @param replayed           true when this result came from the ledger rather than
 *                           from a fresh call, because an outcome for this key already
 *                           existed. This is the double-charge guard firing, and it is
 *                           recorded rather than hidden.
 * @param complianceViolation true when the attempt touched an instrument that must
 *                           never be re-presented. In production this is the gateway's
 *                           own risk response; in the simulation the harness knows it
 *                           outright. Either way it is reported on its own line and
 *                           never folded into a success rate.
 */
public record AttemptResult(
        String txnId,
        String idempotencyKey,
        boolean succeeded,
        long recoveredPaise,
        long costPaise,
        Instant attemptedAt,
        String providerReference,
        String failureNote,
        boolean replayed,
        boolean complianceViolation
) {

    public static AttemptResult success(String txnId, String key, long recoveredPaise,
                                        long costPaise, Instant at, String providerReference) {
        return new AttemptResult(txnId, key, true, recoveredPaise, costPaise, at,
                providerReference, null, false, false);
    }

    public static AttemptResult failure(String txnId, String key, long costPaise,
                                        Instant at, String note) {
        return new AttemptResult(txnId, key, false, 0L, costPaise, at, null, note, false, false);
    }

    /** No call was made: the ledger already holds an outcome for this key. */
    public AttemptResult asReplay() {
        return new AttemptResult(txnId, idempotencyKey, succeeded, recoveredPaise, 0L,
                attemptedAt, providerReference,
                "replayed from the ledger; no second call was made", true, complianceViolation);
    }

    public AttemptResult asComplianceViolation(String note) {
        return new AttemptResult(txnId, idempotencyKey, false, 0L, costPaise, attemptedAt,
                providerReference, note, replayed, true);
    }
}
