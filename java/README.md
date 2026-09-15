# Java reproduction

See the [root README](../README.md) for the bug. This version uses the in-process test server
from `io.temporal:temporal-testing:1.39.0`.

## Run

```bash
./gradlew test
```

Requires a JDK 11+.

## Actual output

```
Run 1319: the activity is still open after its schedule-to-close deadline; the previous 1318 runs failed with RETRY_STATE_TIMEOUT as expected.
  first attempt scheduled at: 2026-09-15T10:18:19Z
  last failure:               retryable failure; the next attempt would start after schedule-to-close
  schedule-to-close deadline: 2026-09-15T10:23:19Z (nanos=0)
  test server time now:       2026-09-15T10:24:21.211Z
  workflow status:            WORKFLOW_EXECUTION_STATUS_RUNNING, activity attempt 2
```

It has reproduced in every run so far; the stuck activity is always scheduled on a whole second.

## Environment

- `io.temporal:temporal-testing:1.39.0`
- OpenJDK 21.0.12, compiled with `--release 11`
- Linux x86_64
