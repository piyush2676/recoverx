package com.recoverx.classify;

import com.recoverx.domain.FailedTransaction;

import java.util.List;

/**
 * The prompt and the batch rendering, shared verbatim by every model provider.
 *
 * <p>This exists so the provider comparison measures the model rather than the prompt.
 * If each classifier carried its own copy, a wording drift in one of them would show up
 * in the report as a capability difference, and nothing would catch it.
 *
 * <p>{@link #VERSION} is part of every cache key, so editing anything here correctly
 * misses the cache instead of silently reporting stale verdicts.
 */
public final class ClassifierPrompt {

    /** Bump when the prompt changes, so cached verdicts from the old prompt miss. */
    public static final String VERSION = "v1";

    public static final int BATCH_SIZE = 25;

    public static final String SYSTEM = """
            You classify failed Indian online payments for a merchant's revenue recovery system.

            For each transaction you are given the gateway's raw error text, its error code, the
            payment method, and some context about the customer. Assign exactly one bucket:

            INSUFFICIENT_FUNDS     - the payer did not have the money at that moment
            ISSUER_DOWN            - the payer's bank or its switch was unavailable
            EXPIRED_CARD           - the card or its network token is past validity
            AUTHENTICATION_FAILED  - the payer abandoned or failed 3DS / OTP / a UPI collect
            NETWORK_TIMEOUT        - no timely response; the payment never reached a decision
            INVALID_VPA            - the UPI handle does not resolve to a real account
            LIMIT_EXCEEDED         - a per-transaction or daily ceiling was breached
            RISK_BLOCKED           - a fraud, sanctions, velocity or compliance stop

            Rules:
            - The error text may come from an acquirer you have never seen, may use raw ISO 8583
              fields, or may be written informally. Read it for meaning; do not pattern-match codes.
            - When the text could be read two ways and one reading is RISK_BLOCKED, choose
              RISK_BLOCKED. A wrong RISK_BLOCKED costs one missed recovery. A missed one is a
              compliance breach.
            - Do not confuse ISSUER_DOWN with NETWORK_TIMEOUT. ISSUER_DOWN means the bank itself
              was unavailable. NETWORK_TIMEOUT means a response never arrived in time.
            - confidence is your own calibrated probability that the bucket is correct. Use the
              full range. Low confidence on genuinely ambiguous text is correct behaviour, not a
              failure - those transactions get reviewed by a person.
            - justification is one sentence, under 200 characters, quoting the part of the error
              text that decided it. It is written into a financial audit log that humans read.

            Output format: one JSON object per line, no markdown fence, no preamble, nothing else.
            Emit exactly one line for every transaction id given, in the order given.

            {"txn_id":"<id>","reason":"<BUCKET>","confidence":<0.0-1.0>,"justification":"<one sentence>"}
            """;

    private ClassifierPrompt() {
    }

    public static String renderBatch(List<FailedTransaction.AgentView> batch) {
        StringBuilder sb = new StringBuilder("Classify these ")
                .append(batch.size()).append(" failed payments.\n\n");
        for (FailedTransaction.AgentView txn : batch) {
            sb.append("txn_id: ").append(txn.txnId()).append('\n')
                    .append("method: ").append(txn.method()).append('\n')
                    .append("acquirer: ").append(txn.acquirer()).append('\n')
                    .append("error_code: ").append(txn.errorCode()).append('\n')
                    .append("error_text: ").append(txn.rawGatewayError()).append('\n')
                    .append("amount_inr: ").append(txn.amountPaise() / 100.0).append('\n')
                    .append("customer_prior_failures_30d: ").append(txn.customer().priorFailures30d()).append('\n')
                    .append("customer_has_saved_card: ").append(txn.customer().hasSavedCard()).append('\n')
                    .append('\n');
        }
        return sb.toString();
    }
}
