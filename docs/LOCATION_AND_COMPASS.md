# Location acquisition and compass (0.13.3)

NavFrame now listens to the Android location sources available on the device: GNSS/GPS, network, native fused location, and passive updates. It also seeds the position from a recent, validated Android last-known location rather than always waiting for another satellite fix. The native fused provider is used only when present; this does not introduce a Google Play services dependency or guarantee the same acquisition behavior as Google Maps or Waze.

## Approximate position versus route guidance

The previous 25-metre gate applied even to recentering the map. A recent position with reported accuracy up to 100 metres can now recenter the map, supply a route-preview origin, and show the rider's approximate position. The phone displays the estimated accuracy and labels positions worse than 25 metres as approximate. Stale, future-dated, invalid, or accuracy-free fixes are rejected. A very coarse network update cannot replace a recent usable precise fix.

Starting guidance and automatic recalculation still require a recent fix with accuracy at most 25 metres. The route tracker retains its existing precision, freshness, and sustained-deviation rules. Approximate position is not presented as confirmed road-level route progress. If guidance pauses for low precision while a recent approximate position remains available, the phone/TFT explain that distinction instead of simply claiming there is no GPS signal.

All freshness checks use Android's monotonic elapsed time and a 15-second limit, including cached fixes. Location services must be enabled and precise location permission remains required by the current app workflow. Enabled providers are tracked independently; disabling one does not invalidate another active provider. Location subscriptions are removed when their visible-map or navigation session ends.

## Compass and course

When the position workflow is active, the app uses an absolute rotation-vector sensor (or a geomagnetic rotation-vector sensor when available) to determine the direction of the top edge of the screen. Display rotation is accounted for, and the latest in-memory location supplies magnetic declination for a true-north heading. No extra sensor permission is needed.

At speeds of at least 2 m/s, a usable GPS course takes priority. At lower speeds, an available compass heading drives the map orientation and the phone shows **Brújula** (Compass). The phone labels movement course **Rumbo GPS** (GPS course). The compass is smoothed across the north/360-degree boundary, expires after five seconds without a measurement, and rejects sensor status UNRELIABLE. Without a suitable sensor or trustworthy heading, direction remains unavailable rather than inventing north as a measured heading.

Stationary TFT compass changes can redraw the map, limited to the existing stopped-map cadence (at most once every two seconds). Sensor listeners are released with the location workflow. Magnetic mounts, vehicle metal, and device calibration can affect compass accuracy; test with the phone in its actual mounting position. The sensor describes phone orientation, not inherently motorcycle orientation.

Coordinates are used only in memory; this change does not add coordinate logs or uploads. It retains the existing explicit online-route/search behavior and the persistent sanitized TFT diagnostics from 0.13.2.

## Validation

Robolectric tests cover fresh network-cache startup, stale/invalid/future fixes, coarse updates competing with precise fixes, independent provider availability and cleanup, approximate-map readiness versus strict route guidance, expiration of stale visible positions, stationary compass versus movement course, sensor reliability/cleanup, and north-wrap smoothing. A core scheduling test verifies stationary compass redraws respect the configured rate limit without changing the default policy for other renderers.

Native GNSS acquisition time, device-specific provider availability, compass accuracy on a motorcycle mount, and physical TFT rendering require device validation.
