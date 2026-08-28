package com.recoverx.domain;

/**
 * Context the agent is allowed to use when choosing a recovery action.
 * Everything here is observable in a real merchant's own data.
 */
public record CustomerProfile(
        String customerId,
        int paydayDayOfMonth,
        double historicalSuccessRate,
        PaymentMethod preferredMethod,
        int priorFailures30d,
        boolean hasSavedCard
) {
}
