package com.recoverx.executor;

import com.recoverx.ledger.Ledger;
import com.recoverx.ledger.LedgerEntry;
import com.recoverx.policy.RecoveryDecision;

import java.util.Optional;

/**
 * The double-charge guard.
 *
 * <p>Before any executor calls out, this asks the ledger a single question: is there
 * already an outcome row for this idempotency key? If there is, the attempt has
 * already happened - possibly in a process that died before it could report - and the
 * recorded result is returned instead of a second call.
 *
 * <p>This works because the key is <em>derived</em> from (txnId, attemptNumber) rather
 * than generated per run. A restart recomputes the same key and finds the same row. A
 * random key would look new every time, which is precisely how resumes double-charge.
 *
 * <p>The guard is deliberately separate from any one executor: the simulated executor
 * and the Razorpay executor must not be able to differ on this.
 */
public class IdempotencyGuard {

    private final Ledger ledger;

    public IdempotencyGuard(Ledger ledger) {
        this.ledger = ledger;
    }

    /**
     * @return the already-recorded outcome for this decision's key, if one exists
     */
    public Optional<AttemptResult> previousOutcome(RecoveryDecision decision) {
        String key = decision.idempotencyKey();
        if (key == null) {
            return Optional.empty();
        }
        return ledger.forTxn(decision.txnId()).stream()
                .filter(entry -> entry.type() == LedgerEntry.EntryType.OUTCOME)
                .filter(entry -> key.equals(entry.idempotencyKey()))
                .findFirst()
                .map(entry -> new AttemptResult(
                        entry.txnId(),
                        entry.idempotencyKey(),
                        Boolean.TRUE.equals(entry.succeeded()),
                        entry.recoveredPaise(),
                        entry.costPaise(),
                        entry.recordedAt(),
                        null,
                        entry.justification(),
                        false,
                        false).asReplay());
    }
}
