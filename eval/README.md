# Eval harness

Two harnesses live here. **Classification** (phase 2, built) scores classifier arms
against the true failure bucket. **Recovery** (phase 5, planned) scores rupees
recovered against the oracle ceiling. Both read `data/ground_truth.jsonl`; nothing
outside this package may, and `GroundTruthIsolationTest` enforces that.

## Classification harness

```bash
java -jar target/recoverx-0.1.0.jar --classify --data=data --out=eval/out
```

Writes `out/classification_report.md`: headline accuracy and coverage per arm per
slice, a RISK_BLOCKED safety section, per-bucket precision/recall/F1, and a confusion
matrix. Arms are scored on three slices - all, documented, novel - because the novel
slice is the only one the rule table was not written against.

Model verdicts are cached in `out/llm_cache.jsonl`, keyed by content and prompt
version, so re-runs are free and reproducible.

## Recovery harness (planned)

Scores a run of the agent against the hidden truth. This is the only place in the
repo allowed to read `data/ground_truth.jsonl`.

## Inputs

| File | Produced by |
|---|---|
| `data/ground_truth.jsonl` | the generator |
| `out/ledger.jsonl` | the agent run |

## Scoring rules

An attempt on transaction `t`, made at instant `at`, using method `m`, succeeds
exactly when all of these hold:

1. `truth.recoverable` is true
2. `at >= truth.recoverableFrom`
3. `truth.requiredMethod` is null, or equals `m`
4. this is the `truth.attemptsNeeded`-th qualifying attempt

Anything else fails. An attempt on a row with `truth.mustNotRetry` is recorded as
a **compliance violation** and reported on its own line — never averaged into
precision, where it would disappear.

## Arms to report

| Arm | Policy |
|---|---|
| baseline | retry every failed txn once, immediately, same rail |
| recoverx | the agent |
| oracle | perfect knowledge; the ceiling nobody beats |

## Output

`out/report.md` plus the charts used in the pitch video. Every recovered-rupees
figure appears next to the oracle ceiling for the same batch.
