package com.recoverx;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the claim the whole submission rests on: the agent never sees the answer.
 *
 * <p>Only {@code com.recoverx.datagen} (which writes it) and {@code com.recoverx.eval}
 * (which scores against it) may touch the ground-truth file or the truth fields.
 * If a future change reads either from the classify, policy, executor, or ledger
 * packages, this fails - which is cheaper than a judge finding it.
 */
class GroundTruthIsolationTest {

    private static final List<String> AGENT_PACKAGES =
            List.of("classify", "policy", "executor", "ledger");

    private static final List<String> FORBIDDEN =
            List.of("ground_truth", "groundTruth", "trueReason", "RecoveryTruth", "mustNotRetry");

    @Test
    void agentPackagesNeverReferenceGroundTruth() throws IOException {
        for (String pkg : AGENT_PACKAGES) {
            Path dir = Path.of("src/main/java/com/recoverx", pkg);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    for (String needle : FORBIDDEN) {
                        assertTrue(!source.contains(needle),
                                file + " references \"" + needle + "\"; only datagen and eval may.");
                    }
                }
            }
        }
    }
}
