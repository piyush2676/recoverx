# Five-minute pitch

A running order for the video. Timings are targets, not a script to read aloud —
the numbers below are the ones the repo actually produces at seed 42, so they can be
shown on screen rather than asserted.

Regenerate everything the deck references with:

```bash
mvn -DskipTests package
java -jar target/recoverx-0.1.0.jar --generate --count=500 --seed=42 --out=data
java -jar target/recoverx-0.1.0.jar --compare  --data=data --out=eval/out
java -jar target/recoverx-0.1.0.jar --server.port=8081     # dashboard
```

---

## 0:00 — 0:35 · The problem, in one number

Roughly one in eight Indian online payments fails on the first attempt. Merchants
do one of two things with those: retry them all once, or nothing.

Both are wrong, and for the same reason — **a decline is not one thing.** Some clear
in twenty minutes. Some only clear when the customer's salary lands. Some can only
succeed on a different rail. And some must never be touched again.

> On screen: `data/DATASET.md` — 500 failed payments, Rs 603,762 of failed value.

## 0:35 — 1:20 · What the batch actually contains

Show the failure mix and the ceiling.

- Rs 603,762 failed, of which **Rs 382,063 was recoverable by anyone**. The rest was
  never coming back, and quoting a recovery rate against the larger number is how
  these figures get inflated.
- **29 transactions are risk-blocked.** Re-presenting one is a compliance breach, not
  a wasted retry. They are the trap the whole design is aimed at.
- ~20% of the gateway error strings use wordings no rule was written for — other
  acquirers, raw ISO 8583, transliterated Hindi.

> On screen: the failure-mix table, then a few raw error strings side by side to make
> the point that they look nothing like each other.

## 1:20 — 2:10 · The architecture, and the one decision that matters

```
failed_transactions.jsonl
        │
        ▼
  classify   ── LLM reads the messy error text; emits a bucket,
        │        a confidence, and a written justification
        ▼
  policy     ── DETERMINISTIC. Six gates, then an action.
        │        Nothing here is a prompt.
        ▼
  executor   ── Razorpay test mode. Idempotency key = sha256(txn, attempt)
        │
        ▼
  ledger     ── append-only; one row per decision, one per outcome
```

**The model classifies and explains. It never moves money.** Every rupee-moving branch
is ordinary Java you can read in one sitting. A prompt can be argued out of a stopping
rule by a strange enough input; an `if` statement cannot.

Say the six gates out loud — they take fifteen seconds and they are the product:

1. no verdict → a person
2. risk-blocked → hard stop
3. below the confidence floor → a person
4. three attempts already → stop
5. an attempt already in flight → stop
6. outside the recovery window → stop

Gate 2 runs **before** the confidence floor deliberately: a hesitant guess of
"risk-blocked" must still stop the money.

## 2:10 — 3:10 · The result, against a real baseline

> On screen: the dashboard, top of page.

| Arm | Net recovered | % of ceiling |
|---|---:|---:|
| `naive-hourly` — a merchant's retry cron | Rs 91,855 | 24.0% |
| `recoverx-rules` | Rs 329,665 | 86.3% |
| **`recoverx-llm`** — gemini-3.5-flash-lite | **Rs 339,897** | **89.0%** |
| `oracle` — perfect knowledge | Rs 382,063 | 100.0% |

Two things to say here, both of which pre-empt the obvious question:

- **The baseline is a real opponent.** An hourly retry cron collects *the entire
  network-timeout bucket* while knowing nothing at all. My first baseline retried
  instantly, recovered nothing, and made this project look twice as good as it is. I
  replaced it.
- **The oracle is a self-check, not decoration.** It must collect exactly the ceiling.
  If it cannot, my simulator and my oracle disagree about what a successful attempt is
  and every number on this slide is wrong. It runs on every comparison and it is
  asserted in the test suite.

Then the per-decline table — this is the argument:

| Decline | Ceiling | Retry cron | RecoverX |
|---|---:|---:|---:|
| INSUFFICIENT_FUNDS | Rs 112,952 | **Rs 0** | Rs 76,154 |
| NETWORK_TIMEOUT | Rs 61,959 | **Rs 61,959** | Rs 57,919 |
| EXPIRED_CARD | Rs 29,716 | **Rs 0** | Rs 24,848 |

