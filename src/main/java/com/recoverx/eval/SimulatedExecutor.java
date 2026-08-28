package com.recoverx.eval;

import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.PaymentMethod;
import com.recoverx.domain.RecoveryTruth;
import com.recoverx.executor.AttemptResult;
import com.recoverx.executor.IdempotencyGuard;
import com.recoverx.executor.RecoveryExecutor;
import com.recoverx.policy.ActionType;
import com.recoverx.policy.RecoveryDecision;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves an attempt against the hidden truth.
 *
 * <p>This is the measurement engine. Against Razorpay test mode you can prove the
 * plumbing works but you cannot prove a rupee was recovered, because a test-mode
 * payment link is never really paid. Here the simulation knows whether each decline
 * was recoverable, when, and on which rail, so "Rs 214,000 recovered" is a computed
 * number rather than a claim.
 *
 * <p>It lives in {@code eval} rather than {@code executor} because it reads the truth
 * file, and nothing the agent uses may. {@code GroundTruthIsolationTest} enforces that
 * boundary.
 *
 * <h2>Resolution rules</h2>
 *
 * An attempt succeeds when all four hold:
 * <ol>
 *   <li>the decline was recoverable at all</li>
 *   <li>it was scheduled at or after the moment recovery became possible</li>
 *   <li>it used a rail that can work</li>
 *   <li>it is the n-th such qualifying attempt, where n is what the truth says it takes</li>
 * </ol>
 *
 * <h2>Modelling the gateway's own duplicate check</h2>
 *
 * <p>Razorpay requires a payment link's {@code reference_id} to be unique, so a repeat
 * of the same attempt is rejected at the far end even if the local guard missed it.
 * That second layer only matters in one window: a process that called out and died
 * before it could write the outcome row. On resume the ledger shows no outcome, the
 * local guard says "go", and only the remote check stands between that and a second
 * charge.
 *
 * <p>So the simulation keeps its own durable record of every key it has been presented,
 * written <em>before</em> the attempt resolves. A crash anywhere after that point still
 * leaves the key on disk, and the resume is rejected exactly as the real gateway would
 * reject it. Modelling this is the difference between demonstrating the guard and
 * asserting it.
 *
 * <h2>The payment-link assumption</h2>
 *
 * <p>A payment link is treated as satisfying any required rail, because the customer
 * chooses how to pay when they open it. This is a modelling choice and it favours the
 * agent, so it is stated here and in the report rather than left for a reader to
 * discover. The alternative - making links satisfy only the original rail - would
 * understate every link-based recovery, which is equally wrong.
 */
public class SimulatedExecutor implements RecoveryExecutor {

    private final Map<String, FailedTransaction.GroundTruth> truth;
    private final Map<String, FailedTransaction.AgentView> transactions;
    private final IdempotencyGuard guard;
    private final Map<String, Integer> qualifyingAttempts = new HashMap<>();
    private final Path presentedKeys;
    private final Set<String> seenKeys = new HashSet<>();

    private int complianceViolations = 0;
    private int replays = 0;
    private int duplicatesRejectedByGateway = 0;
    private int reconciled = 0;

    public SimulatedExecutor(List<FailedTransaction.AgentView> transactions,
                             Map<String, FailedTransaction.GroundTruth> truth,
                             IdempotencyGuard guard,
                             Path presentedKeys) throws IOException {
        this.truth = truth;
        this.guard = guard;
        this.presentedKeys = presentedKeys;
        this.transactions = new HashMap<>();
        transactions.forEach(txn -> this.transactions.put(txn.txnId(), txn));

        if (presentedKeys.getParent() != null) {
            Files.createDirectories(presentedKeys.getParent());
        }
        if (Files.exists(presentedKeys)) {
            seenKeys.addAll(Files.readAllLines(presentedKeys, StandardCharsets.UTF_8));
        }
    }

