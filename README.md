# Test server ignores schedule-to-close when the deadline falls on a whole second

An activity with `scheduleToCloseTimeout = 5m` fails with a retryable `ApplicationFailure` whose
`nextRetryDelay` (90 days) is past the deadline. It should fail at once with
`RETRY_STATE_TIMEOUT`. When the deadline lands exactly on a whole second, the Temporal test server
reschedules it instead and the workflow never completes.

The check that is skipped:
[`TestServiceRetryState.java#L132`](https://github.com/temporalio/sdk-java/blob/v1.39.0/temporal-test-server/src/main/java/io/temporal/internal/testservice/TestServiceRetryState.java#L132)

```java
if (expirationTime.getNanos() != 0
    && Timestamps.compare(nextScheduleTime, expirationTime) > 0) {
  return new BackoffInterval(RetryState.RETRY_STATE_TIMEOUT);
}
```

About one activity in a thousand is scheduled on a whole second, so each reproduction starts
workflows one by one until one is still running two seconds after its activity failed (a few
seconds), or gives up after 20,000.

| Directory | SDK | Run |
|-|-|-|
| [`typescript/`](typescript) | `@temporalio/testing` 1.24.0 (test server binary) | `npm ci && npm test` |
| [`java/`](java) | `io.temporal:temporal-testing` 1.39.0 (in-process test server) | `./gradlew test` |
