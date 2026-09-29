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

## Demo

![RecoverX — the full 24-second demo: three gateway error strings that are one decline, the six-gate pipeline, then the KPI row showing Rs 3,39,897 net recovered, 89.0% of the recoverable ceiling, zero compliance violations and zero double charges](docs/brag.gif)

<sup>**The complete 24 seconds, playing above.** For 1080p with sound:
[**docs/brag.mp4**](https://github.com/piyush2676/recoverx/raw/main/docs/brag.mp4) (1.7 MB).
GitHub strips `<video>` from rendered Markdown, so a file in a repository cannot be an inline
player — the README plays the silent GIF and links the original. Every figure on screen is produced
by a run, not a mockup: `--generate --count=500 --seed=42` followed by `--compare`. The error
strings are verbatim from [`ErrorCatalog`](src/main/java/com/recoverx/datagen/ErrorCatalog.java),
the six gates from [`DefaultRecoveryPolicy`](src/main/java/com/recoverx/policy/DefaultRecoveryPolicy.java),
and the styling from the dashboard's own [`index.html`](src/main/resources/static/index.html).</sup>

---

> **Submitting or reviewing this?** [`SUBMISSION.md`](SUBMISSION.md) is the
> self-contained write-up: the problem, every component, the measured results, and
> what the project is *not*. Start there.

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

Two providers are wired - Gemini and Claude - selected by whichever key is present.
Both drive the identical prompt through the identical validator and cache, so
switching provider changes the model and nothing else. That is the only way the arms
stay comparable.

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

The model arm needs credentials from either provider:

```bash
setx GEMINI_API_KEY    "..."   # free tier at aistudio.google.com
setx ANTHROPIC_API_KEY "..."   # console.anthropic.com
```

Gemini is used when both are set. Without either, the run says so and still writes
the rules-only report - a missing key should not cost you the baseline numbers.
Override the model with `--model=`; defaults are `gemini-3.5-flash-lite` and
`claude-opus-5`. If a model id has been retired, the run lists the ones your key can
actually reach.

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

Seed 42, model arm on `gemini-3.5-flash-lite` (free tier, 20 API calls):

| Slice | n | rules | gemini |
|---|---:|---:|---:|
| documented | 396 | **100.0%** | 92.7% |
| **novel** | 104 | 49.0% | **100.0%** |
| all | 500 | 89.4% | **94.2%** |

**The answer is not "the model wins."** Each arm is perfect where the other is weak,
and that is the finding.

The model read **every unseen wording correctly**. All 53 rows the rule table
declined, it answered - and all 53 were right. Escalations went from 53 to zero, and
the 8 risk-blocked declines the regex could not recognise were all classified
correctly.

But it is **worse on the rows the regex was written for**, and the reason is a line in
our own prompt. The 29 documented-slice errors:

```
13  INVALID_VPA     -> RISK_BLOCKED
12  NETWORK_TIMEOUT -> ISSUER_DOWN
 4  ISSUER_DOWN     -> NETWORK_TIMEOUT
```

`ClassifierPrompt` line 42 says *"when the text could be read two ways and one reading
is RISK_BLOCKED, choose RISK_BLOCKED."* The error string is
`U16 - risk threshold exceeded for VPA`. It contains the words "risk threshold", the
model followed the instruction, stopped the money, and cost **Rs 5,576** of recoverable
VPA revenue.

That is not a bug. It is a safety instruction with a measurable price, and both halves
belong in the report: 13 transactions that a compliance officer would rather see
stopped, and the rupees that decision cost.

### What this actually argues for

A hybrid, not a replacement. The regex is perfect on what it was written for and blind
past it; the model is the reverse. `RuleBasedClassifier` already returns 0.95
confidence on an exact vendor-code match and 0.75 on a keyword, so the routing
threshold exists: take the code match when there is one, send everything else to the
model. Neither arm alone is the right production answer, and the comparison is what
makes that visible.

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
  lying about which part broke. This earned its keep: the first Gemini run used a
  model id Google had retired, and the report said `0 rejected by the validator, 500
  that never reached the validator` rather than blaming the model for 500 failures.

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
---

## Phase 4 result: measured recovery

The full cycle - decide, attempt, learn the outcome, decide again, up to three
attempts each - with `SimulatedExecutor` resolving every attempt against the hidden
truth. Against Razorpay test mode you can prove the plumbing works but not that a
rupee came back, because a test-mode payment link is never really paid. Only here can
recovered value be computed rather than claimed.

An attempt succeeds when all four hold: the decline was recoverable at all, it was
scheduled at or after the moment recovery became possible, it used a rail that can
work, and it is the n-th qualifying attempt where n is what the truth says it takes.

**One assumption favours the agent and is stated rather than buried:** a payment link
is treated as satisfying any required rail, because the customer chooses how to pay
when they open it. It is the single most load-bearing modelling choice in the headline
figure.

Zero compliance violations is the gates working, not luck: of the 29 risk-blocked
declines, the rule arm classified and hard-stopped 21 and escalated the 8 it could not
read; the model arm classified all 29. None was ever presented to the gateway.

---

## Phase 5: the comparison

Every arm runs over the same batch, through the same execution loop, ledger and
simulator. The only thing that differs between rows is the strategy - otherwise a
difference in the report could be a difference in the harness.

| Arm | Attempts | Recoveries | Net | % of ceiling |
|---|---:|---:|---:|---:|
| `naive-immediate` | 500 | 0 | Rs 0 | 0.0% |
| `naive-hourly` | 500 | 74 | Rs 91,855 | 24.0% |
| `recoverx-rules` | 730 | 260 | Rs 329,665 | 86.3% |
| `recoverx-llm` | 767 | 275 | Rs 339,897 | **89.0%** |
| `oracle` | 341 | 306 | Rs 382,063 | 100.0% |

**Harness self-check: PASS** - the oracle collects exactly the ceiling. If it could
not, `OracleDecisionSource` and `SimulatedExecutor` would disagree about what a
successful attempt is, and every other row would be wrong in a way no amount of
staring at the policy engine would reveal. It is asserted in the test suite too.

### The baseline is two baselines

`naive-immediate` re-presents the instant a payment fails and recovers nothing -
transient declines need minutes to clear. Quoting it alone would have flattered this
project enormously.

`naive-hourly` waits an hour, which is what a merchant's retry cron actually does. It
is a far stronger opponent: it collects **100% of the network-timeout ceiling** while
knowing nothing at all.

### What each arm costs to get its number

| Arm | Compliance violations | Double charges | Wasted attempts | Escalated |
|---|---:|---:|---:|---:|
| `naive-immediate` | 29 | 0 | 194 | 0 |
| `naive-hourly` | 29 | 0 | 194 | 0 |
| `recoverx-rules` | **0** | 0 | 348 | 53 |
| `recoverx-llm` | **0** | 0 | 374 | 0 |
| `oracle` | 0 | 0 | 0 | 0 |

Both baselines re-present all 29 risk-blocked declines, because a merchant with no
classifier cannot know which ones they are. That is not a bug in the baseline - it is
what having no classifier costs, and a test asserts it stays exactly 29.

### Where the money comes from

| Decline | Ceiling | `naive-hourly` | `recoverx-rules` | `recoverx-llm` |
|---|---:|---:|---:|---:|
| INSUFFICIENT_FUNDS | Rs 112,952 | Rs 0 | Rs 76,154 | Rs 84,564 |
| AUTHENTICATION_FAILED | Rs 66,136 | Rs 22,103 | Rs 60,651 | **Rs 66,136** |
| ISSUER_DOWN | Rs 62,027 | Rs 6,882 | **Rs 62,027** | Rs 59,665 |
| NETWORK_TIMEOUT | Rs 61,959 | **Rs 61,959** | Rs 57,919 | **Rs 61,959** |
| LIMIT_EXCEEDED | Rs 34,273 | Rs 911 | Rs 34,034 | **Rs 34,273** |
| EXPIRED_CARD | Rs 29,716 | Rs 0 | Rs 24,848 | Rs 24,848 |
| INVALID_VPA | Rs 15,001 | Rs 0 | **Rs 14,112** | Rs 8,536 |

This table is the argument. An hourly cron already owns the timeouts - that is the
part of the problem that does not need an agent. It gets **nothing** on insufficient
funds, expired cards or invalid handles, because those need waiting for a payday or
moving rails. That is the part that does.

The model arm collects the entire ceiling on three buckets. It loses `INVALID_VPA` to
the regex for the prompt reason described in the phase 2 section.

### Both denominators, side by side

| Arm | vs ceiling (honest) | vs total failed (inflated) |
|---|---:|---:|
| `naive-hourly` | 24.0% | 15.2% |
| `recoverx-rules` | 86.3% | 54.6% |
| `recoverx-llm` | 89.0% | 56.3% |
| `oracle` | 100.0% | 63.3% |

Recovery quoted against total failed value is inflated with money nobody could have
collected. Both columns are printed so the choice is visible rather than made quietly
in a slide.

---

## Three layers against a double charge

| Layer | Mechanism | When it matters |
|---|---|---|
| Policy gate 5 | refuses to act while an attempt is in flight | any resume |
| `IdempotencyGuard` | returns the recorded outcome instead of calling out | ledger has the outcome |
| Gateway `reference_id` | remote rejects a key it has already seen | process died before recording |

### What the chaos tests actually showed

```bash
# crash in the dangerous window: gateway called, process dies before recording
java -jar target/recoverx-0.1.0.jar --execute --crash-before-outcome=150
java -jar target/recoverx-0.1.0.jar --execute --resume
```

`--crash-after=N` and `--crash-before-outcome=N` call `Runtime.halt` - a real kill, no
shutdown hooks, no flush.

Result: **zero double charges**, but not via the layer that was expected. The policy's
in-flight gate stopped re-presentation before the idempotency guard or the gateway
check was ever reached. Both inner layers reported zero activations.

That is defence in depth working from the outside in, and it is reported as what it
is. The inner two layers are proven by direct unit tests instead - an untested layer
that is never reached in practice is a layer nobody knows is broken.

### The gap that found

Blocking re-presentation left that transaction **stuck forever**: a decision row with
no outcome, an in-flight gate correctly refusing to act, and nothing to resolve it.
`--resume` now reconciles first - it asks the gateway what became of each in-flight
attempt and writes the missing outcome. A read, not a retry.

---

## The dashboard

`java -jar target/recoverx-0.1.0.jar` with no batch flag serves the dashboard at
<http://localhost:8080> (`--server.port=` to move it).

It shows the headline figures, each arm's recovery against the ceiling, where the
money came from by decline type, the full audit trail with the model's own
justification and the gate that allowed or blocked each action, and the exception
queue grouped by reason.

**It computes nothing.** Every figure is read from `eval/out/comparison.json` and
`eval/out/arms/<arm>/ledger.jsonl` - the same artifacts a reviewer can open in a text
editor. A dashboard that recalculated its own numbers would be a second implementation
to keep honest.

Chart colours are a two-slot categorical palette, validated for colourblind separation
and contrast against both the light and dark surfaces before any CSS was written. Both
themes are selected, not an automatic flip; the toggle is in the header.

---

## Failures to demonstrate on video

1. **Crash mid-batch** - `--crash-before-outcome=150`, then `--resume`. Show the
   in-flight gate refusing, the orphan reconciled, zero double charges.
2. **Model returns garbage** - the schema validator rejects an unknown bucket, an
   out-of-range confidence, a hallucinated transaction id or an empty justification,
   and the transaction falls into the human queue. It never falls through to a charge.

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
