package com.recoverx.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

import com.recoverx.domain.FailureReason;
import com.recoverx.domain.PaymentMethod;

/**
 * A bounded, gated instruction produced by deterministic code.
 *
 * <p>The model wrote {@code justification}. Everything that decides whether money
 * moves - the action, the timing, the rail, the gate - was computed in plain Java from
 * the classifier's verdict. No model output reaches the executor unmediated.
 *
 * @param amountPaise     the order value to collect. Carried on the decision so the
 *                        executor never has to look the transaction back up.
 * @param attemptNumber   1-based; the ledger's count of prior money-moving attempts
 *                        plus one. Feeds the idempotency key.
 * @param scheduledFor    when to execute. Never earlier than the decision instant, and
 *                        never later than the configured cutoff. Null when no attempt
 *                        will be made.
 * @param method          null means keep the original rail
 * @param costPaise       what attempting this costs. Recovered value is reported net of it.
 * @param gateReason      why the policy allowed or blocked this, in the audit trail
 */
public record RecoveryDecision(
        String txnId,
        ActionType action,
        long amountPaise,
        int attemptNumber,
        Instant scheduledFor,
        PaymentMethod method,
        long costPaise,
        FailureReason classifiedReason,
        double confidence,
        String justification,
        String gateReason
) {

    /**
     * Derived, never random. A crash mid-batch replays the same key, so a resume
     * cannot double-charge. A random key would silently lose that property.
     */
    public String idempotencyKey() {
        if (!PolicyConfig.movesMoney(action)) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((txnId + ":" + attemptNumber).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }
}
