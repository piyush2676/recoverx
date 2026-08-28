package com.recoverx.policy;

import com.recoverx.classify.Classification;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.ledger.Ledger;

/**
 * Deterministic decision layer. Owns the stopping rules the track asks for:
 *
 *  - at most 3 attempts per transaction
 *  - no attempt more than 7 days after the original failure
 *  - hard stop on a risk-blocked decline, whatever the classifier says
 *  - hard stop below the classifier confidence floor
 *  - never schedule two live attempts for the same txn at once
 *
 * Keeping these in code rather than in a prompt is the whole point. A prompt can
 * be talked out of a stopping rule; an if-statement cannot.
 */
public interface RecoveryPolicy {

    RecoveryDecision decide(FailedTransaction.AgentView txn, Classification classification, Ledger ledger);
}
