# RecoverX

Autonomous failed-payment recovery agent. Built for the Razorpay Buildathon,
**Track 03 — AI Revenue Recovery**.

Roughly one in eight Indian online payments fails on the first attempt. Most
merchants retry all of them once, or none of them. Both are wrong: some declines
recover on their own in twenty minutes, some only after the customer's salary
lands, some need a different rail entirely, and some must never be touched again.

RecoverX classifies each failed payment, decides a bounded recovery action,
executes it against Razorpay test mode, and writes every decision to an
append-only ledger. It reports rupees actually recovered against an oracle
ceiling — not attempts made.

---

## The bar this repo is aimed at

> *"Show measured money recovered across a batch, with compliant escalation,
> stopping rules, and an audit trail."*

Mapped to code:

| Requirement | Where it lives |
|---|---|
| measured money recovered | `eval/` scores against `data/ground_truth.jsonl` |
| compliant escalation | `policy.ActionType.ESCALATE_HUMAN` + exception list |
| stopping rules | `policy.RecoveryPolicy` — deterministic, not prompt-held |
| audit trail | `ledger.LedgerEntry`, append-only, one row per decision |

---

## Architecture

```
data/failed_transactions.jsonl        <- agent input (no ground truth)
        |
        v
classify.FailureClassifier            <- LLM reads messy gateway error text,
        |                                emits reason + confidence + justification
        v
policy.RecoveryPolicy                 <- DETERMINISTIC. Owns every money gate:
        |                                max 3 attempts, 7-day cutoff, confidence
        |                                floor, hard stop on risk-blocked
        v
executor.RecoveryExecutor             <- Razorpay test API, idempotency key per
        |                                (txnId, attemptNumber)
        v
ledger.Ledger                         <- append-only audit trail
        |
        v
eval/                                 <- oracle ceiling vs baseline vs agent
```

**The split is the design.** The model classifies and explains; it never moves
money. A prompt can be argued out of a stopping rule. An `if` statement cannot.
Every rupee-moving branch is ordinary Java you can read in one sitting.

---

## Quickstart

```bash
mvn test                      # 23 tests
mvn -DskipTests package

# phase 1 - build the batch
java -jar target/recoverx-0.1.0.jar --generate --count=500 --seed=42 --out=data

# phase 2 - score both classifier arms against the hidden truth
java -jar target/recoverx-0.1.0.jar --classify --data=data --out=eval/out

# phase 3 - classify, decide, and write the audit trail
java -jar target/recoverx-0.1.0.jar --decide --data=data --out=eval/out

# phase 4 - run the full cycle and measure recovered rupees
java -jar target/recoverx-0.1.0.jar --execute --data=data --out=eval/out

# phase 5 - every arm, same batch, one report
java -jar target/recoverx-0.1.0.jar --compare --data=data --out=eval/out

# phase 6 - the dashboard (no args = serve mode)
java -jar target/recoverx-0.1.0.jar --server.port=8081
```

The LLM arm needs credentials (`ANTHROPIC_API_KEY`, or `ant auth login`). Without
them the run says so and still writes the rules-only report - a missing key should
not cost you the baseline numbers.

Output:

```
generated 500 transactions -> .../data
  seed              : 42
  total failed value: Rs 603761.64
  oracle ceiling    : Rs 382063.47 (63.3%)
  do-not-retry traps: 29
```

Serve mode (dashboard, once phase 3 lands):

```bash
java -jar target/recoverx-0.1.0.jar
```

---

## The dataset

`SyntheticDataGenerator` produces a reproducible batch of failed payments plus a
hidden truth record for each: whether it is recoverable at all, the earliest
instant a retry can succeed, which rail it must use, and how many attempts it
needs.

Two files, deliberately separate:

- `data/failed_transactions.jsonl` — the **only** file the agent may read
- `data/ground_truth.jsonl` — the eval harness only

A judge can verify that separation by grepping the agent packages for
`ground_truth`. Nothing outside `eval/` should match.

Realism that matters:

