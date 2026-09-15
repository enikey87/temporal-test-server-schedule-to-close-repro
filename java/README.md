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
Run 956: the activity is still open after its schedule-to-close deadline (the previous 955 runs failed as expected).
  deadline:    2026-09-15T10:56:18Z (nanos=0)
  server time: 2026-09-15T10:57:20.202Z
  attempt:     2
```

It has reproduced in every run so far; the stuck activity is always scheduled on a whole second.

## Environment

- `io.temporal:temporal-testing:1.39.0`
- OpenJDK 21.0.12, compiled with `--release 11`
- Linux x86_64
