package com.recoverx.eval;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.recoverx.domain.FailedTransaction;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the two dataset files back off disk.
 *
 * <p>The ground-truth loader lives here, in {@code eval}, and nowhere else. Nothing
 * in {@code classify}, {@code policy}, {@code executor}, or {@code ledger} may read
 * that file - which is checkable with one grep, and is checked by a test.
 */
public class DatasetLoader {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public List<FailedTransaction.AgentView> loadTransactions(Path dataDir) throws IOException {
        List<FailedTransaction.AgentView> out = new ArrayList<>();
        for (String line : readLines(dataDir.resolve("failed_transactions.jsonl"))) {
            out.add(mapper.readValue(line, FailedTransaction.AgentView.class));
        }
        return out;
    }

    public Map<String, FailedTransaction.GroundTruth> loadGroundTruth(Path dataDir) throws IOException {
        Map<String, FailedTransaction.GroundTruth> out = new LinkedHashMap<>();
        for (String line : readLines(dataDir.resolve("ground_truth.jsonl"))) {
            FailedTransaction.GroundTruth truth = mapper.readValue(line, FailedTransaction.GroundTruth.class);
            out.put(truth.txnId(), truth);
        }
        return out;
    }

    private List<String> readLines(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("missing " + path.toAbsolutePath()
                    + " - run with --generate first");
        }
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank())
                .toList();
    }
}
