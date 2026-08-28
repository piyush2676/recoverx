package com.recoverx.datagen;

import com.recoverx.domain.FailureReason;

import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Realistic, deliberately messy gateway error strings.
 *
 * <p>Two tiers, and the split is the whole experiment:
 *
 * <ul>
 *   <li><b>Documented forms</b> - the shapes an engineer would have seen while
 *       building the merchant's integration. {@code RuleBasedClassifier} is written
 *       against these, and only these.</li>
 *   <li><b>Novel forms</b> - shapes from acquirers, PSPs and locales nobody wrote a
 *       rule for. Roughly one row in five. In production these appear every time a
 *       new bank is onboarded or a vendor changes a message.</li>
 * </ul>
 *
 * <p>Without this split, a regex written against the same catalog that generated the
 * data scores near 100% and the LLM has nothing to win. The novel slice is where the
 * cost of an LLM call is actually justified - or isn't. Either result is a finding.
 */
final class ErrorCatalog {

    record ErrorForm(String code, String message, boolean novel) {
    }

    /** Shapes the rule-based baseline was written against. */
    private static final Map<FailureReason, List<ErrorForm>> DOCUMENTED = Map.of(
            FailureReason.INSUFFICIENT_FUNDS, List.of(
                    form("BAD_REQUEST_ERROR", "Your card has insufficient funds. Please retry with a different card."),
                    form("GW00121", "issuer response :: NOT SUFFICIENT FUNDS :: acquirer=%s"),
                    form("U30", "UPI decline U30 - insufficient balance in linked account"),
                    form("51", "auth_declined code=51 desc=DO NOT HONOUR/NSF bank=%s"),
                    form("PAYMENT_FAILED", "payment failed :: reason=insufficient_balance :: acquirer=%s :: retry_allowed=true")
            ),
            FailureReason.ISSUER_DOWN, List.of(
                    form("GATEWAY_ERROR", "Issuer bank is down. Please try again after some time."),
                    form("91", "acquirer_timeout issuer=%s status=91 upstream_unavailable"),
                    form("U69", "UPI decline U69 - beneficiary bank offline"),
                    form("GW00340", "HTTP 503 from %s auth endpoint after 2 retries"),
                    form("BANK_DOWN", "scheduled maintenance window at %s, txn not attempted")
            ),
            FailureReason.EXPIRED_CARD, List.of(
                    form("54", "auth_declined code=54 desc=EXPIRED CARD bank=%s"),
                    form("BAD_REQUEST_ERROR", "The card used is expired. Try another card."),
                    form("GW00133", "card_expiry_invalid :: token stale :: acquirer=%s :: retry_same_card=false")
            ),
            FailureReason.AUTHENTICATION_FAILED, List.of(
                    form("3DS_TIMEOUT", "customer did not complete 3DS challenge within 300s"),
                    form("GW00201", "OTP not entered - session abandoned on %s ACS page"),
                    form("AUTHENTICATION_ERROR", "Payment processing cancelled by user"),
                    form("U17", "UPI decline U17 - collect request expired, payer did not approve"),
                    form("PA_FAILED", "pa_auth_failed acs=%s reason=challenge_abandoned")
            ),
            FailureReason.NETWORK_TIMEOUT, List.of(
                    form("GATEWAY_ERROR", "Payment processing failed due to error at bank or wallet gateway"),
                    form("ETIMEDOUT", "read timeout after 30000ms calling %s /pay"),
                    form("U68", "UPI decline U68 - debit timeout, no response from remitter"),
                    form("GW00500", "upstream reset :: connection closed by %s :: idempotent=true")
            ),
            FailureReason.INVALID_VPA, List.of(
                    form("U16", "UPI decline U16 - risk threshold exceeded for VPA"),
                    form("BAD_REQUEST_ERROR", "The VPA entered is invalid or does not exist"),
                    form("U01", "UPI decline U01 - invalid virtual address, PSP=%s")
            ),
            FailureReason.LIMIT_EXCEEDED, List.of(
                    form("61", "auth_declined code=61 desc=EXCEEDS WITHDRAWAL LIMIT bank=%s"),
                    form("U19", "UPI decline U19 - per-transaction limit breached"),
                    form("GW00155", "daily_txn_cap_reached customer_bank=%s reset_at=midnight_IST")
            ),
            FailureReason.RISK_BLOCKED, List.of(
                    form("RISK_DECLINE", "Transaction blocked by risk engine - do not retry"),
                    form("59", "auth_declined code=59 desc=SUSPECTED FRAUD bank=%s"),
                    form("GW00901", "velocity_rule_hit :: card blacklisted by %s :: permanent=true")
            )
    );

