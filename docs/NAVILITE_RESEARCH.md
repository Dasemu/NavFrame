# Pillion / NaviLite research

Date: 2026-09-29. Source inspected: [Pillion](https://github.com/alexandrevega/pillion), `main`, commit `6647af22f035ad74dc98f0e2a29e0c8a769a1caa`. The upstream repository was downloaded to `/tmp/navframe-pillion`; code was read only after checking `LICENSE.md`.

## License and reuse

The [pinned upstream license](https://github.com/alexandrevega/pillion/blob/6647af22f035ad74dc98f0e2a29e0c8a769a1caa/LICENSE.md) is PolyForm Noncommercial 1.0.0. It permits modification, use, and distribution for noncommercial purposes and describes personal hobby projects without anticipated commercial application. It is not a permissive license for commercial exploitation. Recipients of any part of the software must receive the terms or their URL and the required notice: `Required Notice: Copyright 2026 the Pillion authors`.

The full license is retained in `third_party/pillion/LICENSE.md`. The `navilite` module adapts Pillion algorithms and sequencing; its files identify their origin. Pillion's license is not presented as automatically licensing the rest of the project. Commercial use requires a separate license from the rightsholder.

## Structure and examined components

Pillion uses Kotlin Multiplatform and Compose Multiplatform, with module `composeApp`, Android code in `src/androidMain`, and shared protocol in `src/commonMain`. It uses JDK 17 and the Android SDK. Relevant files at the inspected commit:

| Source | NavFrame reuse |
|---|---|
| `protocol/NaviLiteCodec.kt` | Packet format and little endian handling; NavFrame adds version, CRC, and size checks |
| `protocol/Crc32Mpeg2.kt` | Checksum algorithm |
| `protocol/Auth.kt` | XOR and nonce; NavFrame adds a minimum length check |
| `protocol/ServiceType.kt` | IDs needed for initialization and images |
| `core/Handshake.kt` | AUTH payload and setup order/values |
| `core/FrameReader.kt` | Fragmented stream reading, reimplemented with bounds and CRC |
| `core/MirrorEngine.kt` | Stop-and-wait image sending and UInt16 sequence |
| `android/RfcommByteChannel.kt` | Insecure RFCOMM socket and UUID |

NavFrame does not reuse `CaptureService`, `MediaProjectionScreenSource`, ADB, mirroring, the GitHub updater, or UI components. The sender accepts a NavFrame-generated JPEG at 480×240.

## Transport and image

Bluetooth Classic RFCOMM, with the phone as client and the motorcycle already paired. UUID `00007220-0000-1000-8000-00805F9B34FB`; socket created with `createInsecureRfcommSocketToServiceRecord`. This is not BLE/GATT. Only one projection app should occupy the link; close StreetCross/Pillion before testing.

NaviLite on this path sends a baseline JPEG: service 0, pointer payload, imageType 3, UInt16 LE sequence, then JPEG. Pillion uses 480×240 and also documents 480×236. Do not assume every CCU accepts the same height: its documentation also distinguishes 480×234 for family `006-B3952` and 480×240 for `006-B4160` / `006-B4920`. NavFrame's initial target remains 480×240.

An ACK service 80 is expected after each image. Effective rate depends on JPEG size, encoding, and transport. Upstream-reported 14–15 FPS at approximately quality 40 is not a guarantee for the user's phone or motorcycle. Even an unchanged image may need periodic retransmission to keep the session active; see the real observation below. There is no need to render or encode an unchanged JPEG again.

## Compatibility: evidence and assumptions

The [upstream specification](https://github.com/alexandrevega/pillion/blob/6647af22f035ad74dc98f0e2a29e0c8a769a1caa/docs/PROTOCOL.md) attributes the original observation to an MT-07 2025, CCU `006-B4160-00`. The [compatibility table](https://github.com/alexandrevega/pillion/blob/6647af22f035ad74dc98f0e2a29e0c8a769a1caa/docs/COMPATIBILITY.md) contains reports for MT-07 2025, R9 2026, MT-09 2024/2025, MT-09 SP 2026, and XSR900 2025. These are upstream reports, separate from NavFrame tests below.

MT-07 2026 is not listed as confirmed in that upstream table. The owner first confirmed Garmin StreetCross works on the MT-07 2026 with a Xiaomi 11 Lite 5G NE / Android 14. The owner later confirmed **both NavFrame-generated images** (test and synthetic) appeared on the TFT: RFCOMM connection, handshake, and 480×240 images are verified by the owner for that combination; **M1-TFT achieved**. This has not been extrapolated to other motorcycles/phones, and long-term stability, power use, and latency have not been measured.

During that test, the owner observed the connection drop after a long interval between sends. No reproducible duration, disconnect log, or controlled screen/Doze condition is available yet. The behavior is consistent with CCU inactivity, but Android suspension must also be separated as a cause. No specific timeout or exclusive cause is asserted.

## Inactivity and recommendations after M1

At the inspected commit, `core/MirrorEngine.kt` always requests `latestFrame()` and sends the available JPEG after each ACK, even if its content is unchanged. It has no separate heartbeat/ping and does not deduplicate static images. `docs/PROTOCOL.md` lists service 16 `BT_THROUGHPUT_TIMEOUT_UPDATE` with unknown payload, so it cannot provide a documented keepalive. Service 80 acknowledges images but does not by itself establish how long a link can remain idle.

The initial recommendation is to resend the latest cached JPEG every **1 second**, with a new sequence, one frame in flight, and normal ACK wait. This is a provisional policy to measure, not a discovered CCU timeout or a universally guaranteed value. When navigation updates, send the new frame and reset the keepalive timer instead of sending an extra redundant packet. Reuse a static JPEG without rendering/encoding it again. A send failure starts conservative reconnection while the user keeps the session active; STOP must cancel sending/retries and release resources.

A stationary visual rate of zero FPS and a transport rate of zero packets are not equivalent. The scheduler may avoid redraws while retaining retransmissions if the CCU needs them. To find a suitable interval, compare screen on/off, stable connection at 1 s, then longer intervals. Record monotonic send/ACK times and failure cause, without location data. Stable measured durations are test evidence, not automatically protocol limits.

## Differences between upstream documentation and code

The PROTOCOL text describes NAV_STATUS `01 00` in setup, but `core/Handshake.kt` currently sends `00 00`. The pinned code was used as the smoke-test reference. The exact meaning of several setup values remains uncertain. StreetCross documentation contains services not needed by the smoke test; dashboard commands and navigation metadata are not implemented without a specific test.

The upstream reader does not validate CRC on receive or limit payload before reserving memory; NavFrame does. The upstream engine ignores non-ACK packets during transmission; NavFrame does not interpret navigation commands and documents this limitation. NavFrame adds timeouts and socket closure to unblock reads/connects.

The exact implementable contract is in [NAVILITE.md](NAVILITE.md).
