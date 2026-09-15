# Java reproduction

See the [root README](../README.md) for the bug. This version uses the in-process test server
from `io.temporal:temporal-testing:1.39.0`.

## Run

```bash
./gradlew test
```

Requires a JDK 11+. Time skipping is disabled; the bug does not depend on it.

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

Four runs reproduced it at runs 1430, 910, 111 and 1674; every stuck deadline had `nanos=0`,
every other run failed with `RETRY_STATE_TIMEOUT`.

## Environment

- `io.temporal:temporal-testing:1.39.0`
- OpenJDK 21.0.12, compiled with `--release 11`
- Linux x86_64