    /**
     * Records the key before resolving, so a crash after this point still leaves
     * evidence the gateway was already asked.
     */
    private boolean alreadyPresented(String key) {
        if (!seenKeys.add(key)) {
            return true;
        }
        try (var writer = Files.newBufferedWriter(presentedKeys, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(key);
            writer.newLine();
        } catch (IOException e) {
            throw new UncheckedIOException("could not record the presented key", e);
        }
        return false;
    }

    @Override
    public AttemptResult execute(RecoveryDecision decision) {
        Optional<AttemptResult> already = guard.previousOutcome(decision);
        if (already.isPresent()) {
            replays++;
            return already.get();
        }

        String key = decision.idempotencyKey();
        if (key == null) {
            throw new IllegalArgumentException(
                    "decision " + decision.txnId() + " does not move money and must not be executed");
        }

        if (alreadyPresented(key)) {
            duplicatesRejectedByGateway++;
            return AttemptResult.failure(decision.txnId(), key, 0L, decision.scheduledFor(),
                    "gateway rejected a duplicate reference_id; this attempt was already presented");
        }

        FailedTransaction.AgentView txn = transactions.get(decision.txnId());
        FailedTransaction.GroundTruth groundTruth = truth.get(decision.txnId());
        Instant at = decision.scheduledFor();
        long cost = decision.costPaise();

        if (txn == null || groundTruth == null) {
            return AttemptResult.failure(decision.txnId(), key, cost, at,
                    "no such transaction in the batch");
        }

        RecoveryTruth recovery = groundTruth.truth();

        // The trap. Counted on its own line and never folded into a success rate: this
        // is a compliance breach, not a failed recovery.
        if (recovery.mustNotRetry()) {
            complianceViolations++;
            return AttemptResult.failure(decision.txnId(), key, cost, at,
                            "COMPLIANCE VIOLATION: instrument must never be re-presented")
                    .asComplianceViolation("COMPLIANCE VIOLATION: instrument must never be re-presented");
        }

        if (!recovery.recoverable()) {
            return AttemptResult.failure(decision.txnId(), key, cost, at,
                    "customer was never going to complete this payment");
        }

        if (at == null || at.isBefore(recovery.recoverableFrom())) {
            return AttemptResult.failure(decision.txnId(), key, cost, at,
                    "attempted before recovery was possible");
        }

        PaymentMethod effective = effectiveMethod(decision, txn);
        boolean railWorks = recovery.requiredMethod() == null
                || decision.action() == ActionType.SEND_PAYMENT_LINK
                || recovery.requiredMethod() == effective;
        if (!railWorks) {
            return AttemptResult.failure(decision.txnId(), key, cost, at,
                    "wrong rail: this decline can only be recovered on " + recovery.requiredMethod());
        }

        int qualifying = qualifyingAttempts.merge(decision.txnId(), 1, Integer::sum);
        if (qualifying < recovery.attemptsNeeded()) {
            return AttemptResult.failure(decision.txnId(), key, cost, at,
                    "right approach, but this decline takes " + recovery.attemptsNeeded()
                            + " attempts and this was attempt " + qualifying);
        }

        return AttemptResult.success(decision.txnId(), key, txn.amountPaise(), cost, at,
                "sim_" + key.substring(0, 8));
    }

    /**
     * Asks the gateway what became of an attempt that was presented but whose outcome
     * was never recorded, and returns it so the ledger can be completed.
     *
     * <p>This is a read, not a retry. It deliberately skips the duplicate check, because
     * the question is "what happened to the request I already sent", not "please take
     * this again". Against the real API it is a lookup of the payment link by its
     * reference_id.
     *
     * <p>Without this, a process that dies in that window leaves the transaction blocked
     * forever: the in-flight gate sees an attempt with no result and correctly refuses to
     * act, and nothing ever resolves it. The gate is right; the missing piece was
     * somebody to answer the question.
     */
    public AttemptResult reconcile(com.recoverx.ledger.LedgerEntry pending) {
        reconciled++;
        FailedTransaction.AgentView txn = transactions.get(pending.txnId());
        FailedTransaction.GroundTruth groundTruth = truth.get(pending.txnId());
        String key = pending.idempotencyKey();
        Instant at = pending.scheduledFor();

        if (txn == null || groundTruth == null) {
            return AttemptResult.failure(pending.txnId(), key, 0L, at, "reconciled: no such transaction");
        }
        RecoveryTruth recovery = groundTruth.truth();
        if (recovery.mustNotRetry()) {
            complianceViolations++;
            return AttemptResult.failure(pending.txnId(), key, 0L, at,
                            "reconciled: COMPLIANCE VIOLATION, instrument must never be re-presented")
                    .asComplianceViolation("reconciled: COMPLIANCE VIOLATION");
        }
        if (!recovery.recoverable() || at == null || at.isBefore(recovery.recoverableFrom())) {
            return AttemptResult.failure(pending.txnId(), key, 0L, at,
                    "reconciled: the attempt did not succeed");
        }
        boolean railWorks = recovery.requiredMethod() == null
                || pending.action() == ActionType.SEND_PAYMENT_LINK
                || recovery.requiredMethod() == pending.method();
        if (!railWorks) {
            return AttemptResult.failure(pending.txnId(), key, 0L, at,
                    "reconciled: the attempt used a rail that cannot work");
        }
        int qualifying = qualifyingAttempts.merge(pending.txnId(), 1, Integer::sum);
        if (qualifying < recovery.attemptsNeeded()) {
            return AttemptResult.failure(pending.txnId(), key, 0L, at,
                    "reconciled: the attempt did not succeed");
        }
        return AttemptResult.success(pending.txnId(), key, txn.amountPaise(), 0L, at,
                "sim_reconciled_" + key.substring(0, 8));
    }

    public int reconciledCount() {
        return reconciled;
    }

    @Override
    public String name() {
        return "simulated";
    }

    public int complianceViolations() {
        return complianceViolations;
    }

    public int replays() {
        return replays;
    }

    /** Repeats the local guard missed and the gateway caught. In a clean run this is zero. */
    public int duplicatesRejectedByGateway() {
        return duplicatesRejectedByGateway;
    }

    /**
     * A link leaves the choice to the customer, so it has no fixed rail. The other two
     * actions do, and getting it wrong is what makes an expired-card retry pointless.
     */
    private PaymentMethod effectiveMethod(RecoveryDecision decision, FailedTransaction.AgentView txn) {
        return switch (decision.action()) {
            case RETRY_SAME_METHOD -> txn.method();
            case RETRY_ALTERNATE_METHOD -> decision.method();
            case SEND_PAYMENT_LINK -> null;
            case ESCALATE_HUMAN, DO_NOTHING -> null;
        };
    }
}
