package com.recoverx.dashboard;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.recoverx.ledger.LedgerEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves what the runs already wrote to disk.
 *
 * <p>The dashboard deliberately computes nothing. Every figure it shows was produced
 * by a run and lives in a ledger file, so what a judge sees on screen is the same
 * artifact they can open in a text editor. A dashboard that recalculated its own
 * numbers would be a second implementation to keep honest.
 */
@RestController
public class DashboardController {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path outDir = Path.of(System.getProperty("recoverx.out", "eval/out"));

    /**
     * Serves the run's own summary file verbatim. The dashboard shows what
     * {@code --compare} computed; it does not recompute it, because a second
     * implementation of the arithmetic is a second thing that can be wrong.
     */
    @GetMapping("/api/arms")
    public ResponseEntity<?> arms() throws IOException {
        Path summary = outDir.resolve("comparison.json");
        if (!Files.exists(summary)) {
            return ResponseEntity.ok(Map.of("arms", List.of(), "hint",
                    "run --compare first; nothing at " + summary.toAbsolutePath()));
        }
        return ResponseEntity.ok(mapper.readValue(Files.readString(summary, StandardCharsets.UTF_8), Map.class));
    }

    /**
     * The audit trail itself. Decision rows only by default - they carry the classifier's
     * reason, the model's own justification, and the gate that allowed or blocked the
     * action, which is the part worth putting on screen.
     */
    @GetMapping("/api/ledger")
    public ResponseEntity<?> ledger(@RequestParam(defaultValue = "recoverx-rules") String arm,
                                    @RequestParam(defaultValue = "200") int limit,
                                    @RequestParam(defaultValue = "") String action) throws IOException {
        Path path = outDir.resolve("arms").resolve(arm).resolve("ledger.jsonl");
        if (!Files.exists(path)) {
            return ResponseEntity.ok(Map.of("rows", List.of(), "total", 0));
        }

        List<LedgerEntry> rows = readLedger(path).stream()
                .filter(e -> e.type() == LedgerEntry.EntryType.DECISION)
                .filter(e -> action.isBlank() || action.equals(String.valueOf(e.action())))
                .toList();

        return ResponseEntity.ok(Map.of(
                "total", rows.size(),
                "rows", rows.stream().limit(Math.max(1, limit)).map(this::rowView).toList()));
    }

    /** The exception queue: everything the agent handed to a person, and why. */
    @GetMapping("/api/exceptions")
    public ResponseEntity<?> exceptions(@RequestParam(defaultValue = "recoverx-rules") String arm)
            throws IOException {
        Path path = outDir.resolve("arms").resolve(arm).resolve("ledger.jsonl");
        if (!Files.exists(path)) {
            return ResponseEntity.ok(Map.of("rows", List.of(), "total", 0));
        }

        List<Map<String, Object>> rows = readLedger(path).stream()
                .filter(e -> e.type() == LedgerEntry.EntryType.DECISION)
                .filter(e -> e.action() == com.recoverx.policy.ActionType.ESCALATE_HUMAN
                        || e.action() == com.recoverx.policy.ActionType.DO_NOTHING)
                .sorted(Comparator.comparing(LedgerEntry::gateReason))
                .map(this::rowView)
                .toList();

        return ResponseEntity.ok(Map.of("total", rows.size(), "rows", rows));
    }

    // ---------- internals ----------

    private List<LedgerEntry> readLedger(Path path) throws IOException {
        List<LedgerEntry> rows = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                try {
                    rows.add(mapper.readValue(line, LedgerEntry.class));
                } catch (IOException e) {
                    // A truncated final row is the normal shape of a killed process.
                }
            }
        }
        return rows;
    }

    private Map<String, Object> rowView(LedgerEntry entry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sequence", entry.sequence());
        out.put("txnId", entry.txnId());
        out.put("reason", String.valueOf(entry.classifiedReason()));
        out.put("confidence", entry.confidence());
        out.put("action", String.valueOf(entry.action()));
        out.put("attemptNumber", entry.attemptNumber());
        out.put("scheduledFor", String.valueOf(entry.scheduledFor()));
        out.put("justification", entry.justification());
        out.put("gateReason", entry.gateReason());
        out.put("idempotencyKey", entry.idempotencyKey());
        return out;
    }
}
