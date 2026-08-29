# RecoverX — Submission

**Razorpay Buildathon · Track 03, AI Revenue Recovery**

Repository: <https://github.com/piyush2676/recoverx>

An autonomous agent that recovers failed online payments. It reads each decline,
decides a bounded recovery action, executes it against Razorpay test mode, and writes
every decision to an append-only audit trail. It reports **rupees actually recovered
against a measured ceiling** — not attempts made.

| | |
|---|---|
| **Result** | Rs 3,39,897 recovered — **89.0% of everything that was recoverable** |
| **Versus** | 24.0% for an hourly retry cron; 100% is perfect knowledge |
| **Safety** | 0 compliance violations, 0 double charges, 0 escalations |
| **Scale** | 46 main classes, 10 test classes, 6,570 lines of Java, 83 tests |
| **Stack** | Java 21, Spring Boot 3.3.5, Gemini + Claude, Razorpay test mode |

---

## 1. The problem

Roughly one in eight Indian online payments fails on the first attempt. Merchants do
one of two things with those: retry them all once, or nothing at all.

Both are wrong, and for the same reason — **a decline is not one thing.**

- Some clear on their own in twenty minutes.
- Some only clear when the customer's salary lands, up to a month later.
- Some can only ever succeed on a *different* payment rail.
- And some must never be touched again, because touching them is a compliance breach.

A single retry policy cannot be right for all four. Getting it right needs something
that reads the decline, understands *why* it failed, and decides accordingly — while
never being allowed to do something a payments team would be fired for.

---

## 2. What the system does, end to end

```
data/failed_transactions.jsonl        the agent's only input
        │
        ▼
  classify      A language model reads the messy gateway error text and emits
        │       a bucket, a confidence, and a written justification.
        │       Every response is schema-validated before it is believed.
        ▼
  policy        DETERMINISTIC. Six gates, then an action and a time.
        │       Nothing here is a prompt. This is where money is gated.
        ▼
  executor      Razorpay test mode. Idempotency key = sha256(txnId, attemptNumber).
        │
        ▼
  ledger        Append-only. One row per decision, one per outcome.
        │
        ▼
  eval          Baselines, the agent, and an oracle — scored on the same batch.
```

**The single most important design decision: the model classifies and explains, but it
never moves money.** Every rupee-moving branch is ordinary Java you can read in one
sitting. A prompt can be argued out of a stopping rule by a sufficiently strange input.
An `if` statement cannot.

---

## 3. Every component, explained

### 3.1 `domain` — the data model (5 classes)

A failed payment carries two separate views:

- **What the agent sees** — the gateway error, the amount, the rail, the acquirer, and
  ordinary customer context (their payday, prior failures, whether a card is on file).
- **What only the harness sees** — a `RecoveryTruth`: whether the decline was
  recoverable at all, the earliest instant recovery becomes possible, which rail can
  work, how many attempts it takes, and whether the instrument must never be
  re-presented.

Splitting these at the *type* level is what makes the claim "the agent never saw the
answer" checkable rather than asserted.

### 3.2 `datagen` — the synthetic batch (5 classes)

Generates 500 failed payments, fully reproducible from a seed. Realism that matters:

| Property | Why it is there |
|---|---|
| Payday-linked recovery | `INSUFFICIENT_FUNDS` clears when salary lands, clustered on the 1st and month-end. A retry ten minutes later collects nothing. |
| Rail-locked recovery | Expired cards and invalid UPI handles can *only* succeed on a different rail. Same-rail retries are guaranteed waste. |
| The do-not-retry trap | 29 of 500 rows are risk-blocked. Re-presenting one is a compliance breach, not a wasted retry. |
| Log-normal amounts | Recovered rupees depend on *which* transactions the agent picks, not a flat average. |
| Messy error strings | Each failure reason gets several unrelated surface forms. |
| **Unseen wordings** | 104 of 500 rows use forms from other acquirers, raw ISO 8583, and transliterated Hindi. |

