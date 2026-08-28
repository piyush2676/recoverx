package com.recoverx.domain;

import java.time.Instant;

/**
 * A single failed payment. The agent-facing view is produced by {@link #redacted()},
 * which strips the ground-truth fields.
 */
public record FailedTransaction(
        String txnId,
        String orderId,
        String merchantId,
        long amountPaise,
        String currency,
        PaymentMethod method,
        Instant failedAt,
        String errorCode,
        String rawGatewayError,
        String acquirer,
        CustomerProfile customer,
        int attemptNumber,
        FailureReason trueReason,
        RecoveryTruth truth,
        boolean novelErrorForm
) {
    /** What the agent is allowed to see: no trueReason, no truth. */
    public AgentView redacted() {
        return new AgentView(txnId, orderId, merchantId, amountPaise, currency, method,
                failedAt, errorCode, rawGatewayError, acquirer, customer, attemptNumber);
    }

    /** What the eval harness scores against. */
    public GroundTruth groundTruth() {
        return new GroundTruth(txnId, trueReason, truth, novelErrorForm);
    }

    public record AgentView(
            String txnId,
            String orderId,
            String merchantId,
            long amountPaise,
            String currency,
            PaymentMethod method,
            Instant failedAt,
            String errorCode,
            String rawGatewayError,
            String acquirer,
            CustomerProfile customer,
            int attemptNumber
    ) {
    }

    /**
     * @param novelErrorForm true when the gateway string came from a wording no
     *                       rule was written for. Lets the report split accuracy
     *                       into a seen slice and an unseen slice.
     */
    public record GroundTruth(
            String txnId,
            FailureReason trueReason,
            RecoveryTruth truth,
            boolean novelErrorForm
    ) {
    }
}
