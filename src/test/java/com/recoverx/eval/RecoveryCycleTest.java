package com.recoverx.eval;

import com.recoverx.classify.Classification;
import com.recoverx.classify.RuleBasedClassifier;
import com.recoverx.datagen.GeneratorConfig;
import com.recoverx.datagen.SyntheticDataGenerator;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.eval.RecoveryCycle.ArmResult;
import com.recoverx.policy.PolicyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the comparison harness against itself.
 *
 * <p>The oracle check is the important one. If perfect knowledge cannot collect the
 * ceiling, then {@link OracleDecisionSource} and {@link SimulatedExecutor} disagree
 * about what counts as a successful attempt - and every number in the report is wrong
 * in a way that no amount of staring at the policy engine would reveal.
 */
class RecoveryCycleTest {

    private List<FailedTransaction.AgentView> transactions;
    private Map<String, FailedTransaction.GroundTruth> truth;
    private RecoveryCycle cycle;

    @BeforeEach
    void setUp() {
        List<FailedTransaction> batch = new SyntheticDataGenerator(
                new GeneratorConfig(200, 7L, Path.of("target/test-data"),
                        Duration.ofDays(30), "acc_TEST")).generate();

        transactions = batch.stream().map(FailedTransaction::redacted).toList();
        truth = new LinkedHashMap<>();
        batch.forEach(txn -> truth.put(txn.txnId(), txn.groundTruth()));
        cycle = new RecoveryCycle(transactions, truth, PolicyConfig.defaults());
    }

    @Test
    void theOracleCollectsExactlyTheCeiling(@TempDir Path tmp) throws IOException {
        ArmResult oracle = cycle.run(new OracleDecisionSource(truth), tmp.resolve("oracle"), false, null);

        assertEquals(oracle.ceilingPaise(), oracle.recoveredPaise(),
                "the oracle and the simulator disagree about what a successful attempt is");
        assertEquals(1.0, oracle.recoveryRate(), 1e-9);
    }

    @Test
    void theOracleNeverTouchesAnInstrumentItMustNot(@TempDir Path tmp) throws IOException {
        ArmResult oracle = cycle.run(new OracleDecisionSource(truth), tmp.resolve("oracle"), false, null);

        assertEquals(0, oracle.complianceViolations());
        assertEquals(0, oracle.wastedAttempts(), "perfect knowledge wastes nothing");
    }

    /**
     * The baseline's violations are not a bug in the baseline - they are what not having
     * a classifier costs. Removing them would quietly rig the comparison.
     */
    @Test
    void theNaiveBaselineRePresentsRiskBlockedDeclines(@TempDir Path tmp) throws IOException {
        ArmResult naive = cycle.run(new NaiveDecisionSource(Duration.ofHours(1), "naive"),
                tmp.resolve("naive"), false, null);

        long riskBlocked = truth.values().stream().filter(t -> t.truth().mustNotRetry()).count();
        assertEquals(riskBlocked, naive.complianceViolations(),
                "a strategy with no classifier cannot know which declines to leave alone");
    }

    @Test
    void theNaiveBaselineAttemptsEachTransactionExactlyOnce(@TempDir Path tmp) throws IOException {
        ArmResult naive = cycle.run(new NaiveDecisionSource(Duration.ofHours(1), "naive"),
                tmp.resolve("naive"), false, null);

        assertEquals(transactions.size(), naive.attempts());
    }

    @Test
    void thePolicyArmCommitsNoViolationsAndNoDoubleCharges(@TempDir Path tmp) throws IOException {
        ArmResult recoverx = cycle.run(
                new PolicyDecisionSource(PolicyConfig.defaults(), ruleVerdicts(), "recoverx"),
                tmp.resolve("recoverx"), false, null);

        assertEquals(0, recoverx.complianceViolations());
        assertEquals(0, recoverx.doubleCharges());
    }

    @Test
    void thePolicyArmBeatsTheHourlyRetryCronButNotTheOracle(@TempDir Path tmp) throws IOException {
        ArmResult naive = cycle.run(new NaiveDecisionSource(Duration.ofHours(1), "naive"),
                tmp.resolve("naive"), false, null);
        ArmResult recoverx = cycle.run(
                new PolicyDecisionSource(PolicyConfig.defaults(), ruleVerdicts(), "recoverx"),
                tmp.resolve("recoverx"), false, null);
        ArmResult oracle = cycle.run(new OracleDecisionSource(truth), tmp.resolve("oracle"), false, null);

        assertTrue(recoverx.recoveredPaise() > naive.recoveredPaise(),
                "the policy engine must be worth more than an hourly retry cron");
        assertTrue(recoverx.recoveredPaise() <= oracle.recoveredPaise(),
                "nothing may beat perfect knowledge; if it does, the simulator is being fooled");
    }

    @Test
    void noArmEverChargesTheSameTransactionTwice(@TempDir Path tmp) throws IOException {
        List<DecisionSource> sources = List.of(
                new NaiveDecisionSource(Duration.ZERO, "naive-immediate"),
                new NaiveDecisionSource(Duration.ofHours(1), "naive-hourly"),
                new PolicyDecisionSource(PolicyConfig.defaults(), ruleVerdicts(), "recoverx"),
                new OracleDecisionSource(truth));

        for (DecisionSource source : sources) {
            ArmResult result = cycle.run(source, tmp.resolve(source.name()), false, null);
            assertEquals(0, result.doubleCharges(), source.name() + " double-charged a transaction");
        }
    }

    private Map<String, Classification> ruleVerdicts() {
        RuleBasedClassifier rules = new RuleBasedClassifier();
        Map<String, Classification> verdicts = new HashMap<>();
        transactions.forEach(txn -> verdicts.put(txn.txnId(), rules.classify(txn)));
        return verdicts;
    }
}