That last row is the experiment. A regex written against the same catalog that
generated the data scores ~100% and proves nothing. The unseen slice is where a
language model has to earn its cost — or fail to, which is equally worth reporting.

**Batch composition:** Rs 6,03,762 of failed value, of which **Rs 3,82,063 (63.3%) was
recoverable by anyone**. The rest was never coming back.

### 3.3 `classify` — reading the decline (9 classes)

Three implementations, all returning the same type:

1. **`RuleBasedClassifier`** — exact vendor codes first, then keywords. What a
   competent engineer writes in an afternoon after reading the gateway docs. This is
   deliberately a *good* baseline: comparing a model against a strawman would prove
   nothing.
2. **`GeminiClassifier`** — Google Gemini, free tier. Used for the measured run.
3. **`LlmClassifier`** — Anthropic Claude. Same contract, selected if that key is set.

Both model classifiers drive `ClassifierPrompt` **verbatim** — the same system prompt,
batch size, and batch renderer. If each carried its own copy, a wording drift would
appear in the report as a capability difference and nothing would catch it.

**`ResponseParser` is the safety boundary.** Unparseable output, an unknown bucket, a
confidence outside 0–1, an empty justification, a hallucinated transaction id, a
truncated response — each becomes a *declined* classification that routes to a human.
**There is no path from a malformed model response to a charge.** It has no SDK
dependency, so all of those failure modes are unit-tested without a network call.

Verdicts are cached by content hash, so re-running an evaluation is free and the
numbers in a report do not move because the model was sampled again.

### 3.4 `policy` — the deterministic half (5 classes)

Six gates run **before** any action is selected. There is no path where an action is
chosen and a gate consulted afterwards.

| # | Gate | Result |
|---|---|---|
| 1 | Classifier returned no verdict | → a human |
| 2 | Decline is `RISK_BLOCKED` | → hard stop |
| 3 | Confidence below 0.70 | → a human |
| 4 | Three attempts already made | → stop |
| 5 | An attempt is already in flight | → stop |
| 6 | Outside the recovery window | → stop |

**Gate 2 runs before gate 3 deliberately.** A hesitant guess of "risk-blocked" must
still stop the money, not fall through to the confidence check.

Only if all six pass is an action chosen, based on what actually recovers that decline:

| Decline | Action | Timing |
|---|---|---|
| `INSUFFICIENT_FUNDS` | retry same rail | just after the customer's next salary credit |
| `ISSUER_DOWN` | retry same rail | 2h, then 8h, then 24h |
| `NETWORK_TIMEOUT` | retry same rail | 30 min, then 2h |
| `AUTHENTICATION_FAILED` | payment link | the customer must act; a silent retry cannot make them finish an OTP |
| `LIMIT_EXCEEDED` | retry same rail | after the daily limit resets |
| `EXPIRED_CARD` | payment link | the card is dead; only another rail can work |
| `INVALID_VPA` | switch to saved card, else link | the handle will not resolve on a retry either |

### 3.5 `ledger` — the audit trail (3 classes)

Append-only. A correction is a new row, never an edit.

**Append-only is load-bearing, not stylistic.** The attempt ceiling is answered by
*counting rows*, so a row that could be updated would be a way past the engine's own
limit. The ledger also rebuilds from disk on startup — a process that began with an
empty index would recompute the same idempotency keys, find nothing, and re-present
every attempt.

Every decision row carries the classified reason, the confidence, the **model's own
written justification**, and the gate that allowed or blocked the action. This is the
file a payments team would be asked for in an audit.

### 3.6 `executor` — carrying it out (4 classes)

`RazorpayExecutor` calls Razorpay test mode over documented REST. It **refuses live
keys at construction** — this code creates payment requests, and pointing it at a live
account by accident is not a mistake worth being recoverable from.

**One domain constraint shapes the whole executor:** under RBI rules a merchant cannot
silently re-charge an Indian card without a standing e-mandate. So against the real
API, every recovery action becomes a payment link the customer approves. That is why
links carry a cost in the policy's model, and why "retry" never means "quietly charge
them again".

### 3.7 `eval` — the measurement harness (13 classes)

