# TFT map incident: La Corredoria, 12 October 2026

## Report and conclusion

The owner reported that the TFT map stopped working in La Corredoria (Oviedo) after approximately 20 minutes of successful operation. It displayed `MAPA NO DISPONIBLE` and did not recover for the rest of the journey, while the phone map continued working. An offline map was installed. The owner reported using the latest version, but the installed APK and error diagnostics were not available to verify the build or failure category.

**The root cause remains unknown.** The symptom is consistent with a failure in the independent TFT snapshot workflow. There is no device log or physical reproduction establishing a GPU, resource, network, or lifecycle cause. No runtime change is proposed on that basis.

This investigation reviewed commit `6fec09f20fff50c3ed94782b10c67998adce986f`, which declares version 0.13.1. Repository inspection does not establish which exact APK was installed.

## What the implementation establishes

- The phone uses `PhoneMapView`/MapLibre `MapView`; the service renders the TFT with a separate `MapSnapshotter` at 480×240. A working phone map does not establish that the snapshot renderer is healthy.
- `MapLibreTftRenderer.render` allows 30 seconds for the first snapshot and 10 seconds after its first success. Errors are classified as `TIMEOUT`, `NETWORK`, `STYLE`, `GPU`, `BITMAP`, or `SDK`. The display text alone does not identify the category or cause.
- `TftService.mapUnavailable` discards the failed renderer and schedules retries after 2, 4, 8, and at most 15 seconds. A replacement renderer receives the initial 30-second timeout again. Persistent failure can mean repeated errors; a retained error image can also remain visible if updates stop. An increasing attempt counter distinguishes active retries from a static last frame.
- The error image is composed by NavFrame. If it arrived as a new frame during the incident, at least that frame was transmitted. It does not by itself prove that Bluetooth later stayed connected.
- `OfflineMapStyle` uses local PMTiles and bundled font assets rather than network resource URLs. Loss of mobile coverage alone does not explain a failure when a valid offline source was actually selected and its resources remained usable.
- Installation and selection are separate actions. An installed package does not prove that the active source was offline: the user must select **Use** for that package. The diagnostic report distinguishes `LOCAL PMTiles` from `ONLINE`.
- The bundled Asturias archive has PMTiles v3 metadata, zoom levels 6–14, and bounds 42.9–43.75 N / −7.2–−4.45 E. La Corredoria lies inside these declared bounds. This rules out being outside that archive's declared coverage, but does not validate every tile or establish that this archive was selected.
- The TFT requests zoom 15–17.5 according to speed and maneuver proximity; the phone uses a zoom 0.8 levels lower. Overzoom beyond the stored maximum is not itself evidence of a defect, but the two views can request different resources.
- The real TFT session renews its 120-second partial wake lock every 30 seconds. No programmed 20-minute cutoff was found in this workflow. Device power management and native resource problems remain unverified.
- This commit already includes the 1 October TFT connectivity lifecycle correction: the snapshot renderer owns a connectivity receiver activation independently of the phone map. The older defect cannot be assumed to explain this incident if the installed build includes that correction.

## Open hypotheses

A snapshot timeout, graphics-context failure, or native resource problem can affect the TFT while its independent phone map keeps working. A local tile, style, or glyph resource failure is also possible. Alternatively, an online source may have remained selected despite an installed offline package. A static last error frame must be distinguished from repeated failing captures. None of these hypotheses is confirmed by the report.

## Capture evidence before restarting

While stationary, open **Menu → Information and licenses → TFT map diagnosis → Share** (Spanish UI: **☰ → Información y licencias → Diagnóstico del mapa TFT → Compartir**). Save the complete report before stopping the service or restarting the app. It includes the active source, failure code, stage, duration, attempts, and last failure without coordinates or URLs. The last failure is retained in service memory even after recovery, but is lost when that service instance is destroyed; it is not a persistent incident history.

For a stationary reproduction with an attached development device, collect logcat locally and inspect `MAP_UNAVAILABLE`, `TFT_MAP_NETWORK`, `GPS_FAILED`, and MapLibre/EGL output. A full logcat may include private information; review and redact it before sharing.

| Evidence | Next investigation |
| --- | --- |
| `TIMEOUT / STYLE_LOADING` | Determine which style/resource operation did not finish; do not infer an Internet outage solely from the timeout. |
| `TIMEOUT / STYLE_READY` | Inspect pending resources and native rendering after style loading completed. |
| `GPU`, `EGL`, or `CONTEXT_LOST` | Investigate OpenGL context and recovery on the actual device. |
| `NETWORK` with source `ONLINE` | Verify source selection and connectivity. |
| Increasing attempt counter | Retries are running and failing again. |
| Unchanging attempt counter and error frame | Investigate rendering/session progress and whether the TFT is showing a retained frame. |

After preserving evidence, restarting the map mode or app while stationary can test recovery. It is not a demonstrated fix for this incident.

## Automated validation and limits

The reviewed code built its debug APK and passed Android lint (zero errors, 76 warnings), 159 unit tests (60 app, 89 core, 10 NaviLite; zero failures or skips), and 17 offline Python tests. The cloud setup uses JDK 17 and Android SDK 36.

These checks do not execute physical MapLibre JNI/OpenGL rendering, Yamaha Bluetooth/TFT behavior, or device power management. They neither reproduce nor rule out this incident. Future fixes need evidence from the device and validation of the affected failure/recovery path.
