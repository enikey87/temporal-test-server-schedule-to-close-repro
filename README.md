# Test server ignores schedule-to-close when the deadline falls on a whole second

Minimal reproduction for the Temporal Java test server (`io.temporal:temporal-testing:1.39.0`).

An activity with `scheduleToCloseTimeout = 5m` fails with a retryable `ApplicationFailure` whose
`nextRetryDelay` (90 days) is past the deadline. It should fail at once with
`RETRY_STATE_TIMEOUT`. When the deadline lands exactly on a whole second, the test server
reschedules it instead and the workflow never completes.

The check that is skipped:
[`TestServiceRetryState.java#L132`](https://github.com/temporalio/sdk-java/blob/v1.39.0/temporal-test-server/src/main/java/io/temporal/internal/testservice/TestServiceRetryState.java#L132)

```java
if (expirationTime.getNanos() != 0
    && Timestamps.compare(nextScheduleTime, expirationTime) > 0) {
  return new BackoffInterval(RetryState.RETRY_STATE_TIMEOUT);
}
```

## Run

```bash
./gradlew test
```

Requires a JDK 11+. Time skipping is disabled; the bug does not depend on it.

The test starts workflows one by one until one is still running two seconds after its activity
failed (about one in a thousand, a few seconds), or gives up after 20,000.

## Actual output

```
Run 111: workflow is still running 2s after its activity failed; the previous 110 runs failed with RETRY_STATE_TIMEOUT as expected.
  activity state:             PENDING_ACTIVITY_STATE_STARTED, attempt 2
  first attempt scheduled at: 2026-09-15T09:21:59.000Z
  last attempt completed at:  2026-09-15T09:21:59.001Z
  last failure:               retryable failure; the next attempt would start after schedule-to-close
  schedule-to-close deadline: 2026-09-15T09:26:59.000Z (nanos=0)
The next retry is 90 days away, past the deadline, yet the activity was rescheduled instead of failing.
```

Three consecutive runs reproduced it at runs 1430, 910 and 111; every stuck deadline had
`nanos=0`, every other run failed with `RETRY_STATE_TIMEOUT`.

## Environment

- `io.temporal:temporal-testing:1.39.0` (in-process test server)
- OpenJDK 21.0.12, compiled with `--release 11`
- Linux x86_64

The same server binary backs `TestWorkflowEnvironment.createTimeSkipping()` in the TypeScript
SDK (`@temporalio/testing` 1.21.1), where it shows up as an intermittently hanging test.
