package com.recoverx.policy;

import com.recoverx.classify.Classification;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.FailureReason;
import com.recoverx.domain.PaymentMethod;
import com.recoverx.ledger.Ledger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The deterministic half of the agent. Everything that decides whether money moves
 * lives here, in ordinary Java, and nowhere else.
 *
 * <p>The engine runs two phases in a fixed order, and the order is the safety
 * property:
 *
 * <ol>
 *   <li><b>Gates</b> - six checks that can only ever stop an attempt. They run before
 *       any action is chosen, so there is no path where an action is selected first
 *       and a gate is consulted afterwards.</li>
 *   <li><b>Action selection</b> - given that the gates passed, what to do and when.</li>
 * </ol>
 *
 * <p>These rules are code rather than prompt text on purpose. A prompt can be argued
 * out of a stopping rule by a sufficiently strange input. An {@code if} statement
 * cannot, and a reviewer can read the whole envelope in one sitting.
 */
public class DefaultRecoveryPolicy implements RecoveryPolicy {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PolicyConfig config;
    private final Clock clock;

    public DefaultRecoveryPolicy(PolicyConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    @Override
    public RecoveryDecision decide(FailedTransaction.AgentView txn,
                                   Classification classification,
                                   Ledger ledger) {
        Instant now = clock.instant();
        int attemptNumber = ledger.attemptCount(txn.txnId()) + 1;

        // ---- gate 1: no verdict, no automation ----
        if (!classification.accepted()) {
            return stop(txn, classification, ActionType.ESCALATE_HUMAN, attemptNumber,
                    "classifier returned no verdict; a person decides this one");
        }

        // ---- gate 2: hard stop on a compliance decline ----
        // Checked before the confidence floor on purpose. A low-confidence guess of
        // RISK_BLOCKED must still stop the money, not fall through to the next gate.
        if (classification.reason() == FailureReason.RISK_BLOCKED) {
            return stop(txn, classification, ActionType.DO_NOTHING, attemptNumber,
                    "risk-blocked decline; retrying is a compliance violation, hard stop");
        }

        // ---- gate 3: confidence floor ----
        if (classification.confidence() < config.minClassifierConfidence()) {
            return stop(txn, classification, ActionType.ESCALATE_HUMAN, attemptNumber,
                    String.format("confidence %.2f below the %.2f floor; routed to a human",
                            classification.confidence(), config.minClassifierConfidence()));
        }

        // ---- gate 4: attempt ceiling ----
        if (attemptNumber > config.maxAttemptsPerTxn()) {
            return stop(txn, classification, ActionType.DO_NOTHING, attemptNumber,
                    "already attempted " + config.maxAttemptsPerTxn() + " times; ceiling reached");
        }

        // ---- gate 5: one live attempt at a time ----
        if (ledger.hasPendingAttempt(txn.txnId())) {
            return stop(txn, classification, ActionType.DO_NOTHING, attemptNumber,
                    "an attempt is already in flight for this transaction");
        }

        // ---- gate 6: age cutoff ----
        int windowDays = config.maxAgeDaysFor(classification.reason());
        Instant cutoff = txn.failedAt().plus(Duration.ofDays(windowDays));
        if (!now.isBefore(cutoff)) {
            return stop(txn, classification, ActionType.DO_NOTHING, attemptNumber,
                    "original failure is older than " + windowDays + " days; window closed");
        }

        Plan plan = planFor(classification.reason(), txn, attemptNumber, now);
        if (plan == null) {
            return stop(txn, classification, ActionType.DO_NOTHING, attemptNumber,
                    "no recovery action is plausible for this decline");
        }

        Instant scheduledFor = plan.when().isBefore(now) ? now : plan.when();
        if (!scheduledFor.isBefore(cutoff)) {
            // The right moment to retry falls outside the window. Attempting earlier
            // would be attempting at a time we already believe will fail.
            return stop(txn, classification, ActionType.DO_NOTHING, attemptNumber,
                    "the earliest sensible attempt falls outside the "
                            + windowDays + "-day window");
        }

        return new RecoveryDecision(
                txn.txnId(),
                plan.action(),
                txn.amountPaise(),
                attemptNumber,
                scheduledFor,
                plan.method(),
                config.costOf(plan.action()),
                classification.reason(),
                classification.confidence(),
                classification.justification(),
                "attempt " + attemptNumber + " of " + config.maxAttemptsPerTxn()
                        + "; " + plan.rationale());
    }

    // ---------- action selection ----------

    private record Plan(ActionType action, Instant when, PaymentMethod method, String rationale) {
    }

    /**
     * What to do, and when it has a chance of working. The timing is the whole game:
     * an insufficient-funds decline retried ten minutes later fails again, and the same
     * decline retried on payday morning goes through.
     */
    private Plan planFor(FailureReason reason,
                         FailedTransaction.AgentView txn,
                         int attemptNumber,
                         Instant now) {
        return switch (reason) {

            // The money is not there yet. Wait for the salary credit, plus a buffer so
            // we are not racing the bank's own batch.
            case INSUFFICIENT_FUNDS -> new Plan(
                    ActionType.RETRY_SAME_METHOD,
                    nextPayday(now, txn.customer().paydayDayOfMonth()),
                    null,
                    "insufficient funds; scheduled just after the customer's next salary credit");

            // Outages resolve. Back off further each attempt rather than hammering a
            // bank that is already struggling.
            case ISSUER_DOWN -> new Plan(
                    ActionType.RETRY_SAME_METHOD,
                    txn.failedAt().plus(Duration.ofHours(switch (attemptNumber) {
                        case 1 -> 2;
                        case 2 -> 8;
                        default -> 24;
                    })),
                    null,
                    "issuer outage; backing off " + (attemptNumber == 1 ? "2h" : attemptNumber == 2 ? "8h" : "24h"));

            // Nothing was decided, so re-presenting is cheap and usually works.
            case NETWORK_TIMEOUT -> new Plan(
                    ActionType.RETRY_SAME_METHOD,
                    txn.failedAt().plus(Duration.ofMinutes(attemptNumber == 1 ? 30 : 120)),
                    null,
                    "no decision was reached upstream; safe to re-present");

            // The customer has to act. A silent retry cannot make them finish an OTP,
            // so send them something they can tap.
            case AUTHENTICATION_FAILED -> new Plan(
                    ActionType.SEND_PAYMENT_LINK,
                    txn.failedAt().plus(Duration.ofHours(attemptNumber == 1 ? 2 : 24)),
                    null,
                    "customer abandoned authentication; a link lets them finish on their own time");

            // Daily ceilings reset at midnight. Aim just after, not exactly at.
            case LIMIT_EXCEEDED -> new Plan(
                    ActionType.RETRY_SAME_METHOD,
                    nextMidnightIst(now).plus(Duration.ofHours(1)),
                    null,
                    "daily limit breached; scheduled after the limit resets");

            // The card is dead. Retrying it is guaranteed waste, so move rails. A link
            // lets the customer pick one that works rather than us guessing.
            case EXPIRED_CARD -> new Plan(
                    ActionType.SEND_PAYMENT_LINK,
                    now.plus(Duration.ofMinutes(15)),
                    null,
                    "card is past validity; a link lets the customer pay on another rail");

            // The handle will not resolve on a retry either. If we already hold a card
            // on file we can switch rails ourselves; otherwise the customer chooses.
            case INVALID_VPA -> txn.customer().hasSavedCard()
                    ? new Plan(ActionType.RETRY_ALTERNATE_METHOD,
                    now.plus(Duration.ofMinutes(15)),
                    PaymentMethod.CARD,
                    "UPI handle does not resolve; switching to the saved card")
                    : new Plan(ActionType.SEND_PAYMENT_LINK,
                    now.plus(Duration.ofMinutes(15)),
                    null,
                    "UPI handle does not resolve and no card is on file; sending a link");

            // Unreachable: gate 2 stopped this already. Kept explicit so adding a bucket
            // to the enum breaks the build here rather than silently defaulting.
            case RISK_BLOCKED -> null;
        };
    }

    // ---------- helpers ----------

    private RecoveryDecision stop(FailedTransaction.AgentView txn,
                                  Classification classification,
                                  ActionType action,
                                  int attemptNumber,
                                  String gateReason) {
        return new RecoveryDecision(
                txn.txnId(),
                action,
                txn.amountPaise(),
                attemptNumber,
                null,
                null,
                0L,
                classification.reason(),
                classification.confidence(),
                classification.justification(),
                gateReason);
    }

    /**
     * Computed from {@code paydayDayOfMonth}, which is ordinary merchant-visible
     * customer data - not from the hidden truth. Aimed at 10:30 IST, half an hour after
     * a typical salary batch lands.
     */
    private Instant nextPayday(Instant from, int dayOfMonth) {
        LocalDate today = from.atZone(IST).toLocalDate();
        LocalDate candidate = clampDayOfMonth(today, dayOfMonth);
        if (!candidate.isAfter(today)) {
            candidate = clampDayOfMonth(today.plusMonths(1), dayOfMonth);
        }
        return candidate.atTime(10, 30).atZone(IST).toInstant();
    }

    private LocalDate clampDayOfMonth(LocalDate reference, int dayOfMonth) {
        return reference.withDayOfMonth(Math.min(dayOfMonth, reference.lengthOfMonth()));
    }

    private Instant nextMidnightIst(Instant from) {
        return from.atZone(IST).toLocalDate().plusDays(1).atStartOfDay(IST).toInstant();
    }
}
