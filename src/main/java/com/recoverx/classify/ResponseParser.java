package com.recoverx.classify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recoverx.domain.FailedTransaction;
import com.recoverx.domain.FailureReason;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates model output before anything downstream is allowed to believe it.
 *
 * <p>The rule is single and absolute: <b>anything that does not validate becomes a
 * rejected classification.</b> A rejected classification routes the transaction to a
 * human. There is no branch anywhere in this class that lets a malformed response
 * reach the executor.
 *
 * <p>What it catches:
 * <ul>
 *   <li>output that is not JSON at all, or is wrapped in a markdown fence</li>
 *   <li>a bucket name that is not in the enum</li>
 *   <li>a confidence that is missing, non-numeric, or outside 0..1</li>
 *   <li>an empty justification - an audit row with no reason is not an audit row</li>
 *   <li>a transaction id that was never in the batch (a hallucinated row)</li>
 *   <li>a transaction in the batch that got no line back at all</li>
 * </ul>
 *
 * <p>This class is deliberately free of any SDK dependency so the failure modes can be
 * unit tested without a network call or an API key.
 */
public class ResponseParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, Classification> parse(String rawResponse, List<FailedTransaction.AgentView> batch) {
        Set<String> expected = new LinkedHashSet<>();
        for (FailedTransaction.AgentView txn : batch) {
            expected.add(txn.txnId());
        }

        Map<String, Classification> results = new HashMap<>();
        Set<String> seen = new HashSet<>();

        for (String line : stripFence(rawResponse).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            JsonNode node;
            try {
                node = mapper.readTree(trimmed);
            } catch (Exception e) {
                continue; // Not JSON. The affected transactions are caught by the sweep below.
            }
            if (node == null || !node.isObject() || !node.hasNonNull("txn_id")) {
                continue;
            }

            String txnId = node.get("txn_id").asText();
            if (!expected.contains(txnId) || !seen.add(txnId)) {
                continue; // Hallucinated id, or a duplicate line. First valid line wins.
            }
            results.put(txnId, validate(node, txnId));
        }

        for (String txnId : expected) {
            results.putIfAbsent(txnId, Classification.rejected(
                    "model returned no line for this transaction; routed to a human"));
        }
        return results;
    }

    private Classification validate(JsonNode node, String txnId) {
        if (!node.hasNonNull("reason")) {
            return Classification.rejected("response for " + txnId + " has no reason; routed to a human");
        }
        FailureReason reason;
        try {
            reason = FailureReason.valueOf(node.get("reason").asText().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Classification.rejected("unknown bucket \"" + node.get("reason").asText()
                    + "\" for " + txnId + "; routed to a human");
        }

        JsonNode confidenceNode = node.get("confidence");
        if (confidenceNode == null || !confidenceNode.isNumber()) {
            return Classification.rejected("confidence missing or non-numeric for " + txnId
                    + "; routed to a human");
        }
        double confidence = confidenceNode.asDouble();
        if (confidence < 0.0 || confidence > 1.0 || Double.isNaN(confidence)) {
            return Classification.rejected("confidence " + confidence + " out of range for " + txnId
                    + "; routed to a human");
        }

        JsonNode justificationNode = node.get("justification");
        if (justificationNode == null || justificationNode.asText().isBlank()) {
            return Classification.rejected("no justification for " + txnId
                    + "; an audit row without a reason is not an audit row");
        }

        return Classification.of(reason, confidence, justificationNode.asText().trim());
    }

    /** Models sometimes wrap JSONL in a fence despite being told not to. Cheap to survive. */
    private String stripFence(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        if (firstNewline < 0) {
            return "";
        }
        String body = text.substring(firstNewline + 1);
        int fenceEnd = body.lastIndexOf("```");
        return fenceEnd < 0 ? body : body.substring(0, fenceEnd);
    }
}
