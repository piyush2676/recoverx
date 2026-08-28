package com.recoverx.datagen;

import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.FailureReason;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticDataGeneratorTest {

    private static List<FailedTransaction> generate(long seed) {
        GeneratorConfig config = new GeneratorConfig(
                400, seed, java.nio.file.Path.of("target/test-data"),
                java.time.Duration.ofDays(30), "acc_TEST");
        return new SyntheticDataGenerator(config).generate();
    }

    @Test
    void sameSeedProducesSameBatch() {
        List<String> first = generate(7L).stream().map(FailedTransaction::txnId).toList();
        List<String> second = generate(7L).stream().map(FailedTransaction::txnId).toList();
        assertEquals(first, second, "generator must be reproducible from the seed alone");
    }

    @Test
    void differentSeedProducesDifferentBatch() {
        List<String> first = generate(7L).stream().map(FailedTransaction::txnId).toList();
        List<String> second = generate(8L).stream().map(FailedTransaction::txnId).toList();
        assertFalse(first.equals(second));
    }

    @Test
    void riskBlockedIsAlwaysAnUnrecoverableTrap() {
        generate(42L).stream()
                .filter(t -> t.trueReason() == FailureReason.RISK_BLOCKED)
                .forEach(t -> {
                    assertFalse(t.truth().recoverable(), t.txnId() + " must not be recoverable");
                    assertTrue(t.truth().mustNotRetry(), t.txnId() + " must be gated");
                });
    }

    @Test
    void recoverableTransactionsCarryAWindowAndNoTrapFlag() {
        generate(42L).stream()
                .filter(t -> t.truth().recoverable())
                .forEach(t -> {
                    assertTrue(t.truth().recoverableFrom() != null,
                            t.txnId() + " recoverable but has no window");
                    assertTrue(t.truth().attemptsNeeded() >= 1);
                    assertFalse(t.truth().mustNotRetry());
                });
    }

    @Test
    void expiredCardCanOnlyBeRecoveredOnAnotherRail() {
        generate(42L).stream()
                .filter(t -> t.trueReason() == FailureReason.EXPIRED_CARD && t.truth().recoverable())
                .forEach(t -> assertTrue(t.truth().requiredMethod() != null
                                && t.truth().requiredMethod() != com.recoverx.domain.PaymentMethod.CARD,
                        t.txnId() + " expired card must require a non-card rail"));
    }

    /**
     * The whole "the agent never saw the answer" claim rests on this. Assert against
     * the real serialized payload the agent reads, not against toString().
     */
    @Test
    void agentViewSerializesWithoutGroundTruth() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

        for (FailedTransaction txn : generate(42L)) {
            String json = mapper.writeValueAsString(txn.redacted()).toLowerCase();
            assertFalse(json.contains("truereason"), txn.txnId() + " leaks trueReason");
            assertFalse(json.contains("recoverable"), txn.txnId() + " leaks recoverability");
            assertFalse(json.contains("mustnotretry"), txn.txnId() + " leaks the trap flag");
            assertFalse(json.contains("attemptsneeded"), txn.txnId() + " leaks attemptsNeeded");
        }
    }
}