The largest package, and deliberately so. It holds the oracle, the baselines, the
simulator, the scorers, and the comparison runner. **It is the only package permitted
to read the ground-truth file**, and a test fails the build if anything else does.

`SimulatedExecutor` resolves each attempt against the hidden truth. Against Razorpay
test mode you can prove the plumbing works but not that a rupee came back, because a
test-mode payment link is never really paid. Only here can recovered value be
*computed* rather than claimed.

### 3.8 `dashboard` — seeing it (1 class + one page)

Serves the headline figures, each arm against the ceiling, where money came from by
decline type, the full audit trail, and the exception queue.

**It computes nothing.** Every figure is read from the files a run wrote, so what
appears on screen is the same artifact a reviewer can open in a text editor. A
dashboard that recalculated its own numbers would be a second implementation to keep
honest.

---

## 4. Results

### 4.1 Money recovered

| Arm | Attempts | Recoveries | Net recovered | % of ceiling |
|---|---:|---:|---:|---:|
| `naive-immediate` — retry instantly | 500 | 0 | Rs 0 | 0.0% |
| `naive-hourly` — a merchant's retry cron | 500 | 74 | Rs 91,855 | 24.0% |
| `recoverx-rules` | 730 | 260 | Rs 3,29,665 | 86.3% |
| **`recoverx-llm`** — Gemini | 767 | 275 | **Rs 3,39,897** | **89.0%** |
| `oracle` — perfect knowledge | 341 | 306 | Rs 3,82,063 | 100.0% |

**The baseline is a real opponent.** An hourly retry cron collects the *entire*
network-timeout ceiling while knowing nothing at all. The first version of this
baseline retried instantly, recovered nothing, and made the project look twice as good
as it is — so it was replaced with the stronger one.

**The oracle is a self-check, not decoration.** Perfect knowledge must collect exactly
the ceiling. If it cannot, the oracle and the simulator disagree about what a
successful attempt is and every number is suspect. It runs on every comparison and is
asserted in the test suite. It passes.

### 4.2 What each strategy costs

| Arm | Compliance violations | Double charges | Wasted attempts | Escalated |
|---|---:|---:|---:|---:|
| `naive-immediate` | **29** | 0 | 194 | 0 |
| `naive-hourly` | **29** | 0 | 194 | 0 |
| `recoverx-rules` | **0** | 0 | 348 | 53 |
| `recoverx-llm` | **0** | 0 | 374 | 0 |
| `oracle` | 0 | 0 | 0 | 0 |

Both baselines re-present all 29 risk-blocked declines, because a merchant with no
classifier has no way to know which ones they are. That is not a bug in the baseline —
**it is what having no classifier costs**, and a test asserts it stays exactly 29.

### 4.3 Where the money comes from

| Decline | Ceiling | Retry cron | Rules | Gemini |
|---|---:|---:|---:|---:|
| INSUFFICIENT_FUNDS | Rs 1,12,952 | Rs 0 | Rs 76,154 | Rs 84,564 |
| AUTHENTICATION_FAILED | Rs 66,136 | Rs 22,103 | Rs 60,651 | **Rs 66,136** |
| ISSUER_DOWN | Rs 62,027 | Rs 6,882 | **Rs 62,027** | Rs 59,665 |
| NETWORK_TIMEOUT | Rs 61,959 | **Rs 61,959** | Rs 57,919 | **Rs 61,959** |
| LIMIT_EXCEEDED | Rs 34,273 | Rs 911 | Rs 34,034 | **Rs 34,273** |
| EXPIRED_CARD | Rs 29,716 | Rs 0 | Rs 24,848 | Rs 24,848 |
| INVALID_VPA | Rs 15,001 | Rs 0 | **Rs 14,112** | Rs 8,536 |

**This table is the argument.** An hourly cron already owns the timeouts — that is the
part of the problem that does not need an agent. It gets *nothing* on insufficient
funds, expired cards, or invalid handles, because those need waiting for a payday or
moving rails. That is the part that does.

