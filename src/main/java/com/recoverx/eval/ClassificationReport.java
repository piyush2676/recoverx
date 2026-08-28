package com.recoverx.eval;

import com.recoverx.domain.FailureReason;
import com.recoverx.eval.ClassificationScorer.ArmMetrics;
import com.recoverx.eval.ClassificationScorer.BucketMetrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Writes the head-to-head report. Every arm is reported on the same three slices,
 * and no figure appears without the one that makes it honest: accuracy sits next to
 * coverage, and the risk-bucket misses get their own section rather than being
 * averaged into a macro score where they would vanish.
 */
public class ClassificationReport {

    public void write(Path out, List<ArmMetrics> metrics, String notes) throws IOException {
        Files.createDirectories(out.getParent());

        StringBuilder sb = new StringBuilder();
        sb.append("# Classification report\n\n")
                .append("Arms scored against `data/ground_truth.jsonl`. ")
                .append("Rejections count as wrong in the headline accuracy column.\n\n");

        sb.append("## Headline\n\n")
                .append("| Arm | Slice | n | Accuracy | Coverage | Accuracy when answered | Macro F1 |\n")
                .append("|---|---|---:|---:|---:|---:|---:|\n");
        for (ArmMetrics arm : metrics) {
            sb.append("| ").append(arm.arm()).append(" | ").append(arm.slice()).append(" | ")
                    .append(arm.total()).append(" | ").append(pct(arm.accuracy())).append(" | ")
                    .append(pct(arm.coverage())).append(" | ").append(pct(arm.accuracyOnAccepted()))
                    .append(" | ").append(fixed(arm.macroF1())).append(" |\n");
        }

        sb.append("\nAccuracy counts a declined row as wrong. Coverage is the share of rows the arm\n")
                .append("was willing to answer at all. An arm that rejects everything scores 0% accuracy\n")
                .append("and 0% coverage, which is the correct reading of that behaviour.\n\n");

        sb.append("## Safety: the RISK_BLOCKED bucket\n\n")
                .append("A risk-blocked decline must never reach the policy engine labelled as something\n")
                .append("retryable. Landing in the human queue instead is a miss, but a safe one.\n\n")
                .append("| Arm | Slice | Missed to an actionable bucket | Missed to a human |\n")
                .append("|---|---|---:|---:|\n");
        for (ArmMetrics arm : metrics) {
            sb.append("| ").append(arm.arm()).append(" | ").append(arm.slice()).append(" | ")
                    .append(arm.riskMissedToActionable()).append(" | ")
                    .append(arm.riskMissedToHuman()).append(" |\n");
        }
        sb.append("\nTarget for the first column is zero.\n\n");

        for (ArmMetrics arm : metrics) {
            sb.append("## ").append(arm.arm()).append(" - ").append(arm.slice()).append("\n\n")
                    .append("| Bucket | Support | Precision | Recall | F1 |\n")
                    .append("|---|---:|---:|---:|---:|\n");
            for (BucketMetrics bucket : arm.buckets()) {
                if (bucket.support() == 0) {
                    continue;
                }
                sb.append("| ").append(bucket.reason()).append(" | ").append(bucket.support())
                        .append(" | ").append(fixed(bucket.precision()))
                        .append(" | ").append(fixed(bucket.recall()))
                        .append(" | ").append(fixed(bucket.f1())).append(" |\n");
            }
            sb.append('\n').append(confusion(arm)).append('\n');
        }

        if (notes != null && !notes.isBlank()) {
            sb.append("## Run notes\n\n").append(notes).append('\n');
        }

        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    }

    private String confusion(ArmMetrics arm) {
        StringBuilder sb = new StringBuilder("Confusion (rows = truth, columns = predicted; ")
                .append("declined rows are excluded and counted under coverage):\n\n```\n");

        List<FailureReason> reasons = List.of(FailureReason.values());
        sb.append(String.format("%-24s", "truth \\ predicted"));
        for (FailureReason reason : reasons) {
            sb.append(String.format("%6s", abbreviate(reason)));
        }
        sb.append('\n');

        for (FailureReason actual : reasons) {
            Map<FailureReason, Integer> row = arm.confusion().get(actual);
            sb.append(String.format("%-24s", actual));
            for (FailureReason predicted : reasons) {
                int value = row.getOrDefault(predicted, 0);
                sb.append(String.format("%6s", value == 0 ? "." : String.valueOf(value)));
            }
            sb.append('\n');
        }
        return sb.append("```\n").toString();
    }

    private String abbreviate(FailureReason reason) {
        return switch (reason) {
            case INSUFFICIENT_FUNDS -> "NSF";
            case ISSUER_DOWN -> "DOWN";
            case EXPIRED_CARD -> "EXP";
            case AUTHENTICATION_FAILED -> "AUTH";
            case NETWORK_TIMEOUT -> "TMO";
            case INVALID_VPA -> "VPA";
            case LIMIT_EXCEEDED -> "LIM";
            case RISK_BLOCKED -> "RISK";
        };
    }

    private String pct(double value) {
        return String.format("%.1f%%", value * 100);
    }

    private String fixed(double value) {
        return String.format("%.3f", value);
    }
}
