package com.recoverx.executor;

import com.recoverx.policy.RecoveryDecision;

/**
 * Carries out a decision against Razorpay test mode.
 *
 * Every call must pass an idempotency key derived from (txnId, attemptNumber) so a
 * crash mid-batch cannot double-charge on resume. That crash is one of the two
 * failures to demo on video.
 */
public interface RecoveryExecutor {

    AttemptResult execute(RecoveryDecision decision);

    String name();
}
