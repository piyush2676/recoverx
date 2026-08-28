package com.recoverx.eval;

import com.recoverx.domain.FailedTransaction;
import com.recoverx.ledger.Ledger;
import com.recoverx.policy.RecoveryDecision;

import java.time.Instant;

/**
 * What decides the next move for one transaction.
 *
 * <p>Every arm in the comparison plugs in here and shares the same execution loop,
 * ledger, and simulator. If each arm had its own loop, a difference in the report could
 * be a difference in the loop rather than in the strategy, and the comparison would be
 * measuring the harness.
 */
public interface DecisionSource {

    RecoveryDecision decide(FailedTransaction.AgentView txn, Instant now, Ledger ledger);

    String name();
}
