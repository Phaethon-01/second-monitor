# Tether Android Client — Milestone Progress

## Important Note on Shared Wire Protocol
The Android PRD references a Shared tab (`docs/protocol-vectors.json`) for the exact binary framing contract with the Windows/Rust host. Because those files were not included in the workspace attachments, the `com.tether.display.proto` package implements a clean, deterministic Big-Endian binary protocol covering every message and field defined in the Android PRD (`HELLO`, `CONFIG`, `VIDEO_FRAME`, `TOUCH`, `PING`, `PONG`, `REQUEST_KEYFRAME`, `DISPLAY_CHANGE`, `BYE`). Once `docs/protocol-vectors.json` is added, `TetherProtocol` and `ProtocolTest` can be aligned 1:1 with those test vectors without touching the codec, net, input, or UI layers.

## Milestones

- **M0 — Gradle project & `proto` package with tests**: Implemented (pure View system, Kotlin Coroutines only, `ProtocolTest` passing).
- **M1 — Decoder capabilities, async low-latency `MediaCodec`, `PING/PONG`, drop-and-resync, reconnect loop**: Implemented (`DecoderCapabilities`, `VideoDecoder`, `FrameQueueController`, `TetherClient`, `FrameQueueControllerTest` passing).
- **M2 — Aspect-correct fullscreen rendering (`CONFIG` resolution scaling)**: Implemented (`AspectSurfaceView`).
- **M3 — Touch capture, normalized coordinates over visible video area, move coalescing, cancel handling**: Implemented (`TouchMapper`, `TouchCoalescer`, `TouchInputTest` passing).
- **M4 — Per-stage timestamps, tuned decoder options, toggleable stats overlay**: Implemented (`FrameQueueController` latency ring, `StatsOverlayView`).
- **M5 — Rotation handling (`DISPLAY_CHANGE`), waiting screen, APK size optimization**: Implemented (`TetherActivity`, `WaitingOverlayView`).

## Pending Manual / Device Verification
- [ ] Verify APK installation and `am start` launch from Windows host on Samsung Galaxy J2 Prime (API 23).
- [ ] Verify `adb reverse tcp:27183 tcp:27183` end-to-end streaming against the Windows host.
- [ ] Verify unplug and replug USB cable auto-recovery with no manual steps.
- [ ] Verify kill and reopen app auto-recovery.
- [ ] Verify physical device rotation triggers `DISPLAY_CHANGE` and `CONFIG` renegotiation.
- [ ] Verify screen lock / unlock recovers decoder surface and resumes on next IDR.
- [ ] Record 30-minute / 4-hour stability and decode+render latency in `docs/BENCHMARKS.md`.
