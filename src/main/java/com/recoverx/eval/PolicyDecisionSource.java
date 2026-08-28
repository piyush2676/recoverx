package com.recoverx.eval;

import com.recoverx.classify.Classification;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.ledger.Ledger;
import com.recoverx.policy.DefaultRecoveryPolicy;
import com.recoverx.policy.PolicyConfig;
import com.recoverx.policy.RecoveryDecision;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * RecoverX itself: a classifier's verdict fed through the deterministic policy engine.
 *
 * <p>The classifier is fixed before the run starts, so the same arm can be measured
 * with the rule baseline or with the model and nothing else differs between them.
 */
public class PolicyDecisionSource implements DecisionSource {

    private final PolicyConfig config;
    private final Map<String, Classification> verdicts;
    private final String label;

    public PolicyDecisionSource(PolicyConfig config,
                                Map<String, Classification> verdicts,
                                String label) {
        this.config = config;
        this.verdicts = verdicts;
        this.label = label;
    }

    @Override
    public RecoveryDecision decide(FailedTransaction.AgentView txn, Instant now, Ledger ledger) {
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        return new DefaultRecoveryPolicy(config, clock).decide(txn, verdicts.get(txn.txnId()), ledger);
    }

    @Override
    public String name() {
        return label;
    }
}
