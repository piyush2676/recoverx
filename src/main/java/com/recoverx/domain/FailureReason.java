package com.recoverx.domain;

/**
 * Ground-truth failure buckets. The agent never sees these directly - it must
 * infer them from the raw gateway error string. Kept out of the agent-facing
 * dataset and written only to ground_truth.jsonl for the eval harness.
 */
public enum FailureReason {
    INSUFFICIENT_FUNDS,
    ISSUER_DOWN,
    EXPIRED_CARD,
    AUTHENTICATION_FAILED,
    NETWORK_TIMEOUT,
    INVALID_VPA,
    LIMIT_EXCEEDED,
    RISK_BLOCKED
}
