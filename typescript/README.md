# TypeScript reproduction

See the [root README](../README.md) for the bug. This version uses
`TestWorkflowEnvironment.createTimeSkipping()` from `@temporalio/testing` 1.24.0, which downloads
and runs the Java test server.

## Run

```bash
npm ci
npm test
```

Requires Node 22.18+ (runs the `.ts` files directly).

## Actual output

```
AssertionError [ERR_ASSERTION]: Run 1486: the activity is still open after its schedule-to-close deadline; the previous 1485 runs failed with RetryState.TIMEOUT as expected.
  first attempt scheduled at: 2026-09-15T10:16:49.000Z
  last failure:               retryable failure; the next attempt would start after schedule-to-close
  schedule-to-close deadline: 2026-09-15T10:21:49.000Z (nanos=0)
  test server time now:       2026-09-15T10:22:51.004Z
  workflow status:            RUNNING, activity attempt 2
```

It has reproduced in every run so far; the stuck activity is always scheduled on a whole second.

## Environment

- `@temporalio/testing`, `@temporalio/worker`, `@temporalio/client` 1.24.0
- Node 24.15.0
- Linux x86_64
