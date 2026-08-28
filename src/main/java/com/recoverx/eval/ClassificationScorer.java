package com.recoverx.eval;

import com.recoverx.classify.Classification;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.FailureReason;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Scores one classifier arm against the hidden truth.
 *
 * <p>Two decisions here decide whether the reported numbers mean anything:
 *
 * <p><b>A rejection counts against accuracy.</b> Headline accuracy is
 * {@code correct / total}, where declining to answer is not correct. Reporting only
 * accuracy-over-answered would let a classifier that rejects 90% of rows post a
 * beautiful number, so both figures are always shown side by side.
 *
 * <p><b>A rejection is a recall miss, not a precision hit.</b> Per bucket, a rejected
 * row whose truth is that bucket is a false negative, but it is never a false
 * positive for any bucket. That matches what actually happens: the transaction goes
 * to a person, so nothing wrong was done - the recovery was just not automated.
 */
public class ClassificationScorer {

    public record BucketMetrics(
            FailureReason reason,
            int support,
            int truePositives,
            int falsePositives,
            int falseNegatives
    ) {
        public double precision() {
            int predicted = truePositives + falsePositives;
            return predicted == 0 ? 0.0 : (double) truePositives / predicted;
        }

        public double recall() {
            return support == 0 ? 0.0 : (double) truePositives / support;
        }

        public double f1() {
            double p = precision();
            double r = recall();
            return p + r == 0 ? 0.0 : 2 * p * r / (p + r);
        }
    }

    /**
     * @param riskMissedToActionable truth was RISK_BLOCKED but the arm named a different,
     *                               actionable bucket. The dangerous miss: the policy engine
     *                               would have been handed a retryable verdict on a
     *                               compliance stop. Target zero.
     * @param riskMissedToHuman      truth was RISK_BLOCKED and the arm declined to answer.
     *                               Not dangerous - a person sees it - but still a miss.
     */
    public record ArmMetrics(
            String arm,
            String slice,
            int total,
            int accepted,
            int rejected,
            int correct,
            int riskMissedToActionable,
            int riskMissedToHuman,
            List<BucketMetrics> buckets,
            Map<FailureReason, Map<FailureReason, Integer>> confusion
    ) {
        /** Rejections count as wrong. This is the headline. */
        public double accuracy() {
            return total == 0 ? 0.0 : (double) correct / total;
        }

        /** Quality when the arm was willing to answer. Meaningless without coverage. */
        public double accuracyOnAccepted() {
            return accepted == 0 ? 0.0 : (double) correct / accepted;
        }

        public double coverage() {
            return total == 0 ? 0.0 : (double) accepted / total;
        }

        public double macroF1() {
            return buckets.stream().mapToDouble(BucketMetrics::f1).average().orElse(0.0);
        }
    }

    public ArmMetrics score(String arm,
                            String slice,
                            List<FailedTransaction.AgentView> transactions,
                            Map<String, FailedTransaction.GroundTruth> truth,
                            Map<String, Classification> predictions,
                            Predicate<FailedTransaction.GroundTruth> include) {

        Map<FailureReason, int[]> counts = new EnumMap<>(FailureReason.class);
        Map<FailureReason, Map<FailureReason, Integer>> confusion = new EnumMap<>(FailureReason.class);
        for (FailureReason reason : FailureReason.values()) {
            counts.put(reason, new int[3]); // tp, fp, fn
            confusion.put(reason, new EnumMap<>(FailureReason.class));
        }

        int total = 0;
        int accepted = 0;
        int correct = 0;
        int riskMissedToActionable = 0;
        int riskMissedToHuman = 0;
        Map<FailureReason, Integer> support = new EnumMap<>(FailureReason.class);

        for (FailedTransaction.AgentView txn : transactions) {
            FailedTransaction.GroundTruth groundTruth = truth.get(txn.txnId());
            if (groundTruth == null || !include.test(groundTruth)) {
                continue;
            }
            total++;
            FailureReason actual = groundTruth.trueReason();
            support.merge(actual, 1, Integer::sum);

            Classification predicted = predictions.get(txn.txnId());
            if (predicted == null || !predicted.accepted()) {
                counts.get(actual)[2]++; // false negative for the true bucket
                if (actual == FailureReason.RISK_BLOCKED) {
                    riskMissedToHuman++;
                }
                continue;
            }

            accepted++;
            FailureReason guess = predicted.reason();
            confusion.get(actual).merge(guess, 1, Integer::sum);

            if (guess == actual) {
                correct++;
                counts.get(actual)[0]++;
            } else {
                counts.get(guess)[1]++;
                counts.get(actual)[2]++;
                if (actual == FailureReason.RISK_BLOCKED) {
                    riskMissedToActionable++;
                }
            }
        }

        List<BucketMetrics> buckets = List.of(FailureReason.values()).stream()
                .map(reason -> {
                    int[] c = counts.get(reason);
                    return new BucketMetrics(reason, support.getOrDefault(reason, 0), c[0], c[1], c[2]);
                })
                .toList();

        return new ArmMetrics(arm, slice, total, accepted, total - accepted, correct,
                riskMissedToActionable, riskMissedToHuman, buckets, confusion);
    }
}