The cron already owns the timeouts. That is the part of the problem that does not
need an agent. It gets **nothing** on insufficient funds or expired cards, because
those need waiting for a payday or moving rails. That is the part that does.

## 3:10 — 3:50 · What it cost to get that number

| Arm | Compliance violations | Double charges |
|---|---:|---:|
| both baselines | **29** | 0 |
| RecoverX | **0** | 0 |

Both baselines re-present every risk-blocked decline, because a merchant with no
classifier has no way to know which ones they are. That is not a bug in the baseline
— it is what having no classifier costs, and there is a test asserting it stays
exactly 29.

> On screen: scroll the audit trail. Every row carries the bucket, the confidence, the
> model's own sentence, and the gate that allowed or blocked it. This is the file a
> payments team would be asked for in an audit.

## 3:50 — 4:35 · Two failures, on purpose

**Kill it mid-batch.**

```bash
java -jar target/recoverx-0.1.0.jar --execute --crash-before-outcome=150   # Runtime.halt
java -jar target/recoverx-0.1.0.jar --execute --resume
```

Zero double charges — **but not through the mechanism I built for it.** The policy's
in-flight gate refused to re-present before the idempotency guard was ever reached.
Defence in depth working from the outside in. I would rather report that than claim
the idempotency key saved it, which would have been a nicer story and false. The inner
layers are covered by direct unit tests instead.

And blocking re-presentation created a new problem: that transaction was stuck
forever — a decision with no outcome, a gate correctly refusing to act, and nobody to
resolve it. `--resume` now reconciles first: it asks the gateway what became of each
in-flight attempt and writes the missing outcome. A read, not a retry.

**Feed the classifier garbage.** The schema validator rejects an unknown bucket, an
out-of-range confidence, a hallucinated transaction id, or an empty justification, and
the transaction falls into the human queue. There is no path from a malformed model
response to a charge.

## 4:35 — 5:00 · What I would do next, and what I got wrong

Be specific about both — it is more convincing than a roadmap.

- **The model and the regex are each perfect where the other is weak.** On the 104
  novel wordings the regex scores 49% and the model scores 100% — it answered all 53
  rows the regex declined, and got all 53 right. On the 396 documented rows the regex
  is perfect and the model scores 92.7%. The production answer is a hybrid: take the
  exact vendor-code match when there is one, send everything else to the model. The
  routing threshold already exists, because the rule classifier returns 0.95 on a code
  match and 0.75 on a keyword.
- **A line in my own prompt cost Rs 5,576.** It says *"when the text could be read two
  ways and one reading is RISK_BLOCKED, choose RISK_BLOCKED."* The error string
  `U16 - risk threshold exceeded for VPA` contains "risk threshold", so the model
  stopped 13 recoverable transactions. That is a safety instruction with a price tag,
  and both halves belong on the slide: 13 transactions a compliance officer would
  rather see stopped, and what the caution cost.
- I found a stopping rule that was cancelling the recovery: a flat 7-day cutoff
  blocked 98 transactions, almost all insufficient funds — the largest and most
  recoverable bucket. It looked responsible and quietly deleted the biggest recovery
  path in the batch. The window is now per-reason.
- The single most load-bearing assumption in the 86.3%: a payment link is treated as
  satisfying any rail, because the customer picks how to pay. It favours my agent. It
  is stated in the code, the report, and this deck rather than buried.

---

## Things not to claim

Worth writing down so they do not slip out under pressure:

- Do **not** say the idempotency key prevented the double charge. It did not fire.
- Do **not** quote 86.3% without the ceiling next to it, and do not quote the 54.6%
  figure at all unless someone asks for it.
- Do **not** call the simulated executor "Razorpay integration". The Razorpay executor
  is real and test-mode only; the measured rupees come from the simulator, which is
  the only place a recovered rupee can actually be proven.
- Do **not** present the naive-immediate row as *the* baseline.
- Do **not** say the model beat the regex full stop. It lost on the documented slice,
  and the interesting claim is the split, not a win.
