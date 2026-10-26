# Importing a GPX route

Open **☰ → Cargar ruta GPX**, then choose a `.gpx` file using Android's document picker. The app displays the name, distance and point count and previews the trace when no other navigation session is active. After a valid file is selected, navigation starts automatically with real GPS as soon as location permission and a recent accurate fix are available. No destination search is needed. A pending import leaves existing navigation running until the new trace can start. The **Iniciar navegación** button retries after a timeout or permission denial.

Precise location permission is required to start navigation. If a recent fix with accuracy of 25 metres or better is unavailable, the app waits up to 60 seconds. Close the import panel or stop the route to cancel that wait. When the wait expires, enable location and tap **Iniciar navegación** to retry. Denying permission leaves the trace available for review.

## Supported files

GPX 1.0 and 1.1 routes (`rte/rtept`) and tracks (`trk/trkseg/trkpt`) are supported, including GPX files without a namespace. Export one route or one track containing a single nonempty continuous segment. A file containing one route plus one track is also supported: the track supplies the detailed geometry and its name; route stops are not connected or added to the trace. An empty or invalid track is rejected rather than silently following the route stops. Multiple routes, multiple tracks or multiple nonempty segments are rejected: the app must not create a fictitious connection between separate tracks. Waypoints alone do not define a route.

Files are limited to 5 MiB, 10,000 total route and track points and 64 XML nesting levels. Coordinates must be finite and within latitude/longitude bounds, with at least two distinct points. DTD declarations and external entities are rejected. Route names are capped at 256 characters. An invalid or inaccessible file leaves any running navigation untouched.

## Following a trace

The original coordinates are retained locally in memory and drawn on the phone and TFT. GPX import does not send the file or its coordinates to a routing server. It works with the installed offline map; online background tiles still require a connection when no offline map is selected. Standard trace points do not establish legal roads, surface quality, reliable turns or travel time.

The display says **Sigue el trazado GPX**. It shows progress, remaining distance, sustained deviations and arrival using the existing GPS guidance policy. It does not invent turn instructions, speak turn announcements, show an ETA, or automatically reroute away from the imported trace. On a deviation, return to the trace using the map and road signs. Manual recalculation also preserves the trace. After a confirmed deviation, three fresh, stable GPS fixes spanning at least two seconds automatically resume progress at the nearest point of the original trace, whether you rejoin ahead of or behind the previous position. GPS jumps and inaccurate fixes cannot confirm a rejoin.

The imported route is not persisted across process death or device reboot: reload the original document to resume. The document remains in its selected provider; the app does not request long-term file access or copy the GPX into its own storage. Map diagnostics do not include route coordinates, document paths or route names.

## Validation

Core tests exercise standard GPX routes/tracks, namespaces, malicious XML, resource limits and trace guidance without invented turns or ETA. Android unit tests exercise the document picker, cancellation, precise-location permission denial, preview/start/stop and prevention of online recalculation with the renderer replaced to avoid device-only JNI.
