package com.recoverx.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.FailureReason;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Disk cache for model verdicts, keyed by the content that produced them.
 *
 * <p>Re-running the eval is something you do twenty times while tuning a report.
 * Paying for 500 classifications each time is avoidable, and a cached run is also
 * reproducible, which matters more: the numbers in the pitch deck should not move
 * because the model was sampled again.
 *
 * <p>The key includes the prompt version, so editing the prompt correctly misses the
 * cache instead of silently reporting stale verdicts.
 */
public class ClassificationCache {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path path;
    private final Map<String, Classification> entries = new HashMap<>();

    public ClassificationCache(Path path) throws IOException {
        this.path = path;
        Files.createDirectories(path.getParent());
        if (Files.exists(path)) {
            load();
        }
    }

    public static String key(String promptVersion, FailedTransaction.AgentView txn) {
        String material = promptVersion + "|" + txn.txnId() + "|" + txn.errorCode()
                + "|" + txn.rawGatewayError() + "|" + txn.method();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    public Classification get(String key) {
        return entries.get(key);
    }

    public boolean has(String key) {
        return entries.containsKey(key);
    }

    public void put(String key, Classification classification) throws IOException {
        entries.put(key, classification);
        ObjectNode node = mapper.createObjectNode();
        node.put("key", key);
        node.put("reason", classification.reason() == null ? null : classification.reason().name());
        node.put("confidence", classification.confidence());
        node.put("justification", classification.justification());
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(mapper.writeValueAsString(node));
            writer.newLine();
        }
    }

    public int size() {
        return entries.size();
    }

    private void load() throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = mapper.readTree(line);
            String reason = node.get("reason").isNull() ? null : node.get("reason").asText();
            entries.put(node.get("key").asText(), reason == null
                    ? Classification.rejected(node.get("justification").asText())
                    : Classification.of(FailureReason.valueOf(reason),
                    node.get("confidence").asDouble(),
                    node.get("justification").asText()));
        }
    }
}
