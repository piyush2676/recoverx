# Design decisions

Short log of choices worth defending in a five-minute pitch.

## The model never moves money

The classifier emits a reason, a confidence, and a written justification. The
policy engine — plain Java — decides the action. Rationale: every gate in the
track's bar ("bounded", "gated") has to survive an adversarial input. A prompt
can be talked out of a stopping rule; an `if` statement cannot.

## Ground truth lives in a separate file

`data/ground_truth.jsonl` is never read outside `eval/`. Makes the "the agent
never saw the answer" claim verifiable with grep rather than trust.

## Recovery rate is measured against an oracle ceiling

Reporting recovered rupees as a fraction of total failed value inflates the
number with transactions nobody could have recovered. The generator knows the
ceiling, so the honest denominator is available.

## Compliance violations are their own metric

Retrying a risk-blocked decline is a different kind of wrong from retrying a
hopeless one. Folded into a precision score it vanishes. Reported on its own
line, target zero, it is the first thing a payments reviewer looks for.

## Idempotency key is derived, not random

`key = hash(txnId, attemptNumber)`. A crash mid-batch replays the same key, so
resume cannot double-charge. Random keys would silently lose this.

## Attempt cost is subtracted

Payment links cost money to send. Net recovered is the only figure that means
anything to a merchant.

## The rule baseline is a real baseline

Comparing an LLM against a deliberately bad regex proves nothing. `RuleBasedClassifier`
checks exact vendor codes first, then keywords, and is written the way an engineer
would write it after reading the gateway docs. It scores 100% on the documented
forms. The LLM has to beat a competent opponent, not a strawman.

## ~20% of rows use wordings no rule was written for

The flip side of the point above: a regex written against the same catalog that
generated the data scores ~100% on everything, and there is no gap left to measure.
So `ErrorCatalog` has a second tier of novel forms - other vendors, raw ISO 8583,
regional phrasing - and the rule table has never seen them. Metrics are sliced
documented vs novel. That slice is the only place an LLM call can justify its cost,
and if it cannot, that is a finding worth reporting too.

The novel RISK_BLOCKED wordings deliberately share no vocabulary with the rule
table's risk keywords. An earlier version reused "compliance hold" and "negative
list", which let the rule table look like it generalised when it was only matching a
word it had been handed.

## Rejections count as wrong

Headline accuracy is `correct / total`, where declining to answer is not correct.
Reporting only accuracy-over-answered would let a classifier that rejects 90% of
rows post a great number. Accuracy and coverage are always printed together.

## Transport failures are counted apart from validator rejections

An expired API key is not "the model produced bad output". Folding the two together
produced a report claiming 500 validator rejections when the validator had never
been reached. Both still route to a human; only the accounting differs.

## Model verdicts are cached by content hash

Tuning a report means re-running the eval twenty times. The cache makes that free,
and makes the numbers in the deck stable across runs. The key includes a prompt
version, so editing the prompt misses the cache rather than reporting stale verdicts.
Rejections are never cached - they are usually transient, and caching one would
freeze a bad run into the report.

## The recovery window is per-reason, not flat

A flat 7-day cutoff blocked 98 of 500 transactions, nearly all insufficient funds -
the largest bucket by value and the most recoverable one. Those recover when the
customer is paid, up to a month out, so the cutoff forbade the only mechanism that
works on them. Insufficient funds now gets 35 days; everything else keeps 7, because
every other bucket recovers within hours.

Worth stating plainly: the original rule looked responsible and silently deleted the
biggest recovery path in the batch. A stopping rule that forbids the mechanism it is
supposed to bound is not safety.

## The risk gate runs before the confidence floor

If the confidence check came first, a hesitant guess of RISK_BLOCKED would be routed
to a human by the floor - which is safe - but a *confident* wrong guess of something
else would proceed. Ordering the compliance stop first means no reading of a
risk-blocked decline can reach the executor, at any confidence.

## Gates cannot select actions, only block them

The engine runs all six gates before any action is chosen. There is deliberately no
path where an action is selected and a gate is consulted afterwards, because that
shape invites an exception ("this action is safe, skip the check") that is very hard
to spot in review.

## Each transaction is decided as of shortly after it failed

The batch spans 30 days. Deciding all of it under one wall-clock "now" would push
most rows past their cutoff and measure nothing except that the batch is old. A real
recovery system reacts to each failure minutes after it arrives, so `DecisionRunner`
sets the clock per transaction to failure time plus five minutes. It is a simulation
of arrival, stated in the runner's javadoc so a reviewer sees the assumption instead
of inferring it from a number.

## A failed ledger write throws

A decision that is not durably recorded has not happened. Swallowing an IO error
there would mean money moving with no audit row behind it, which is the one failure
this project cannot afford to be quiet about.

## Every recovery action becomes a payment link against the real API

A merchant in India cannot silently re-charge a card or UPI handle. Under RBI rules a
merchant-initiated debit needs a standing e-mandate the customer set up in advance.
So `RETRY_SAME_METHOD` does not mean "quietly charge them again" - against Razorpay it
means "present the payment again for the customer to approve", which is a link.

This changes what actions cost. A silent retry would be free and invisible; a link
costs money to send and asks something of the customer. The policy's cost model already
charges for links. A merchant with e-mandates on file would add a mandate-token path;
the synthetic customers have none, so there is no such path in this repo.

## Raw HTTP for Razorpay, not the SDK

The REST request shapes are the documented contract and do not drift with an SDK
version, and this class has no credentials to be integration-tested against in CI.
Guessing at SDK method names that cannot be exercised is how a demo fails live.

