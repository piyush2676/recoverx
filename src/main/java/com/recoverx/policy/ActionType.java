package com.recoverx.policy;

public enum ActionType {
    /** Re-attempt on the same rail, later. */
    RETRY_SAME_METHOD,
    /** The original rail is dead; re-attempt on a different one. */
    RETRY_ALTERNATE_METHOD,
    /** Send a Razorpay payment link and let the customer choose. */
    SEND_PAYMENT_LINK,
    /** Hand to a human. Counts as an exception, not a failure. */
    ESCALATE_HUMAN,
    /** Deliberately do nothing. The most important action in the set. */
    DO_NOTHING
}