    /**
     * Shapes no rule was written for. Different vendors, different conventions,
     * regional wording, ISO-8583 raw dumps, transliterated Hindi. The meaning is
     * recoverable by anything that reads language; a keyword table misses most of it.
     */
    private static final Map<FailureReason, List<ErrorForm>> NOVEL = Map.of(
            FailureReason.INSUFFICIENT_FUNDS, List.of(
                    novel("ISO8583", "DE39=116 | acq=%s | narration: khaate mein paisa kam hai"),
                    novel("PSP_DECLINE", "settlement rejected by %s: available balance below order value"),
                    novel("E_BAL", "txn aborted; payer wallet could not cover INR amount at %s")
            ),
            FailureReason.ISSUER_DOWN, List.of(
                    novel("ISO8583", "DE39=96 | acq=%s | switch reported system malfunction"),
                    novel("NPCI_OUT", "remitter %s not responding on NPCI switch, cutover in progress")
            ),
            FailureReason.EXPIRED_CARD, List.of(
                    novel("ISO8583", "DE39=33 | acq=%s | captured card, validity elapsed"),
                    novel("TOKEN_STALE", "network token for %s past its good-thru date; re-tokenise required")
            ),
            FailureReason.AUTHENTICATION_FAILED, List.of(
                    novel("ACS_ABORT", "cardholder closed the %s verification window before submitting"),
                    novel("UPI_NO_ACT", "payer never opened the collect notification; mandate lapsed"),
                    novel("SCA_FAIL", "strong customer authentication incomplete at %s, no challenge response")
            ),
            FailureReason.NETWORK_TIMEOUT, List.of(
                    novel("ISO8583", "DE39=68 | acq=%s | response received too late, txn reversed"),
                    novel("SWITCH_LAG", "no acknowledgement from %s within the switch window; safe to re-present")
            ),
            FailureReason.INVALID_VPA, List.of(
                    novel("PSP_UNKNOWN", "handle not registered with any PSP on the %s directory"),
                    novel("ADDR_BAD", "payer address could not be resolved; check the id before re-presenting")
            ),
            FailureReason.LIMIT_EXCEEDED, List.of(
                    novel("ISO8583", "DE39=65 | acq=%s | activity count for the day breached"),
                    novel("CAP_HIT", "customer has crossed the %s ceiling for today; window reopens tomorrow")
            ),
            // Deliberately share no vocabulary with RuleBasedClassifier's risk keywords.
            // Reusing "fraud" or "do not retry" here would let the rule table look like it
            // generalises when it is only matching a word it was handed.
            FailureReason.RISK_BLOCKED, List.of(
                    novel("FRAUD_HOLD", "%s has placed an indefinite stop on this instrument pending investigation"),
                    novel("SANCTION", "instrument appears on an internal watchlist at %s; permanent stop")
            )
    );

    private static final double NOVEL_RATE = 0.20;

    private ErrorCatalog() {
    }

    private static ErrorForm form(String code, String template) {
        return new ErrorForm(code, template, false);
    }

    private static ErrorForm novel(String code, String template) {
        return new ErrorForm(code, template, true);
    }

    static ErrorForm sample(FailureReason reason, String acquirer, RandomGenerator rng) {
        List<ErrorForm> pool = rng.nextDouble() < NOVEL_RATE
                ? NOVEL.get(reason)
                : DOCUMENTED.get(reason);
        ErrorForm picked = pool.get(rng.nextInt(pool.size()));
        String rendered = picked.message().contains("%s")
                ? String.format(picked.message(), acquirer)
                : picked.message();
        return new ErrorForm(picked.code(), rendered, picked.novel());
    }
}
