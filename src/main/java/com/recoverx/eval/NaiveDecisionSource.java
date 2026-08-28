package com.recoverx.eval;

import com.recoverx.domain.FailedTransaction;
import com.recoverx.ledger.Ledger;
import com.recoverx.policy.ActionType;
import com.recoverx.policy.RecoveryDecision;

import java.time.Instant;

/**
 * The baseline every merchant already has: retry everything once, immediately, on the
 * same rail. No classification, no timing, no gates.
 *
 * <p>It is not a strawman - it is what a payments team ships in an afternoon, and it
 * does recover real money on timeouts and outages. What it cannot do is wait for a
 * payday, switch off a dead card, or leave a risk-blocked instrument alone. The
 * comparison exists to price those three things.
 *
 * <p>Two variants are measured, because "naive" is not one thing. Retrying the instant
 * a payment fails is what a quick integration does, and it catches almost nothing -
 * most transient declines need minutes to clear. Retrying an hour later is what a
 * merchant's retry cron actually does, and it is a far stronger opponent: it sweeps up
 * timeouts and a good share of issuer outages without knowing anything.
 *
 * <p>Reporting only the immediate variant would have flattered RecoverX considerably.
 *
 * <p>Note what is deliberately absent: this arm re-presents risk-blocked declines,
 * because a merchant with no classifier has no way to know which ones they are. The
 * compliance violations it racks up are the honest cost of not having the gates, and
 * folding them out of the baseline would be quietly rigging the comparison.
 */
public class NaiveDecisionSource implements DecisionSource {

    private final java.time.Duration delay;
    private final String label;

    public NaiveDecisionSource(java.time.Duration delay, String label) {
        this.delay = delay;
        this.label = label;
    }

    @Override
    public RecoveryDecision decide(FailedTransaction.AgentView txn, Instant now, Ledger ledger) {
        int attemptNumber = ledger.attemptCount(txn.txnId()) + 1;
        if (attemptNumber > 1) {
            return stop(txn, attemptNumber, "naive baseline retries once and gives up");
        }
        return new RecoveryDecision(
                txn.txnId(),
                ActionType.RETRY_SAME_METHOD,
                txn.amountPaise(),
                1,
                txn.failedAt().plus(delay),
                null,
                0L,
                null,
                0.0,
                "naive baseline: re-present on the original rail after " + delay.toMinutes() + " minutes",
                "no gates; every failed payment is retried once");
    }

    private RecoveryDecision stop(FailedTransaction.AgentView txn, int attemptNumber, String why) {
        return new RecoveryDecision(txn.txnId(), ActionType.DO_NOTHING, txn.amountPaise(),
                attemptNumber, null, null, 0L, null, 0.0, why, why);
    }

    @Override
    public String name() {
        return label;
    }
}
