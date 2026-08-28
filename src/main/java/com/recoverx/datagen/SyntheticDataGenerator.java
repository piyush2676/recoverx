package com.recoverx.datagen;

import com.recoverx.domain.CustomerProfile;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.FailureReason;
import com.recoverx.domain.PaymentMethod;
import com.recoverx.domain.RecoveryTruth;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Builds a reproducible batch of failed payments plus the hidden truth about
 * whether each one can actually be recovered.
 *
 * The point of the hidden truth is the eval harness. Without it you can only
 * report "the agent retried 300 payments"; with it you can report recovered
 * rupees against an oracle ceiling and a naive baseline, which is the bar the
 * track actually asks for.
 */
public class SyntheticDataGenerator {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final PaymentMethod[] METHODS = PaymentMethod.values();

    private final GeneratorConfig config;
    private final RandomGenerator rng;

    public SyntheticDataGenerator(GeneratorConfig config) {
        this.config = config;
        this.rng = RandomGeneratorFactory.of("Xoshiro256PlusPlus").create(config.seed());
    }

    public List<FailedTransaction> generate() {
        List<CustomerProfile> customers = buildCustomerPool(Math.max(1, config.count() / 3));
        Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant start = end.minus(config.window());

        List<FailedTransaction> out = new ArrayList<>(config.count());
        for (int i = 0; i < config.count(); i++) {
            CustomerProfile customer = customers.get(rng.nextInt(customers.size()));
            Instant failedAt = randomInstantBetween(start, end);
            FailureReason reason = sampleReason();
            PaymentMethod method = sampleMethod(reason, customer);
            String acquirer = sampleAcquirer(method);
            ErrorCatalog.ErrorForm error = ErrorCatalog.sample(reason, acquirer, rng);

            out.add(new FailedTransaction(
                    "pay_" + token(14),
                    "order_" + token(14),
                    config.merchantId(),
                    sampleAmountPaise(),
                    "INR",
                    method,
                    failedAt,
                    error.code(),
                    error.message(),
                    acquirer,
                    customer,
                    1 + (rng.nextDouble() < 0.12 ? 1 : 0),
                    reason,
                    deriveTruth(reason, customer, failedAt),
                    error.novel()
            ));
        }
        out.sort(Comparator.comparing(FailedTransaction::failedAt));
        return out;
    }

    // ---------- truth model ----------

    /**
     * Decides whether this transaction can be recovered, when, and on what method.
     * Every branch here is a rule the agent has to rediscover from the error text
     * plus customer context - it is never handed the answer.
     */
    private RecoveryTruth deriveTruth(FailureReason reason, CustomerProfile customer, Instant failedAt) {
        if (reason == FailureReason.RISK_BLOCKED) {
            return RecoveryTruth.unrecoverable(true);
        }
        if (rng.nextDouble() >= GeneratorConfig.recoverableRate(reason)) {
            // Genuinely lost: the customer walked away. Retrying is waste, not a violation.
            return RecoveryTruth.unrecoverable(false);
        }

        return switch (reason) {
            case NETWORK_TIMEOUT -> new RecoveryTruth(
                    true, failedAt.plus(minutes(2, 30)), null, 1, false);

            case ISSUER_DOWN -> new RecoveryTruth(
                    true, failedAt.plus(minutes(30, 480)), null, 1, false);

            case AUTHENTICATION_FAILED -> new RecoveryTruth(
                    true, failedAt.plus(minutes(15, 120)), null,
                    rng.nextDouble() < 0.30 ? 2 : 1, false);

            case INSUFFICIENT_FUNDS -> new RecoveryTruth(
                    true, nextPayday(failedAt, customer.paydayDayOfMonth()), null,
                    rng.nextDouble() < 0.25 ? 2 : 1, false);

            case LIMIT_EXCEEDED -> new RecoveryTruth(
                    true, nextMidnightIst(failedAt), null, 1, false);

            // Card is dead: only a different rail can succeed.
            case EXPIRED_CARD -> new RecoveryTruth(
                    true, failedAt.plus(minutes(5, 60)),
                    rng.nextBoolean() ? PaymentMethod.UPI : PaymentMethod.NETBANKING, 1, false);

            // The VPA is wrong and will stay wrong: move the customer to a card or netbanking.
            case INVALID_VPA -> new RecoveryTruth(
                    true, failedAt.plus(minutes(5, 60)),
                    customer.hasSavedCard() ? PaymentMethod.CARD : PaymentMethod.NETBANKING, 1, false);

            case RISK_BLOCKED -> throw new IllegalStateException("handled above");
        };
    }

