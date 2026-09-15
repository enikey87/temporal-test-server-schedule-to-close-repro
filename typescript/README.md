# TypeScript reproduction

See the [root README](../README.md) for the bug. This version uses
`TestWorkflowEnvironment.createTimeSkipping()` from `@temporalio/testing` 1.24.0, which downloads
and runs the Java test server.

## Run

```bash
npm ci
npm test
```

Requires Node 22.18+ (runs the `.ts` files directly). The client used to await results does not
unlock time skipping; the bug does not depend on it.

## Actual output

```
AssertionError [ERR_ASSERTION]: Run 73: workflow is still running 2s after its activity failed; the previous 72 runs failed with RetryState.TIMEOUT as expected.
  attempt:                    2
  first attempt scheduled at: 2026-09-15T09:53:27.000Z
  last attempt completed at:  2026-09-15T09:53:27.001Z
  last failure:               retryable failure; the next attempt would start after schedule-to-close
  schedule-to-close deadline: 2026-09-15T09:58:27.000Z (nanos=0)
The next retry is 90 days away, past the deadline, yet the activity was rescheduled instead of failing.
```

Three runs reproduced it at runs 334, 1342 and 73; every stuck deadline had `nanos=0`, every
other run failed with `RetryState.TIMEOUT`.

## Environment

- `@temporalio/testing`, `@temporalio/worker`, `@temporalio/client` 1.24.0
- Node 24.15.0
- Linux x86_64