### 4.4 Does the AI earn its cost?

| Slice | n | Rules | Gemini |
|---|---:|---:|---:|
| Documented wordings | 396 | **100.0%** | 92.7% |
| **Unseen wordings** | 104 | 49.0% | **100.0%** |
| All | 500 | 89.4% | **94.2%** |

**The answer is not "the model wins." Each is perfect where the other is weak, and
that is the finding.**

The model read **every unseen wording correctly**. All 53 rows the rule table declined,
it answered — and all 53 were right. Escalations went from 53 to zero.

But it is **worse on the rows the regex was written for**, and the cause is a line in
our own prompt:

> *"When the text could be read two ways and one reading is RISK_BLOCKED, choose
> RISK_BLOCKED."*

The error string `U16 - risk threshold exceeded for VPA` contains the words "risk
threshold". The model followed the instruction, stopped the money, and cost
**Rs 5,576** of recoverable revenue across 13 transactions.

That is not a bug. It is a safety instruction with a measurable price, and both halves
belong in the report: 13 transactions a compliance officer would rather see stopped,
and what that caution cost.

**What it argues for is a hybrid**, not a replacement: take the exact vendor-code match
when there is one, send everything else to the model. The routing threshold already
exists, because the rule classifier returns 0.95 confidence on a code match and 0.75 on
a keyword.

---

## 5. How failures are handled

### 5.1 Three layers against a double charge

| Layer | Mechanism | When it matters |
|---|---|---|
| Policy gate 5 | refuses to act while an attempt is in flight | any resume |
| `IdempotencyGuard` | returns the recorded outcome instead of calling out | the ledger has the outcome |
| Gateway `reference_id` | the remote rejects a key it has already seen | the process died before recording |

The key is `sha256(txnId, attemptNumber)` — **derived, never random** — so a restart
recomputes it and the guards recognise it. A random key looks new every time, which is
exactly how resumes double-charge.

### 5.2 The chaos test, and what it actually proved

```bash
java -jar target/recoverx-0.1.0.jar --execute --crash-before-outcome=150
java -jar target/recoverx-0.1.0.jar --execute --resume
```

Both crash flags call `Runtime.halt` — a real kill, no shutdown hooks, no flush.

Result: **zero double charges — but not through the mechanism built for it.** The
policy's in-flight gate stopped re-presentation before the idempotency guard or the
gateway check was ever reached. Both inner layers reported zero activations.

That is defence in depth working from the outside in, and it is reported as what it is.
Claiming "the idempotency key prevented the double charge" would have been a nicer
story and false. The inner two layers are proven by direct unit tests instead — an
untested layer that is never reached in practice is a layer nobody knows is broken.

### 5.3 The gap that found

Blocking re-presentation left that transaction **stuck forever**: a decision row with
no outcome, a gate correctly refusing to act, and nothing to resolve it. The gate was
right; what was missing was somebody to answer its question.

`--resume` now reconciles first — it asks the gateway what became of each in-flight
attempt and writes the missing outcome. A read, not a retry.

### 5.4 Malformed model output

The validator rejects an unknown bucket, an out-of-range confidence, a hallucinated
transaction id, a truncated response, or an empty justification, and the transaction
falls into the human queue. Eight tests cover exactly those cases.

Transport failures are counted **separately** from validator rejections. This earned
its keep: the first Gemini run used a model id Google had retired, and the report said
`0 rejected by the validator, 500 that never reached the validator` — rather than
blaming the model for 500 failures it never saw.

---

## 6. How the numbers are kept honest

Every one of these is a deliberate choice that makes the result *look worse* and be
more true.

1. **Ground truth is physically separated.** It lives in its own file that no agent
   package may read, and `GroundTruthIsolationTest` fails the build if one does. The
   claim is checkable with a grep.
2. **Recovery is quoted against the recoverable ceiling, never total failed value.**
   Both denominators are printed side by side — 89.0% against the ceiling, 56.3%
   against everything — so the choice is visible rather than made quietly in a slide.
