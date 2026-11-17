# Tether Android Performance Benchmarks

## Targets (from Android PRD)
- **Decode + render (modern device):** < 15 ms
- **Decode + render (legacy, e.g. Samsung Galaxy J2 Prime):** best-effort
- **Per-frame allocations:** 0 in hot path
- **APK size:** < 3 MB
- **Stability:** 4 hours continuous streaming, no crash, <= 10% memory growth

## Recorded Runs

| Device | Android API | Chosen Decoder | Stream Config | Avg Decode+Render (ms) | Dropped Frames (30m) | APK Size | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Samsung Galaxy J2 Prime | 23 (Android 6.0) | _Pending device run_ | — | — | — | — | — |
| Modern Reference Device | — | _Pending device run_ | — | — | — | — | — |
