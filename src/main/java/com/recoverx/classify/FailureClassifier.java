package com.recoverx.classify;

import com.recoverx.domain.FailedTransaction;

/**
 * Turns a messy gateway error string plus customer context into a failure bucket.
 *
 * Two implementations are planned:
 *  - RuleBasedClassifier: regex over error codes. Cheap, deterministic, the baseline.
 *  - LlmClassifier: Claude over the raw string. Handles forms the regex has never seen.
 *
 * Report both in the pitch. "The LLM beat my regex by N points on the same 500 rows"
 * is a measurement; "we used AI" is not.
 */
public interface FailureClassifier {

    Classification classify(FailedTransaction.AgentView txn);

    String name();
}