    // ---------- samplers ----------

    private List<CustomerProfile> buildCustomerPool(int size) {
        List<CustomerProfile> pool = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            pool.add(new CustomerProfile(
                    "cust_" + token(12),
                    // Indian salary credits cluster at month end and the 1st.
                    switch (rng.nextInt(10)) {
                        case 0, 1, 2, 3 -> 1;
                        case 4, 5, 6 -> 28 + rng.nextInt(3);
                        case 7, 8 -> 7;
                        default -> 15;
                    },
                    round2(0.55 + rng.nextDouble() * 0.44),
                    METHODS[weightedMethodIndex()],
                    rng.nextInt(5),
                    rng.nextDouble() < 0.62
            ));
        }
        return pool;
    }

    private FailureReason sampleReason() {
        double roll = rng.nextDouble();
        double cumulative = 0.0;
        for (Map.Entry<FailureReason, Double> entry : GeneratorConfig.failureMix().entrySet()) {
            cumulative += entry.getValue();
            if (roll < cumulative) {
                return entry.getKey();
            }
        }
        return FailureReason.INSUFFICIENT_FUNDS;
    }

    /** Some reasons only happen on some rails - a VPA cannot be invalid on a card. */
    private PaymentMethod sampleMethod(FailureReason reason, CustomerProfile customer) {
        return switch (reason) {
            case INVALID_VPA -> PaymentMethod.UPI;
            case EXPIRED_CARD -> PaymentMethod.CARD;
            default -> rng.nextDouble() < 0.55
                    ? customer.preferredMethod()
                    : METHODS[weightedMethodIndex()];
        };
    }

    private int weightedMethodIndex() {
        // UPI dominates Indian volume; wallet is the tail.
        double roll = rng.nextDouble();
        if (roll < 0.58) return 0;   // UPI
        if (roll < 0.85) return 1;   // CARD
        if (roll < 0.96) return 2;   // NETBANKING
        return 3;                    // WALLET
    }

    private String sampleAcquirer(PaymentMethod method) {
        String[] banks = method == PaymentMethod.UPI
                ? new String[]{"HDFC", "ICICI", "AXIS", "SBIN", "PAYTM", "YESB"}
                : new String[]{"HDFC", "ICICI", "AXIS", "SBIN", "KOTAK", "RBL"};
        return banks[rng.nextInt(banks.length)];
    }

    /**
     * Log-normal rupee amounts: a long tail of small orders and a few large ones,
     * so recovered-rupees is driven by which transactions the agent picks, not by
     * a flat average. Clamped to a plausible checkout range.
     */
    private long sampleAmountPaise() {
        double rupees = Math.exp(6.6 + rng.nextGaussian());
        rupees = Math.min(200_000, Math.max(99, rupees));
        return Math.round(rupees * 100);
    }

    private Instant randomInstantBetween(Instant start, Instant end) {
        long span = end.getEpochSecond() - start.getEpochSecond();
        return start.plusSeconds((long) (rng.nextDouble() * span));
    }

    private Duration minutes(int minInclusive, int maxExclusive) {
        return Duration.ofMinutes(minInclusive + rng.nextInt(maxExclusive - minInclusive));
    }

    private Instant nextPayday(Instant from, int dayOfMonth) {
        ZonedDateTime zoned = from.atZone(IST);
        LocalDate date = zoned.toLocalDate();
        LocalDate candidate = safeDayOfMonth(date, dayOfMonth);
        if (!candidate.isAfter(date)) {
            candidate = safeDayOfMonth(date.plusMonths(1), dayOfMonth);
        }
        return candidate.atTime(10, 0).atZone(IST).toInstant();
    }

    private LocalDate safeDayOfMonth(LocalDate reference, int dayOfMonth) {
        return reference.withDayOfMonth(Math.min(dayOfMonth, reference.lengthOfMonth()));
    }

    private Instant nextMidnightIst(Instant from) {
        return from.atZone(IST).toLocalDate().plusDays(1).atStartOfDay(IST).toInstant();
    }

    private String token(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
