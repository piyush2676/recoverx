package com.recoverx.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.recoverx.policy.ActionType;
import com.recoverx.policy.RecoveryDecision;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * Carries out a decision against Razorpay <b>test mode</b>.
 *
 * <h2>Why every action becomes a payment link</h2>
 *
 * <p>A merchant in India cannot silently re-charge a customer's card or UPI handle.
 * Under the RBI's rules a merchant-initiated debit needs a standing e-mandate that the
 * customer set up in advance; without one, the customer has to authorise each payment.
 * So {@code RETRY_SAME_METHOD} does not mean "quietly charge them again" - against the
 * real API it means "present the payment again for the customer to approve", which is
 * a payment link.
 *
 * <p>This is stated here rather than buried because it changes what the policy engine's
 * actions actually cost. A silent retry is free and invisible; a link costs money to
 * send and asks something of the customer. The policy's cost model already charges for
 * links, and this executor is where the two meet. A merchant with e-mandates on file
 * would add a mandate-token path here; the synthetic customers have none, so there is
 * no such path in this repo.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Two layers. {@link IdempotencyGuard} checks the ledger first and skips the call
 * entirely if this key already has an outcome. Beyond that, the decision's key is sent
 * as the link's {@code reference_id}, which Razorpay requires to be unique per link -
 * so a duplicate that somehow got past the local guard is rejected at the far end too.
 *
 * <p>Raw HTTP rather than the SDK: the request shapes below are the documented REST
 * contract and do not drift with an SDK version, and this class has no credentials to
 * be integration-tested against in CI.
 */
public class RazorpayExecutor implements RecoveryExecutor {

    private static final String PAYMENT_LINKS = "https://api.razorpay.com/v1/payment_links";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String keyId;
    private final String keySecret;
    private final Clock clock;
    private final IdempotencyGuard guard;

    public RazorpayExecutor(String keyId, String keySecret, Clock clock, IdempotencyGuard guard) {
        if (keyId != null && !keyId.isBlank() && !keyId.startsWith("rzp_test_")) {
            // Refuse live keys outright. This code creates payment requests; pointing it
            // at a live account by accident is not a mistake worth being recoverable from.
            throw new IllegalArgumentException(
                    "RazorpayExecutor accepts test-mode keys only (expected an rzp_test_ prefix)");
        }
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.clock = clock;
        this.guard = guard;
    }

    public static boolean credentialsAvailable() {
        String id = System.getenv("RAZORPAY_KEY_ID");
        String secret = System.getenv("RAZORPAY_KEY_SECRET");
        return id != null && !id.isBlank() && secret != null && !secret.isBlank();
    }

    public static RazorpayExecutor fromEnv(Clock clock, IdempotencyGuard guard) {
        return new RazorpayExecutor(System.getenv("RAZORPAY_KEY_ID"),
                System.getenv("RAZORPAY_KEY_SECRET"), clock, guard);
    }

    @Override
    public AttemptResult execute(RecoveryDecision decision) {
        Optional<AttemptResult> alreadyDone = guard.previousOutcome(decision);
        if (alreadyDone.isPresent()) {
            return alreadyDone.get();
        }

        String key = decision.idempotencyKey();
        if (key == null) {
            throw new IllegalArgumentException(
                    "decision " + decision.txnId() + " does not move money and must not be executed");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(PAYMENT_LINKS))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", basicAuth())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(decision), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                JsonNode node = mapper.readTree(response.body());
                // The link is created, not paid. Recovery is confirmed by the webhook that
                // fires when the customer pays; recording a rupee here would be recording
                // money we have not received.
                return new AttemptResult(decision.txnId(), key, false, 0L, decision.costPaise(),
                        clock.instant(), node.path("id").asText(null),
                        "payment link created; awaiting customer payment", false, false);
            }

            // A duplicate reference_id lands here. That means the far end caught a repeat
            // the local guard missed, which is a bug worth seeing in the trail, not a retry.
            return AttemptResult.failure(decision.txnId(), key, decision.costPaise(),
                    clock.instant(), "razorpay returned " + response.statusCode() + ": "
                            + firstLine(response.body()));

        } catch (java.io.IOException e) {
            return AttemptResult.failure(decision.txnId(), key, 0L, clock.instant(),
                    "transport failure: " + e.getClass().getSimpleName() + " " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AttemptResult.failure(decision.txnId(), key, 0L, clock.instant(),
                    "interrupted while calling Razorpay");
        }
    }

    @Override
    public String name() {
        return "razorpay-test";
    }

    private String body(RecoveryDecision decision) {
        ObjectNode root = mapper.createObjectNode();
        root.put("amount", amountPaiseFor(decision));
        root.put("currency", "INR");
        root.put("accept_partial", false);
        // Razorpay requires reference_id to be unique per link, which makes our derived
        // key a server-side duplicate check as well as a local one.
        root.put("reference_id", decision.idempotencyKey());
        root.put("description", "Payment retry for " + decision.txnId());
        root.put("reminder_enable", true);

        ObjectNode notes = root.putObject("notes");
        notes.put("recoverx_txn_id", decision.txnId());
        notes.put("recoverx_attempt", String.valueOf(decision.attemptNumber()));
        notes.put("recoverx_reason", String.valueOf(decision.classifiedReason()));
        notes.put("recoverx_action", String.valueOf(decision.action()));
        return root.toString();
    }

    /**
     * The decision carries the cost of attempting, not the order value. The amount to
     * collect lives on the original transaction, so callers set it here.
     */
    private long amountPaiseFor(RecoveryDecision decision) {
        return decision.amountPaise();
    }

    private String basicAuth() {
        String raw = keyId + ":" + keySecret;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String firstLine(String body) {
        if (body == null) {
            return "";
        }
        int newline = body.indexOf('\n');
        String head = newline < 0 ? body : body.substring(0, newline);
        return head.length() > 200 ? head.substring(0, 200) : head;
    }

    /** Kept explicit so the enum cannot gain a case that silently falls through to a charge. */
    static boolean isExecutable(ActionType action) {
        return switch (action) {
            case RETRY_SAME_METHOD, RETRY_ALTERNATE_METHOD, SEND_PAYMENT_LINK -> true;
            case ESCALATE_HUMAN, DO_NOTHING -> false;
        };
    }
}
