package com.recoverx.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.recoverx.executor.AttemptResult;
import com.recoverx.policy.PolicyConfig;
import com.recoverx.policy.RecoveryDecision;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Append-only ledger backed by a JSONL file.
 *
 * <p>Append-only is load-bearing, not stylistic. The policy engine's stopping rules
 * are answered by counting rows, so a row that could be updated or removed would be a
 * way to talk the engine past its own ceiling. Every write goes to the end of the file
 * and the in-memory index is derived from what was written, never the reverse.
 *
 * <p>The file is also what the eval harness reads to score a run, and what the
 * dashboard renders. One artifact, three readers.
 */
public class JsonlLedger implements Ledger {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path path;
    private final Clock clock;
    private final List<LedgerEntry> entries = new ArrayList<>();
    private final Map<String, List<LedgerEntry>> byTxn = new HashMap<>();

    public JsonlLedger(Path path, Clock clock) throws IOException {
        this.path = path;
        this.clock = clock;
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        if (Files.exists(path)) {
            reload();
        }
    }

    /**
     * Rebuilds the index from the file on startup.
     *
     * <p>This is what makes a resume safe. A process that died mid-batch left its
     * outcome rows on disk; a fresh process that started with an empty index would
     * recompute the same idempotency keys, find nothing, and attempt every one of them
     * again. Reading the file back is the difference between a resume and a
     * double-charge.
     *
     * <p>A truncated final line - the normal shape of a process killed mid-write - is
     * dropped rather than fatal. The row was never completed, so the attempt it would
     * have described has no outcome, and the policy engine correctly sees it as still
     * in flight.
     */
    private void reload() throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                index(mapper.readValue(line, LedgerEntry.class));
            } catch (IOException e) {
                if (i == lines.size() - 1) {
                    System.err.println("ledger: dropping a truncated final row (process died mid-write)");
                    continue;
                }
                throw new IOException("corrupt ledger row at line " + (i + 1), e);
            }
        }
    }

    @Override
    public LedgerEntry recordDecision(RecoveryDecision decision) {
        return append(new LedgerEntry(
                entries.size() + 1L,
                clock.instant(),
                LedgerEntry.EntryType.DECISION,
                decision.txnId(),
                decision.classifiedReason(),
                decision.confidence(),
                decision.action(),
                decision.attemptNumber(),
                decision.scheduledFor(),
                decision.method(),
                decision.justification(),
                decision.gateReason(),
                decision.idempotencyKey(),
                null,
                0L,
                decision.costPaise(),
                false,
                false));
    }

    @Override
    public LedgerEntry recordOutcome(AttemptResult result) {
        return append(new LedgerEntry(
                entries.size() + 1L,
                clock.instant(),
                LedgerEntry.EntryType.OUTCOME,
                result.txnId(),
                null,
                0.0,
                null,
                0,
                null,
                null,
                result.failureNote() == null ? "attempt succeeded" : result.failureNote(),
                "outcome recorded for key " + result.idempotencyKey(),
                result.idempotencyKey(),
                result.succeeded(),
                result.recoveredPaise(),
                result.costPaise(),
                result.replayed(),
                result.complianceViolation()));
    }

    @Override
    public List<LedgerEntry> forTxn(String txnId) {
        return List.copyOf(byTxn.getOrDefault(txnId, List.of()));
    }

    @Override
    public int attemptCount(String txnId) {
        return (int) byTxn.getOrDefault(txnId, List.of()).stream()
                .filter(entry -> entry.type() == LedgerEntry.EntryType.DECISION)
                .filter(entry -> PolicyConfig.movesMoney(entry.action()))
                .count();
    }

    @Override
    public boolean hasPendingAttempt(String txnId) {
        List<LedgerEntry> rows = byTxn.getOrDefault(txnId, List.of());
        Set<String> settled = new HashSet<>();
        for (LedgerEntry entry : rows) {
            if (entry.type() == LedgerEntry.EntryType.OUTCOME && entry.idempotencyKey() != null) {
                settled.add(entry.idempotencyKey());
            }
        }
        return rows.stream()
                .filter(entry -> entry.type() == LedgerEntry.EntryType.DECISION)
                .filter(entry -> PolicyConfig.movesMoney(entry.action()))
                .anyMatch(entry -> !settled.contains(entry.idempotencyKey()));
    }

    @Override
    public List<LedgerEntry> pendingAttempts() {
        Set<String> settled = new HashSet<>();
        for (LedgerEntry entry : entries) {
            if (entry.type() == LedgerEntry.EntryType.OUTCOME && entry.idempotencyKey() != null) {
                settled.add(entry.idempotencyKey());
            }
        }
        return entries.stream()
                .filter(entry -> entry.type() == LedgerEntry.EntryType.DECISION)
                .filter(entry -> PolicyConfig.movesMoney(entry.action()))
                .filter(entry -> !settled.contains(entry.idempotencyKey()))
                .toList();
    }

    @Override
    public List<LedgerEntry> all() {
        return List.copyOf(entries);
    }

    private void index(LedgerEntry entry) {
        entries.add(entry);
        byTxn.computeIfAbsent(entry.txnId(), key -> new ArrayList<>()).add(entry);
    }

    private LedgerEntry append(LedgerEntry entry) {
        index(entry);
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(mapper.writeValueAsString(entry));
            writer.newLine();
        } catch (IOException e) {
            // A decision that is not durably recorded has not happened. Failing loudly
            // here is correct: a silent ledger write failure would mean money moving
            // with no audit row behind it.
            throw new UncheckedIOException("ledger append failed for " + entry.txnId(), e);
        }
        return entry;
    }
}
