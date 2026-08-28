package com.recoverx.eval;

import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.RecoveryTruth;
import com.recoverx.ledger.Ledger;
import com.recoverx.policy.ActionType;
import com.recoverx.policy.RecoveryDecision;

import java.time.Instant;
import java.util.Map;

/**
 * Perfect knowledge. Attempts each recoverable decline at exactly the moment recovery
 * becomes possible, on exactly the rail that works, exactly as many times as it takes,
 * and never touches an instrument that must not be re-presented.
 *
 * <p>This is not a strategy anyone could run - it reads the answer. It exists to be the
 * denominator. A recovery figure quoted against total failed value is inflated by money
 * nobody could have collected; quoted against the oracle it means something.
 *
 * <p>It doubles as a self-check on the harness. The oracle should recover 100% of the
 * ceiling. Anything less means this class and {@link SimulatedExecutor} disagree about
 * the resolution rules, and every other arm's number is suspect until they agree.
 */
public class OracleDecisionSource implements DecisionSource {

    private final Map<String, FailedTransaction.GroundTruth> truth;

    public OracleDecisionSource(Map<String, FailedTransaction.GroundTruth> truth) {
        this.truth = truth;
    }

    @Override
    public RecoveryDecision decide(FailedTransaction.AgentView txn, Instant now, Ledger ledger) {
        FailedTransaction.GroundTruth groundTruth = truth.get(txn.txnId());
        int attemptNumber = ledger.attemptCount(txn.txnId()) + 1;

        if (groundTruth == null) {
            return stop(txn, attemptNumber, "no truth row");
        }
        RecoveryTruth recovery = groundTruth.truth();

        if (recovery.mustNotRetry()) {
            return stop(txn, attemptNumber, "must never be re-presented");
        }
        if (!recovery.recoverable()) {
            return stop(txn, attemptNumber, "not recoverable by anyone");
        }
        if (attemptNumber > recovery.attemptsNeeded()) {
            return stop(txn, attemptNumber, "already recovered");
        }

        boolean needsAnotherRail = recovery.requiredMethod() != null
                && recovery.requiredMethod() != txn.method();

        return new RecoveryDecision(
                txn.txnId(),
                needsAnotherRail ? ActionType.RETRY_ALTERNATE_METHOD : ActionType.RETRY_SAME_METHOD,
                txn.amountPaise(),
                attemptNumber,
                recovery.recoverableFrom(),
                needsAnotherRail ? recovery.requiredMethod() : null,
                0L,
                groundTruth.trueReason(),
                1.0,
                "oracle: attempt " + attemptNumber + " of " + recovery.attemptsNeeded()
                        + " at the first instant recovery is possible",
                "perfect knowledge; this is the ceiling, not a strategy");
    }

    private RecoveryDecision stop(FailedTransaction.AgentView txn, int attemptNumber, String why) {
        return new RecoveryDecision(txn.txnId(), ActionType.DO_NOTHING, txn.amountPaise(),
                attemptNumber, null, null, 0L, null, 0.0, why, why);
    }

    @Override
    public String name() {
        return "oracle";
    }
}