3. **The oracle must collect exactly the ceiling**, or the harness is telling itself a
   story. Checked on every run, asserted in tests.
4. **A declined classification counts as wrong** in headline accuracy. Otherwise an arm
   that refuses 90% of the batch posts a beautiful number. Accuracy and coverage always
   appear together.
5. **Compliance violations get their own line**, never averaged into a success rate.
6. **The baseline keeps its violations.** Filtering them out would quietly rig the
   comparison.
7. **The generous assumption is stated, not buried.** A payment link is treated as
   satisfying any required rail, because the customer chooses how to pay. This favours
   the agent, and it is the single most load-bearing modelling choice in the 89.0%.

---

## 7. Findings worth keeping

Three things went wrong during the build. All three are in the repository's decision
log, because they are more informative than the parts that worked.

**A stopping rule was cancelling the recovery.** A flat 7-day retry cutoff blocked 98
of 500 transactions — almost all insufficient funds, the largest and most recoverable
bucket. Those recover on payday, up to a month out, so the cutoff forbade the only
mechanism that works on them. It looked responsible and silently deleted the biggest
recovery path in the batch. The window is now per-reason; scheduled retries for that
bucket went from 21 to 119.

**The chaos test disproved its own premise**, as described in §5.2.

**A safety line in the prompt cost Rs 5,576**, as described in §4.4. The instruction
stays — the asymmetry it encodes is real — but the price is now measured beside it.

---

## 8. Running it

```bash
mvn -DskipTests package

# build a reproducible batch of 500 declines
java -jar target/recoverx-0.1.0.jar --generate --count=500 --seed=42 --out=data

# score every arm against the hidden truth
java -jar target/recoverx-0.1.0.jar --compare --data=data --out=eval/out

# the dashboard
java -jar target/recoverx-0.1.0.jar --server.port=8081
```

The model arm needs a key from either provider — `GEMINI_API_KEY` (free tier at
aistudio.google.com) or `ANTHROPIC_API_KEY`. **Without either, the run says so and
still writes the complete rules-only report.** A missing key never costs you the rest
of the numbers.

500 model verdicts are cached in the repository's output directory, so the exact
figures above reproduce for free.

Other modes: `--classify` (classifier scoring only), `--decide` (decision pass only),
`--execute` (full cycle, supports `--crash-after` / `--crash-before-outcome` /
`--resume`).

---

## 9. What I would do next

- **Ship the hybrid classifier.** The routing threshold already exists in the
  confidence the rule classifier emits. This is the clearest available win.
- **Check calibration.** The model arm escalated nothing — its confidence never fell
  below the 0.70 floor. That is either good calibration or an unwillingness to admit
  doubt, and this batch cannot tell them apart.
- **Fix the ISSUER_DOWN / NETWORK_TIMEOUT confusion.** 16 rows in both directions, on a
  distinction the prompt explicitly warns about.
- **Replay against a real merchant's declines.** Every number here rests on a synthetic
  truth model. It is calibrated to public Indian decline distributions and every
  assumption is documented, but it is not production data.

---

## 10. What this is not

Stated plainly, because a reviewer will ask.

- **The recovered rupees are simulated, not collected.** `RazorpayExecutor` is real and
  test-mode only, but a test-mode payment link is never actually paid. The measured
  figures come from a simulator resolving attempts against a known truth — which is the
  only place a recovered rupee can be *proven* rather than asserted.
- **The dataset is synthetic.** Reproducible from a seed, calibrated to plausible
  distributions, with every assumption documented in `data/DATASET.md`.
- **The model arm is one run of one model** (`gemini-3.5-flash-lite`) on 500 rows. It is
  a measurement, not a benchmark.

---

## Repository map

| Path | What is there |
|---|---|
| `README.md` | full technical write-up |
| `docs/DECISIONS.md` | every design choice worth defending, including the ones that came out badly |
| `docs/PITCH.md` | five-minute presentation running order |
| `eval/README.md` | how the scoring works |
| `src/main/java/com/recoverx/` | 46 classes across 8 packages |
| `src/test/java/` | 83 tests |
