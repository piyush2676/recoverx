package com.recoverx.domain;

import java.time.Instant;

/**
 * Hidden simulation truth: whether this transaction is actually recoverable,
 * and under what conditions. Used only by the eval harness to compute the
 * oracle ceiling and to score the agent's decisions. Never exposed to the agent.
 *
 * @param recoverable    false means every retry attempt will fail
 * @param recoverableFrom earliest instant a retry can succeed; null when not recoverable
 * @param requiredMethod  method the retry must use to succeed; null means any method works
 * @param attemptsNeeded  number of attempts (at or after recoverableFrom, on a valid method)
 *                        before the payment goes through
 * @param mustNotRetry    hard gate: retrying this is a compliance violation, not just a waste
 */
public record RecoveryTruth(
        boolean recoverable,
        Instant recoverableFrom,
        PaymentMethod requiredMethod,
        int attemptsNeeded,
        boolean mustNotRetry
) {
    public static RecoveryTruth unrecoverable(boolean mustNotRetry) {
        return new RecoveryTruth(false, null, null, 0, mustNotRetry);
    }
}
