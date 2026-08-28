package com.recoverx.classify;

import com.recoverx.domain.FailureReason;

/**
 * A classifier's verdict on one failed payment.
 *
 * <p>A rejected classification carries a null reason. That is not an error path
 * bolted on afterwards - it is the safe default. When the model returns something
 * that does not validate, or the classifier simply does not recognise the error
 * string, the transaction must land in the human queue. It must never fall through
 * to a charge on a guess.
 *
 * @param reason        the bucket, or null when the classifier declined to answer
 * @param confidence    0..1; the policy engine refuses to act below a floor
 * @param justification one sentence of plain English that goes into the ledger.
 *                      This is the "explainable" half of the track's bar: every
 *                      money action must be traceable to a written reason.
 */
public record Classification(
        FailureReason reason,
        double confidence,
        String justification
) {

    public boolean accepted() {
        return reason != null;
    }

    public static Classification of(FailureReason reason, double confidence, String justification) {
        if (reason == null) {
            throw new IllegalArgumentException("use rejected() for a null reason");
        }
        return new Classification(reason, confidence, justification);
    }

    /** No verdict. Routes to a human; never to a retry. */
    public static Classification rejected(String why) {
        return new Classification(null, 0.0, why);
    }
}