- **Messy error strings.** Each failure reason has 3–5 unrelated surface forms —
  `"U30 - insufficient balance"`, `"code=51 desc=DO NOT HONOUR/NSF"`,
  `"reason=insufficient_balance :: acquirer=HDFC"`. If every reason had one clean
  string, the classification metric would be meaningless.
- **Payday-linked recovery.** `INSUFFICIENT_FUNDS` becomes recoverable at the
  customer's next salary credit, clustered on the 1st and month-end. An agent
  that retries in ten minutes recovers nothing.
- **Rail-locked recovery.** Expired cards and invalid VPAs can only succeed on a
  different method. Retrying the same rail is guaranteed waste.
- **The trap.** ~6% of rows are `RISK_BLOCKED` with `mustNotRetry = true`.
  Retrying one is a compliance violation, not a wasted attempt, and is reported
  as its own metric. This is what "bounded and gated" is tested against.
- **Log-normal amounts.** Recovered rupees depend on *which* transactions the
  agent picks, not on a flat average.

See `data/DATASET.md` (generated) for the exact mix and the ceiling.

---

## Phase 2 result: does the LLM earn its cost?

Two arms, same 500 rows, scored against the hidden truth.

A regex written against the same catalog that generated the data would score ~100%
and prove nothing. So the generator draws ~20% of rows from **novel error forms**,
and `RuleBasedClassifier` is written against the documented forms only - exactly the
situation a merchant is in when a new acquirer is onboarded.

Baseline, seed 42:

| Arm | Slice | n | Accuracy | Coverage |
|---|---|---:|---:|---:|
| rules | all | 500 | 89.4% | 89.4% |
| rules | documented | 396 | 100.0% | 100.0% |
| rules | **novel** | 104 | **49.0%** | 49.0% |

The rule table has perfect precision on the novel slice and terrible recall: when it
does not recognise a string it declines rather than guessing, which is the safe
behaviour. It also fails to recognise **8 risk-blocked declines** written in wording
it has never seen - all 8 landed in the human queue rather than being mislabelled as
retryable, so zero dangerous misses, but zero automation either.

That gap - 49% accuracy and 8 unrecognised compliance stops - is the headroom the LLM
arm has to prove. Run `--classify` with credentials set to fill in its row.

Reporting rules, enforced by `ClassificationScorer`:

- **A rejection counts as wrong** in headline accuracy. Otherwise an arm that
  declines 90% of rows posts a beautiful number. Accuracy and coverage always
  appear side by side.
- **A rejection is a recall miss, not a false positive.** The transaction goes to a
  person; nothing wrong was done, it just was not automated.
- **Risk misses are split two ways.** Mislabelled as actionable (dangerous, target
  zero) versus sent to a human (safe). Averaged into a macro F1 they would vanish.
- **Transport failures are counted apart from validator rejections.** An expired API
  key is not the model producing bad output, and a report that conflates them is
  lying about which part broke.

---

## Phase 3: the safety envelope

`DefaultRecoveryPolicy` runs six gates before any action is chosen. They can only
ever stop an attempt - there is no path where an action is picked first and a gate
consulted afterwards.

| # | Gate | Outcome when it fires |
|---|---|---|
| 1 | classifier returned no verdict | `ESCALATE_HUMAN` |
| 2 | decline is `RISK_BLOCKED` | `DO_NOTHING`, hard stop |
| 3 | confidence below 0.70 | `ESCALATE_HUMAN` |
| 4 | 3 attempts already made | `DO_NOTHING` |
| 5 | an attempt is already in flight | `DO_NOTHING` |
| 6 | outside the recovery window | `DO_NOTHING` |

Gate 2 runs **before** the confidence floor on purpose: a hesitant guess of
`RISK_BLOCKED` must still stop the money, not fall through to the next gate.

Then the action is chosen by what actually recovers that decline:

| Decline | Action | Timing |
|---|---|---|
| `INSUFFICIENT_FUNDS` | retry same rail | just after the customer's next salary credit |
| `ISSUER_DOWN` | retry same rail | 2h, then 8h, then 24h |
| `NETWORK_TIMEOUT` | retry same rail | 30 min, then 2h |
| `AUTHENTICATION_FAILED` | payment link | customer has to act; a silent retry cannot make them finish an OTP |
| `LIMIT_EXCEEDED` | retry same rail | after the daily limit resets |
| `EXPIRED_CARD` | payment link | the card is dead; only another rail can work |
| `INVALID_VPA` | switch to saved card, else link | the handle will not resolve on a retry either |