## The simulator models the gateway's duplicate check durably

Razorpay requires a payment link's `reference_id` to be unique. That second layer only
matters in one window - a process that called out and died before writing the outcome -
so the simulator writes each presented key to disk *before* the attempt resolves. A
crash after that point still leaves the key, and the resume is rejected exactly as the
real gateway would reject it. Modelling it is the difference between demonstrating the
guard and asserting it.

## The chaos test disproved the expected mechanism, and that is reported

Crash-and-resume produces zero double charges - but not through the idempotency guard.
The policy's in-flight gate stops re-presentation first, so the guard and the gateway
check are never reached. Reporting "the idempotency key prevented a double charge"
would have been a nicer story and false. The inner layers are covered by direct unit
tests instead.

## Reconciliation exists because the in-flight gate created a deadlock

Blocking re-presentation left the orphaned transaction stuck permanently: a decision
with no outcome, a gate that correctly refuses to act, and nothing to resolve it. The
fix is not to weaken the gate but to answer its question - `--resume` asks the gateway
what became of each in-flight attempt and writes the missing outcome. A read, not a
retry.

## Live keys are refused at construction

`RazorpayExecutor` throws unless the key has an `rzp_test_` prefix. This code creates
payment requests; pointing it at a live account by accident is not a mistake worth
being recoverable from.

## Every arm shares one execution loop

Each strategy is a `DecisionSource` plugged into the same `RecoveryCycle`, ledger and
simulator. If each arm had its own loop, a difference in the report could be a
difference in the loop rather than in the strategy, and the comparison would be
measuring the harness.

## The naive baseline is two baselines

Retrying the instant a payment fails recovers nothing here, because transient declines
need minutes to clear. Quoting only that would have made RecoverX look far better than
it is. `naive-hourly` - what a merchant's retry cron actually does - collects the
entire network-timeout ceiling while knowing nothing, and is the honest opponent.

## The baseline keeps its compliance violations

Both baselines re-present all 29 risk-blocked declines. That is not a bug to be fixed
in the baseline; it is what not having a classifier costs, and it is the clearest
single argument for the gates. Filtering them out would quietly rig the comparison.
There is a test asserting the baseline still commits exactly that many.

## The oracle is a self-check, not just a denominator

Perfect knowledge must collect exactly the ceiling. If it cannot, the oracle and the
simulator disagree about what a successful attempt is, and every arm's number is wrong
in a way that inspecting the policy engine would never reveal. The check runs on every
`--compare` and is asserted in the test suite.

## Both denominators are printed

Recovery against total failed value is inflated by money nobody could have collected -
54.6% instead of 86.3%. Showing both makes the choice visible instead of something
decided quietly when a slide is made.

## Two model providers, one prompt

Gemini and Claude are both wired, selected by whichever API key is present. The
measured run uses Gemini's free tier; the Claude integration stays because it is
written, tested, and costs nothing to keep.

`ClassifierPrompt` holds the system prompt, the batch size, and the batch renderer,
and both classifiers use it verbatim. If each carried its own copy, a wording drift in
one would appear in the report as a capability difference and nothing would catch it.
There is a test asserting the Gemini request carries exactly the shared prompt.

The validator, the content-hash cache, and the scorer were already provider-agnostic,
so adding a provider touched one new class and a factory rather than the pipeline.

## Raw HTTP for Gemini too

Same reasoning as the Razorpay executor: the REST contract is documented and stable,
it adds no dependency, and there are no credentials in CI to integration-test a client
library against. The key goes in the `x-goog-api-key` header rather than the `?key=`
query parameter the docs also permit - a secret in a URL ends up in proxy logs and
browser history.

## Unreadable model output yields nothing, not an exception

`extractText` returns an empty string when the response shape is unexpected, the
candidate list is empty, or `finishReason` is anything but `STOP` - a truncated or
safety-blocked completion included. The validator then declines every row in the batch
and they go to a human. Throwing would have been louder; declining is correct, because
the question "what did the model say" has no answer here.

## The classifier comparison came out split, and the split is the finding

On the 104 novel wordings the regex scores 49% and Gemini scores 100% - it answered
all 53 rows the regex declined and got all 53 right. On the 396 documented rows the
regex is perfect and the model scores 92.7%.

Neither arm alone is the right production answer. The regex is exact on what it was
written for and blind past it; the model is the reverse. The hybrid is to take an
exact vendor-code match when there is one and send everything else to the model, and
the routing threshold already exists because `RuleBasedClassifier` returns 0.95 on a
code match and 0.75 on a keyword.

Reporting only the headline (94.2% vs 89.4%) would have hidden that entirely.

## A safety line in the prompt cost Rs 5,576, and it stays

`ClassifierPrompt` tells the model that when a decline reads two ways and one reading
is RISK_BLOCKED, choose RISK_BLOCKED. The error string
`U16 - risk threshold exceeded for VPA` contains the words "risk threshold", so 13
recoverable transactions were stopped as compliance holds. That is the entire
`INVALID_VPA` gap between the two arms.

The instruction is not being removed. A wrong RISK_BLOCKED costs one missed recovery;
a missed one is a compliance breach, and that asymmetry is the reason the rule exists.
What changes is that the cost is now measured and reported next to it.

## The model arm escalates nothing, which is worth watching

Coverage went from 89.4% to 100%: the confidence floor never fired, because the model
never returned a confidence below 0.70. That is either good calibration or an
unwillingness to admit doubt, and this batch cannot tell the two apart. A calibration
check - are the 0.7-confidence answers actually right 70% of the time - is the obvious
next measurement.
