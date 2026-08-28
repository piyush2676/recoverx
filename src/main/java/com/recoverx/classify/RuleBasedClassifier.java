package com.recoverx.classify;

import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.FailureReason;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The baseline: what a competent engineer writes in an afternoon after reading the
 * gateway's error documentation.
 *
 * <p>This is deliberately a <em>good</em> baseline, not a strawman. It checks exact
 * vendor codes first, because those are unambiguous when present, then falls back to
 * keyword matching on the message. Comparing an LLM against a bad regex would prove
 * nothing.
 *
 * <p>Its ceiling is the point. Every rule here was written against
 * {@code ErrorCatalog}'s <em>documented</em> forms. It has never seen the novel
 * forms - new acquirers, other vendors' wording, transliterated text - and it has no
 * way to reason about them. That gap is what the report measures.
 */
public class RuleBasedClassifier implements FailureClassifier {

    /** Exact vendor codes. When one of these appears, it is decisive. */
    private static final Map<String, FailureReason> BY_CODE = Map.ofEntries(
            Map.entry("U30", FailureReason.INSUFFICIENT_FUNDS),
            Map.entry("51", FailureReason.INSUFFICIENT_FUNDS),
            Map.entry("GW00121", FailureReason.INSUFFICIENT_FUNDS),
            Map.entry("91", FailureReason.ISSUER_DOWN),
            Map.entry("U69", FailureReason.ISSUER_DOWN),
            Map.entry("GW00340", FailureReason.ISSUER_DOWN),
            Map.entry("BANK_DOWN", FailureReason.ISSUER_DOWN),
            Map.entry("54", FailureReason.EXPIRED_CARD),
            Map.entry("GW00133", FailureReason.EXPIRED_CARD),
            Map.entry("3DS_TIMEOUT", FailureReason.AUTHENTICATION_FAILED),
            Map.entry("GW00201", FailureReason.AUTHENTICATION_FAILED),
            Map.entry("AUTHENTICATION_ERROR", FailureReason.AUTHENTICATION_FAILED),
            Map.entry("U17", FailureReason.AUTHENTICATION_FAILED),
            Map.entry("PA_FAILED", FailureReason.AUTHENTICATION_FAILED),
            Map.entry("ETIMEDOUT", FailureReason.NETWORK_TIMEOUT),
            Map.entry("U68", FailureReason.NETWORK_TIMEOUT),
            Map.entry("GW00500", FailureReason.NETWORK_TIMEOUT),
            Map.entry("U16", FailureReason.INVALID_VPA),
            Map.entry("U01", FailureReason.INVALID_VPA),
            Map.entry("61", FailureReason.LIMIT_EXCEEDED),
            Map.entry("U19", FailureReason.LIMIT_EXCEEDED),
            Map.entry("GW00155", FailureReason.LIMIT_EXCEEDED),
            Map.entry("RISK_DECLINE", FailureReason.RISK_BLOCKED),
            Map.entry("59", FailureReason.RISK_BLOCKED),
            Map.entry("GW00901", FailureReason.RISK_BLOCKED)
    );

    /**
     * Keyword fallback, checked in order. RISK_BLOCKED sits first on purpose: when a
     * message could be read two ways, the safe reading is the one that stops the
     * money. A false RISK_BLOCKED costs a wasted recovery; a missed one is a
     * compliance violation.
     */
    private static final List<Rule> RULES = List.of(
            new Rule(FailureReason.RISK_BLOCKED, "fraud", "risk engine", "blacklist",
                    "do not retry", "do not re-present", "negative list", "compliance hold"),
            new Rule(FailureReason.EXPIRED_CARD, "expired", "expiry", "good-thru", "validity elapsed"),
            new Rule(FailureReason.INSUFFICIENT_FUNDS, "insufficient", "not sufficient",
                    "nsf", "do not honour", "insufficient_balance", "below order value"),
            new Rule(FailureReason.ISSUER_DOWN, "bank is down", "issuer bank", "offline",
                    "unavailable", "maintenance", "503", "system malfunction", "not responding"),
            new Rule(FailureReason.INVALID_VPA, "vpa", "virtual address", "invalid address",
                    "not registered"),
            new Rule(FailureReason.LIMIT_EXCEEDED, "limit", "cap", "ceiling", "exceeds withdrawal"),
            new Rule(FailureReason.AUTHENTICATION_FAILED, "3ds", "otp", "cancelled by user",
                    "abandoned", "collect request expired", "challenge", "authentication"),
            new Rule(FailureReason.NETWORK_TIMEOUT, "timeout", "timed out", "no response",
                    "connection closed", "upstream reset", "gateway")
    );

    private record Rule(FailureReason reason, String... keywords) {
    }

    @Override
    public Classification classify(FailedTransaction.AgentView txn) {
        FailureReason byCode = BY_CODE.get(txn.errorCode());
        if (byCode != null) {
            return Classification.of(byCode, 0.95,
                    "Gateway code " + txn.errorCode() + " maps to " + byCode + " in the vendor's documented code list.");
        }

        String message = txn.rawGatewayError().toLowerCase(Locale.ROOT);
        for (Rule rule : RULES) {
            for (String keyword : rule.keywords()) {
                if (message.contains(keyword)) {
                    return Classification.of(rule.reason(), 0.75,
                            "Message contains \"" + keyword + "\", which the rule table maps to " + rule.reason() + ".");
                }
            }
        }

        return Classification.rejected(
                "No documented code or keyword matched error " + txn.errorCode() + "; routed to a human.");
    }

    @Override
    public String name() {
        return "rules";
    }
}
