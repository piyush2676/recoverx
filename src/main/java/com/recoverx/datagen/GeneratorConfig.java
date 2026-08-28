package com.recoverx.datagen;

import com.recoverx.domain.FailureReason;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every knob that shapes the dataset, in one place, so the README can state the
 * exact mix and a reviewer can reproduce it from the seed alone.
 */
public record GeneratorConfig(
        int count,
        long seed,
        Path outDir,
        Duration window,
        String merchantId
) {
    public static GeneratorConfig defaults() {
        return new GeneratorConfig(500, 42L, Path.of("data"), Duration.ofDays(30), "acc_RZP_TEST_MERCHANT");
    }

    /**
     * Failure mix. Ordered map so the weights read top-to-bottom in the summary.
     * Roughly calibrated to public Indian gateway decline distributions - the
     * absolute numbers are synthetic, the shape is not arbitrary.
     */
    public static Map<FailureReason, Double> failureMix() {
        Map<FailureReason, Double> mix = new LinkedHashMap<>();
        mix.put(FailureReason.INSUFFICIENT_FUNDS, 0.27);
        mix.put(FailureReason.AUTHENTICATION_FAILED, 0.22);
        mix.put(FailureReason.ISSUER_DOWN, 0.12);
        mix.put(FailureReason.NETWORK_TIMEOUT, 0.10);
        mix.put(FailureReason.INVALID_VPA, 0.09);
        mix.put(FailureReason.EXPIRED_CARD, 0.08);
        mix.put(FailureReason.LIMIT_EXCEEDED, 0.07);
        mix.put(FailureReason.RISK_BLOCKED, 0.05);
        return mix;
    }

    /**
     * Probability that a transaction with this failure reason is recoverable at all.
     * RISK_BLOCKED is 0.0 on purpose: it is the trap. An agent that retries it is
     * not just wasting money, it is doing something a payments team would fire it for.
     */
    public static double recoverableRate(FailureReason reason) {
        return switch (reason) {
            case NETWORK_TIMEOUT -> 0.92;
            case ISSUER_DOWN -> 0.88;
            case LIMIT_EXCEEDED -> 0.70;
            case INSUFFICIENT_FUNDS -> 0.65;
            case AUTHENTICATION_FAILED -> 0.55;
            case EXPIRED_CARD -> 0.45;
            case INVALID_VPA -> 0.35;
            case RISK_BLOCKED -> 0.0;
        };
    }

    public static GeneratorConfig fromArgs(String[] args) {
        GeneratorConfig base = defaults();
        int count = base.count();
        long seed = base.seed();
        Path out = base.outDir();
        for (String arg : args) {
            if (arg.startsWith("--count=")) {
                count = Integer.parseInt(arg.substring("--count=".length()));
            } else if (arg.startsWith("--seed=")) {
                seed = Long.parseLong(arg.substring("--seed=".length()));
            } else if (arg.startsWith("--out=")) {
                out = Path.of(arg.substring("--out=".length()));
            }
        }
        return new GeneratorConfig(count, seed, out, base.window(), base.merchantId());
    }
}
