# Test server ignores scheduleToCloseTimeout when the deadline falls on a whole second

The test server keeps an activity open past its schedule-to-close deadline whenever that deadline
lands exactly on a whole second. With millisecond timestamps this hits about one activity in a
thousand, and shows up as tests that hang at random.

## Expected Behavior

An activity whose next retry would start after its `scheduleToCloseTimeout` fails right away with
`RETRY_STATE_TIMEOUT`, as the Temporal server does
([`retry.go#L108`](https://github.com/temporalio/temporal/blob/34f6679d2f5e09755a16a715a73bfec92486cd83/service/history/workflow/retry.go#L108)).
In any case it is closed once the deadline has passed.

## Actual Behavior

When the deadline falls on a whole second, the test server schedules the retry anyway, and the
activity is still open after the deadline has passed:

```
Run 150: the activity is still open after its schedule-to-close deadline (the previous 149 runs failed as expected).
  deadline:    2026-09-15T10:37:03.000Z (nanos=0)
  server time: 2026-09-15T10:38:05.005Z
  attempt:     2
```

The deadline check treats `nanos == 0` as "no deadline"
([`TestServiceRetryState.java#L132`](https://github.com/temporalio/sdk-java/blob/v1.39.0/temporal-test-server/src/main/java/io/temporal/internal/testservice/TestServiceRetryState.java#L132)):

```java
if (expirationTime.getNanos() != 0
    && Timestamps.compare(nextScheduleTime, expirationTime) > 0) {
  return new BackoffInterval(RetryState.RETRY_STATE_TIMEOUT);
}
```

Nothing enforces the deadline later: the schedule-to-close timer is registered for the first
attempt only
([`TestWorkflowMutableStateImpl.java#L1060-L1066`](https://github.com/temporalio/sdk-java/blob/v1.39.0/temporal-test-server/src/main/java/io/temporal/internal/testservice/TestWorkflowMutableStateImpl.java#L1060-L1066))
and is dropped as an outdated timer once the attempt number changes
([`#L2302-L2305`](https://github.com/temporalio/sdk-java/blob/v1.39.0/temporal-test-server/src/main/java/io/temporal/internal/testservice/TestWorkflowMutableStateImpl.java#L2302-L2305)).

## Steps to Reproduce the Problem

  1. Define an activity with `scheduleToCloseTimeout = 5m` that throws a retryable
     `ApplicationFailure` with `nextRetryDelay = 90 days`.
  1. Start workflows calling it one by one against the test server, and wait up to 2 s for each
     to fail.
  1. For the first one still running (usually within a couple of thousand runs), skip the test
     server's time past the deadline: the activity is still open, on attempt 2.

A runnable reproduction is in [`typescript/`](typescript), failing within seconds:

```bash
cd typescript
npm ci
npm test
```

## Specifications

  - Version: test server 1.39.0, downloaded by `@temporalio/testing` 1.24.0. Not a regression: the
    check dates back to temporalio/sdk-java#163 (2020).
  - Platform: Linux x86_64, Node 24.15.0.