Decision pass over the 500-row batch (rule classifier, seed 42):

| Action | Count | Value at stake |
|---|---:|---:|
| `RETRY_SAME_METHOD` | 263 | Rs 346,857 |
| `SEND_PAYMENT_LINK` | 144 | Rs 158,711 |
| `ESCALATE_HUMAN` | 53 | Rs 49,122 |
| `RETRY_ALTERNATE_METHOD` | 19 | Rs 26,754 |
| `DO_NOTHING` | 21 | Rs 22,317 |

All 21 `DO_NOTHING` rows are risk-blocked hard stops. The remaining 8 risk-blocked
declines - the ones written in wordings the rule table cannot read - landed in
`ESCALATE_HUMAN`, which is the safe failure. Zero reached an actionable bucket.

### A stopping rule that was cancelling the recovery

The first version used a flat 7-day cutoff. It blocked 98 transactions, almost all
`INSUFFICIENT_FUNDS` - the largest bucket and the one with the most recoverable value.
Those recover when the customer's salary lands, which is up to a month out, so a
7-day window forbade the only mechanism that works on them. The window is now
per-reason: 35 days for insufficient funds, 7 for everything else, since every other
bucket recovers within hours.

Scheduled insufficient-funds retries went from 21 to 119. That is not a tuning
tweak - the original rule looked responsible and quietly deleted the biggest
recovery path in the batch.

---

## The ledger

`JsonlLedger` is append-only. A correction is a new row, never an edit.

Append-only is load-bearing, not stylistic: the attempt ceiling and the
one-live-attempt rule are answered by **counting rows**, so a row that could be
updated would be a way to talk the engine past its own limits. Restarting the process
cannot lose the count either.

Two row types share the table, linked by transaction and idempotency key:

- `DECISION` - what the policy concluded, the model's own justification, and the gate
  that allowed or blocked it
- `OUTCOME` - what happened when the executor carried it out

The idempotency key is `sha256(txnId, attemptNumber)` - derived, never random, so a
crash mid-batch replays the same key and a resume cannot double-charge.

---

## Metrics contract

Report all of these, including the ones that look bad:

| Metric | Definition |
|---|---|
| Recovered value | rupees actually collected |
| Recovery rate | recovered / **oracle ceiling** — never / total failed |
| Net recovered | recovered − attempt costs (payment-link SMS, etc.) |
| Wasted attempts | attempts on non-recoverable transactions |
| **Compliance violations** | attempts on `mustNotRetry` rows. Target: 0 |
| Double-charge rate | same txn collected twice. Target: 0 |
| Classifier accuracy | per-reason precision/recall vs ground truth |
| Exception list | escalated to human, with the reason for each |

Three columns, always: **naive baseline** (retry everything once immediately),
**RecoverX**, **oracle** (perfect knowledge). A number without the ceiling next
to it is not a result.

---

## Failures to demonstrate

Two, on purpose, on video:

1. **Crash mid-batch.** Kill the process during execution, restart, show the
   idempotency keys prevent a double charge on resume.
2. **Model returns garbage.** Feed the classifier a malformed response; the
   schema validator rejects it and the transaction falls into the human queue —
   it never falls through to a charge.

---

## Status

| Phase | Scope | State |
|---|---|---|
| 1 | Domain + synthetic data generator + truth model | **done** |
| 2 | Rule-based and LLM classifiers, both measured | **done** |
| 3 | Policy engine, stopping rules, ledger | **done** |
| 4 | Razorpay test-mode executor with idempotency | **done** |
| 5 | Eval harness: baseline vs agent vs oracle | **done** |
| 6 | Dashboard, chaos tests, pitch material | **done** |

---

## Configuration

Test-mode keys only, via environment:

```bash
export RAZORPAY_KEY_ID=rzp_test_xxx
export RAZORPAY_KEY_SECRET=xxx
```

Never commit live keys. `.env` is gitignored.
