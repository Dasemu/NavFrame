package dev.navframe.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import dev.navframe.core.*
import dev.navframe.navilite.NaviLiteTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** The started foreground service owns real sessions independently of activity bindings. */
class TftService : Service() {
    inner class LocalBinder : Binder() { val service get() = this@TftService }
    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val renderer = TestTftRenderer()
    private val navigationRenderer = SyntheticTftRenderer()
    private var routeJob: Job? = null
    private var routeGeneration = 0L
    private var preparedGeneration = 0L
    internal var routingEngineFactory: (String) -> RoutingEngine = { ValhallaRoutingEngine(it, "dev.navframe.app/${BuildConfig.VERSION_NAME}", language = { NavigationVoicePreferences.language(this).tag }) }
    internal var mapRenderOverride: (suspend (NavigationState, NavigationMode) -> TftFrame)? = null
    private var routePlan: RoutePlan? = null
    private var preparedDestinationDirty = false
    private var shownRoute: RoutePlan? = null
    private var routePreview = true
    private var guidance: RouteGuidanceTracker? = null
    private var routeDemo: RouteDemo? = null
    private var routeDemoStartedMs = 0L
    private var rerouteJob: Job? = null
    private val reroutePolicy = ReroutePolicy()
    private var rerouteFailures = 0
    private var reroutePaused = false
    /** True once the rider is past the leg prepended to reach an imported route's first point. */
    private var approachDone = false
    private var lastGpsFix: LocationFix? = null
    private var lastGpsElapsed = 0L
    private val mutableRouteInfo = MutableStateFlow("Sin ruta calculada")
    val routeInfo = mutableRouteInfo.asStateFlow()
    private data class RoutePlan(val route: RouteResult, val origin: GeoPoint, val demo: Boolean, val options: RouteOptions = RouteOptions(), val importedBase: RouteResult? = null)
    private var navigationJob: Job? = null
    private var locationProvider: AndroidLocationProvider? = null
    private var gpsRequested = false
    private val mutableGpsReady = MutableStateFlow(false)
    val gpsReady = mutableGpsReady.asStateFlow()
    private var phoneVisible = false
    private var visibleGpsJob: Job? = null
    private var visibleGpsProvider: AndroidLocationProvider? = null
    private var previewJob: Job? = null
    private var navigationEpoch = 0L
    private val mutableNavigationState = MutableStateFlow(NavigationState())
    val navigationState = mutableNavigationState.asStateFlow()
    private var latestNavigationState = NavigationState()
        set(value) {
            field = value; mutableNavigationState.value = value
            if (shownRoute?.demo == false && !routePreview && value.route?.let { !it.imported || it.maneuvers.isNotEmpty() } != false) voice?.update(value)
            else voice?.reset()
        }
    private var voice: NavigationVoiceController? = null
    private val speedCameraAlerts = SpeedCameraAlertEngine()
    private val speedCameraRepository by lazy { OfflinePlaceRepository(applicationContext) }
    private var cameraJob: Job? = null
    private var radarAlertExpiryJob: Job? = null
    private var cameraCheckAt = 0L
    private val mutableRadarAlert = MutableStateFlow<String?>(null)
    val radarAlert = mutableRadarAlert.asStateFlow()
    private val mutableVoiceEnabled = MutableStateFlow(true)
    val voiceEnabled = mutableVoiceEnabled.asStateFlow()
    private val mutableVoiceStatus = MutableStateFlow("Voz: preparando motor español")
    val voiceStatus = mutableVoiceStatus.asStateFlow()
    private var mapRenderer: MapLibreTftRenderer? = null
    private var lastMapNetworkConnected: Boolean? = null
    private val mutableMapDiagnostic = MutableStateFlow("TFT MAP · sin captura")
    val mapDiagnostic = mutableMapDiagnostic.asStateFlow()
    private var lastMapFailureDiagnostic = "sin fallo registrado"
    private val mapDiagnosticHistory by lazy { MapDiagnosticHistory(applicationContext) }
    private var mapHistoryWriteFailed = false
    private var mapRenderStarted = 0L
    private var mapRenderStage = MapRenderStage.INITIALIZE
    private var mapRetryAt = 0L
    /** Rider zoom adjustment on top of the speed-based TFT camera; persisted across sessions. */
    var tftZoomOffset: Double = 0.0
        private set
    private var forceMapRedraw = false
    /** Installed offline map used for the TFT while the online source keeps failing. */
    private var fallbackRenderer: MapLibreTftRenderer? = null
    private var fallbackUri: String? = null
    private var onlineRetryAt = 0L
    private var offlineSources: List<Pair<RegionalBounds, TileSource>> = emptyList()
    private var offlineSourcesAt = 0L
    private val mutableTftZoomInfo = MutableStateFlow("")
    val tftZoomInfo = mutableTftZoomInfo.asStateFlow()
    private var dashboardRequests = sortedMapOf<Int, Int>()
    private var mapFailures = 0
    private var unavailableMapFrame: TftFrame? = null
    private val mutableMode = MutableStateFlow(NavigationMode.IDLE)
    val navigationMode = mutableMode.asStateFlow()
    private val mutableNavigationInfo = MutableStateFlow("Navegación detenida")
    val navigationInfo = mutableNavigationInfo.asStateFlow()
    private var sessionJob: Job? = null
    private var fakeTransport: FakeTftTransport? = null
    private var realRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val mutableState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = mutableState.asStateFlow()
    private val mutableFrame = MutableStateFlow<TftFrame?>(null)
    val lastFrame = mutableFrame.asStateFlow()
    private val mutableMessage = MutableStateFlow("Listo")
    val message = mutableMessage.asStateFlow()
    private val mutableInterval = MutableStateFlow(1_000L)
    val intervalMs = mutableInterval.asStateFlow()
    private var notificationText = "Conectando con Yamaha…"

    override fun onCreate() {
        super.onCreate()
        mutableInterval.value = getSharedPreferences("tft", MODE_PRIVATE).getLong("interval_ms", 1_000).coerceIn(500, 3_000)
        tftZoomOffset = TftCameraPolicy.stepOffset(getSharedPreferences("tft_camera", MODE_PRIVATE).getFloat("zoom_offset", 0f).toDouble(), 0)
        voice = NavigationVoiceController(this).also { controller ->
            mutableVoiceEnabled.value = controller.enabled.value
            scope.launch { controller.status.collect { mutableVoiceStatus.value = it } }
        }
    }
    fun setVoiceEnabled(enabled: Boolean) { mutableVoiceEnabled.value = enabled; voice?.setEnabled(enabled) }
    fun voiceLanguageSelection(): String = NavigationVoicePreferences.selection(this)
    fun setVoiceLanguage(selection: String) { NavigationVoicePreferences.save(this, selection); voice?.languageChanged() }
    fun speakNavigationAlert(text: String) { voice?.speakAlert(text) }

    private fun checkSpeedCameras(state: NavigationState, now: Long) {
        if (!getSharedPreferences("navigation_alerts", MODE_PRIVATE).getBoolean("radars_enabled", false) ||
            shownRoute?.demo != false || routePreview || state.navigationStatus != NavigationStatus.NAVIGATING || state.route == null) {
            cameraJob?.cancel(); cameraJob = null
            radarAlertExpiryJob?.cancel(); radarAlertExpiryJob = null
            speedCameraAlerts.reset(); mutableRadarAlert.value = null
            return
        }
        if (cameraJob?.isActive == true || now < cameraCheckAt) return
        val fix = lastGpsFix ?: return
        val fixElapsed = lastGpsElapsed
        val epoch = navigationEpoch
        val route = state.route
        cameraCheckAt = now + 2_000
        cameraJob = scope.launch {
            try {
                val cameras = speedCameraRepository.speedCameras(fix.position)
                ensureActive()
                if (epoch != navigationEpoch || latestNavigationState.route !== route ||
                    latestNavigationState.navigationStatus != NavigationStatus.NAVIGATING ||
                    !getSharedPreferences("navigation_alerts", MODE_PRIVATE).getBoolean("radars_enabled", false)) return@launch
                val elapsed = SystemClock.elapsedRealtime()
                speedCameraAlerts.update(fix, cameras, elapsed, elapsed - fixElapsed, route)?.let { alert ->
                    mutableRadarAlert.value = alert.label
                    radarAlertExpiryJob?.cancel()
                    radarAlertExpiryJob = scope.launch {
                        delay(5_000)
                        mutableRadarAlert.value = null
                    }
                    voice?.speakCameraAlert(alert.distanceMeters)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.w("NavFrame", "RADAR_LOOKUP_FAILED type=${error.javaClass.simpleName}") }
        }
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REAL -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS)
                if (address == null) { stopSelf(); return START_NOT_STICKY }
                if (intent.getBooleanExtra(EXTRA_AUTO, false) && realRequested) return START_NOT_STICKY
                if (intent.getBooleanExtra(EXTRA_AUTO, false) && getSharedPreferences("yamaha", MODE_PRIVATE).getBoolean("paused", false)) return START_NOT_STICKY
                if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) return START_NOT_STICKY
                getSharedPreferences("yamaha", MODE_PRIVATE).edit().putBoolean("paused", false).apply()
                realRequested = true
                notificationText = "Conectando con Yamaha…"
                try { refreshForeground() } catch (_: RuntimeException) { realRequested = false; mutableState.value = ConnectionState.ERROR; return START_NOT_STICKY }
                replaceSession {
                    try { runReal(address) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        realRequested = false
                        mutableState.value = ConnectionState.ERROR
                        mutableMessage.value = "No se pudo iniciar la sesión: ${error.message}"
                        android.util.Log.e("NavFrame", "TFT_SESSION_FAILED", error)
                        refreshForeground()
                    }
                }
            }
            ACTION_GPS -> startGps()
            ACTION_MAP_GPS -> startGps(true)
            ACTION_ROUTE_GPS -> startRoute(intent.getBooleanExtra(EXTRA_PREVIEW, false))
            ACTION_STOP_NAVIGATION -> stopNavigation()
            ACTION_STOP -> { stopNavigation(); disconnect() }
        }
        // Explicit user sessions are not silently resumed after process death or force-stop.
        return START_NOT_STICKY
    }

    /** Waiting non-cancellably for cleanup also handles several rapid connect/stop requests. */
    private fun replaceSession(action: suspend () -> Unit) {
        val previous = sessionJob
        previous?.cancel()
        sessionJob = scope.launch {
            withContext(NonCancellable) { previous?.join() }
            ensureActive()
            fakeTransport?.disconnect()
            fakeTransport = null
            action()
        }
    }

    fun connectFake() {
        getSharedPreferences("yamaha", MODE_PRIVATE).edit().putBoolean("paused", true).apply()
        realRequested = false
        replaceSession {
            refreshForeground()
            val fake = FakeTftTransport().also { fakeTransport = it }
            fake.connect()
            mutableState.value = ConnectionState.CONNECTED
            fake.sendFrame(ensureFrame())
            mutableMessage.value = "Simulador conectado · sin sesión Bluetooth continua"
        }
    }

    private suspend fun ensureFrame(): TftFrame {
        return mutableFrame.value ?: renderer.renderTest(false).also { mutableFrame.value = it }
    }

    private suspend fun runReal(address: String): Nothing = coroutineScope {
        val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NavFrame:TftSession").apply {
            setReferenceCounted(false)
        }
        wakeLock = lock
        var renewal: Job? = null
        try {
            ensureFrame()
            lock.acquire(120_000)
            renewal = launch {
                while (isActive) { delay(30_000); lock.acquire(120_000) }
            }
            TftSessionRunner(
                transportFactory = { NaviLiteTransport(applicationContext, address, ::onDashboardRequest) },
                latestFrame = { requireNotNull(mutableFrame.value) },
                policy = { TftSessionPolicy(effectiveInterval()) },
                clockMs = { SystemClock.elapsedRealtime() },
                onState = { state ->
                    mutableState.value = state
                    if (state == ConnectionState.CONNECTING || state == ConnectionState.HANDSHAKING) {
                        updateNotification(if (state == ConnectionState.CONNECTING) "Conectando con Yamaha…" else "Preparando sesión TFT…")
                    }
                },
                onSent = { frame, elapsed ->
                    mutableMessage.value = "Sesión continua · ${effectiveInterval()} ms · ${frame.encodedBytes.size} bytes · ACK ${elapsed} ms"
                    updateNotification("Conectado · envío cada ${effectiveInterval()} ms")
                    android.util.Log.i("NavFrame", "FRAME_SENT bytes=${frame.encodedBytes.size} ackMs=$elapsed")
                },
                onRetry = { error, wait ->
                    mutableMessage.value = "Conexión interrumpida: ${error.message ?: error.javaClass.simpleName} · reintento en ${wait / 1_000} s"
                    updateNotification("Reconectando en ${wait / 1_000} s · Desconectar para detener")
                    android.util.Log.w("NavFrame", "TFT_RETRY delayMs=$wait", error)
                },
            ).run()
        } finally {
            renewal?.cancel()
            if (lock.isHeld) lock.release()
            if (wakeLock === lock) wakeLock = null
        }
    }

    /** Manual changes replace cached JPEG; only the real session loop writes to Bluetooth. */
    fun sendTestFrame(useSynthetic: Boolean) {
        if (!realRequested && fakeTransport == null) { mutableMessage.value = "Conecta primero el simulador o la Yamaha."; return }
        stopNavigation(false)
        val epoch = navigationEpoch
        scope.launch {
            val frame = renderer.renderTest(useSynthetic)
            if (epoch == navigationEpoch) publishFrame(frame)
            if (realRequested) mutableMessage.value = "Imagen preparada para el siguiente envío continuo."
        }
    }

    fun setInterval(interval: Long) {
        require(interval in 500..3_000)
        mutableInterval.value = interval
        getSharedPreferences("tft", MODE_PRIVATE).edit().putLong("interval_ms", interval).apply()
    }

    fun disconnect(manual: Boolean = true) {
        if (manual) getSharedPreferences("yamaha", MODE_PRIVATE).edit().putBoolean("paused", true).apply()
        realRequested = false
        replaceSession {
            mutableState.value = ConnectionState.DISCONNECTED
            mutableMessage.value = "Desconectado · envío y reconexión detenidos"
            refreshForeground()
        }
    }

    private fun effectiveInterval(): Long =
        if (isMapMode(mutableMode.value)) mutableInterval.value
        else if (mutableMode.value != NavigationMode.IDLE && latestNavigationState.navigationStatus == NavigationStatus.NAVIGATING && latestNavigationState.speed >= 0.7f) 200L
        else mutableInterval.value

    private fun refreshForeground() {
        if (!realRequested && !gpsRequested) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        if (Build.VERSION.SDK_INT >= 29) {
            val types = (if (realRequested) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0) or
                (if (gpsRequested) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            startForeground(1, notification(), types)
        } else startForeground(1, notification())
    }

    private suspend fun publishFrame(frame: TftFrame) {
        currentCoroutineContext().ensureActive()
        mutableFrame.value = frame
        fakeTransport?.sendFrame(frame)
    }

    private fun clearNavigation() {
        voice?.reset()
        cameraJob?.cancel(); cameraJob = null; cameraCheckAt = 0L
        radarAlertExpiryJob?.cancel(); radarAlertExpiryJob = null
        speedCameraAlerts.reset(); mutableRadarAlert.value = null
        previewJob?.cancel(); previewJob = null
        if (rerouteJob?.isActive == true) reroutePolicy.markFinished(SystemClock.elapsedRealtime())
        rerouteJob?.cancel()
        rerouteJob = null
        navigationEpoch++
        latestNavigationState = NavigationState()
        navigationJob?.cancel()
        navigationJob = null
        mapRenderer?.close()
        mapRenderer = null
        mapRetryAt = 0L
        mapFailures = 0
        unavailableMapFrame = null
        try { locationProvider?.close() } catch (_: SecurityException) { }
        locationProvider = null
        gpsRequested = false
    }

    fun stopNavigation(clearFrame: Boolean = true) {
        previewJob?.cancel()
        cancelRouteRequest()
        shownRoute = null
        routePlan = null
        mutableRouteInfo.value = "Ruta detenida"
        guidance = null
        routeDemo = null
        clearNavigation()
        mutableMode.value = NavigationMode.IDLE
        mutableNavigationInfo.value = "Navegación detenida · conexión TFT conservada"
        refreshForeground()
        if (clearFrame) {
            val epoch = navigationEpoch
            scope.launch {
                val frame = navigationRenderer.render(NavigationState(), NavigationMode.IDLE, null)
                if (epoch == navigationEpoch) publishFrame(frame)
            }
        }
    }

    fun startDemo(map: Boolean = false, preserveRoute: Boolean = false) {
        if (!preserveRoute) {
            cancelRouteRequest()
            shownRoute = null
            routePlan = null
            guidance = null
            routeDemo = null
            mutableRouteInfo.value = "Sin ruta · cambio de modo"
        }
        clearNavigation()
        val mode = if (map) NavigationMode.MAP_DEMO else NavigationMode.DEMO
        mutableMode.value = mode
        mutableNavigationInfo.value = if (map) "MAPA · posición simulada · sin ruta" else "DEMO · carretera, ruta y maniobras ficticias"
        refreshForeground()
        navigationJob = scope.launch {
            val scheduler = createScheduler(mode)
            val start = SystemClock.elapsedRealtime()
            val startEpoch = System.currentTimeMillis()
            try {
                while (isActive) {
                    val now = SystemClock.elapsedRealtime()
                    val simulated = if (map && !routePreview && shownRoute?.demo == true) requireNotNull(routeDemo).stateAt(now - routeDemoStartedMs, System.currentTimeMillis())
                        else if (map && shownRoute?.demo == true) NavigationState(position = requireNotNull(shownRoute).origin, bearing = 0f, accuracyMeters = 2f)
                        else DemoNavigation.stateAt(now - start, startEpoch)
                    latestNavigationState = if (map) withRoute(simulated, true) else simulated
                    scheduler.offer(latestNavigationState)
                    val ready = scheduler.takeIfDue(now) ?: if (map && ((mapRetryAt > 0 && now >= mapRetryAt) || consumeForcedRedraw())) latestNavigationState else null
                    if (ready != null) {
                        publishFrame(renderNavigationFrame(ready, mode))
                        scheduler.markRendered(ready, SystemClock.elapsedRealtime())
                    }
                    delay(50)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                latestNavigationState = NavigationState()
                mutableMode.value = NavigationMode.IDLE
                mutableNavigationInfo.value = "Demo detenida: ${error.javaClass.simpleName}"
                android.util.Log.w("NavFrame", "DEMO_FAILED type=${error.javaClass.simpleName}")
                try { publishFrame(navigationRenderer.render(NavigationState(), NavigationMode.IDLE, null)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
        }
    }

    private fun startGps(map: Boolean = false, preserveRoute: Boolean = false) {
        stopVisibleGps()
        if (!preserveRoute) {
            cancelRouteRequest()
            shownRoute = null
            routePlan = null
            guidance = null
            routeDemo = null
            mutableRouteInfo.value = "Sin ruta · cambio de modo"
        }
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mutableNavigationInfo.value = "GPS requiere ubicación precisa; actívala desde la pantalla de NavFrame."
            if (!realRequested && !gpsRequested) stopSelf()
            return
        }
        val manager = getSystemService(android.location.LocationManager::class.java)
        if (!manager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            mutableNavigationInfo.value = "Activa GPS en los ajustes de ubicación y pulsa GPS de nuevo."
            if (!realRequested && !gpsRequested) stopSelf()
            return
        }
        clearNavigation()
        gpsRequested = true
        val mode = if (map) NavigationMode.MAP_GPS else NavigationMode.GPS
        mutableMode.value = mode
        mutableNavigationInfo.value = if (map) "MAPA GPS · esperando posición · sin ruta" else "GPS · SIN MAPA/RUTA · esperando posición"
        try { refreshForeground() }
        catch (error: RuntimeException) {
            gpsRequested = false
            mutableMode.value = NavigationMode.IDLE
            mutableNavigationInfo.value = "No se pudo iniciar GPS: ${error.javaClass.simpleName}"
            refreshForeground()
            return
        }
        navigationJob = scope.launch {
            val provider = AndroidLocationProvider(applicationContext).also { locationProvider = it }
            val scheduler = createScheduler(mode)
            var state = NavigationState(navigationStatus = NavigationStatus.GPS_LOST, bearing = Float.NaN)
            try {
                coroutineScope {
                    launch {
                        provider.locations.collect { fix ->
                            lastGpsFix = fix
                            lastGpsElapsed = provider.lastFixElapsedMs
                            mutableGpsReady.value = hasFreshGpsFix()
                            state = NavigationState(position = fix.position, bearing = fix.bearing, speed = fix.speedMetersPerSecond, navigationStatus = NavigationStatus.NAVIGATING, accuracyMeters = fix.accuracyMeters)
                            scheduler.offer(state)
                        }
                    }
                    provider.start()
                    while (isActive) {
                        val now = SystemClock.elapsedRealtime()
                        mutableGpsReady.value = hasFreshGpsFix()
                        val lost = !provider.providerEnabled.value || provider.lastFixElapsedMs == 0L || now - provider.lastFixElapsedMs > 15_000
                        if (lost && state.navigationStatus != NavigationStatus.GPS_LOST) {
                            state = state.copy(speed = 0f, bearing = Float.NaN, navigationStatus = NavigationStatus.GPS_LOST, accuracyMeters = null)
                        }
                        state = withRoute(state, false).copy(bearing = provider.currentBearing)
                        latestNavigationState = state
                        checkSpeedCameras(state, now)
                        if (state.navigationStatus == NavigationStatus.OFF_ROUTE && reroutePolicy.shouldRequest(state, now)) requestReroute(false)
                        scheduler.offer(state)
                        val ready = scheduler.takeIfDue(now) ?: if (map && ((mapRetryAt > 0 && now >= mapRetryAt) || consumeForcedRedraw())) latestNavigationState else null
                        if (ready != null) {
                            publishFrame(renderNavigationFrame(ready, mode))
                            scheduler.markRendered(ready, SystemClock.elapsedRealtime())
                            if (!map || mapFailures == 0) mutableNavigationInfo.value = if (state.navigationStatus == NavigationStatus.GPS_LOST || lost) "GPS_LOST · esperando señal reciente (máximo 15 s)" else {
                                val heading = ready.bearing.takeIf { it.isFinite() }?.toInt()?.toString() ?: "--"
                                val precision = ready.accuracyMeters?.takeIf { it.isFinite() }?.toInt()?.toString() ?: "--"
                                "${if (map && shownRoute != null) "MAPA GPS · ${ready.navigationStatus}" else if (map) "MAPA GPS · SIN RUTA" else "GPS · SIN MAPA/RUTA"} · ${(ready.speed * 3.6f).toInt()} km/h · $heading° · ± $precision m"
                            }
                        }
                        delay(100)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                rerouteJob?.cancel()
                rerouteJob = null
                navigationEpoch++
                gpsRequested = false
                mutableMode.value = NavigationMode.IDLE
                latestNavigationState = NavigationState()
                mutableNavigationInfo.value = "GPS detenido: ${error.javaClass.simpleName} · pulsa GPS para reintentar"
                try { publishFrame(navigationRenderer.render(NavigationState(), NavigationMode.IDLE, null)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                // Avoid logging error objects that might contain a location payload.
                android.util.Log.w("NavFrame", "GPS_FAILED type=${error.javaClass.simpleName}")
                refreshForeground()
            } finally {
                try { provider.close() } catch (_: SecurityException) { }
                if (locationProvider === provider) locationProvider = null
            }
        }
    }

    fun routingEndpoint(): String = getSharedPreferences("routing", MODE_PRIVATE).getString("endpoint", ValhallaRoutingEngine.DEFAULT_ENDPOINT) ?: ValhallaRoutingEngine.DEFAULT_ENDPOINT
    /** A chosen search result invalidates a prepared plan while retaining ongoing guidance. */
    fun selectSearchDestination() {
        invalidateRoutePlan()
        mutableRouteInfo.value = "Destino cambiado · calcula una nueva ruta. El guiado actual continúa."
    }
    /** Prepared requests never clear the active route/tracker; Start/Stop are the explicit swap points. */
    fun invalidateRoutePlan() {
        previewJob?.cancel()
        if (routePreview && shownRoute != null) {
            shownRoute = null
            latestNavigationState = latestNavigationState.copy(route = null, nextManeuver = null, distanceToNextManeuverMeters = null, remainingDistanceMeters = null, etaEpochMillis = null, navigationStatus = NavigationStatus.IDLE)
        }
        preparedGeneration++
        routeJob?.cancel()
        routeJob = null
        routePlan = null
        preparedDestinationDirty = true
        mutableRouteInfo.value = "Sin plan preparado · el guiado actual continúa"
    }
    fun calculateRoute(destination: GeoPoint, demoOrigin: Boolean, endpoint: String, options: RouteOptions = RouteOptions()) {
        invalidateRoutePlan()
        ValhallaRoutingEngine.validatePoint(destination)
        ValhallaRoutingEngine.validateEndpoint(endpoint)
        val origin = if (demoOrigin) DemoNavigation.stateAt(0).position else {
            val fix = lastGpsFix
            if (fix == null || !hasFreshGpsFix()) {
                mutableRouteInfo.value = "Origen GPS no disponible: inicia GPS y espera una posición reciente."
                return
            }
            fix.position
        }
        val generation = preparedGeneration
        val preferences = getSharedPreferences("routing", MODE_PRIVATE)
        preferences.edit().putString("endpoint", endpoint.trim()).apply()
        mutableRouteInfo.value = "Calculando ruta · origen ${if (demoOrigin) "DEMO simulado" else "GPS real"}…"
        routeJob = scope.launch {
            try {
                val result = routingEngineFactory(endpoint.trim()).calculateRoute(origin, destination, options)
                ensureActive()
                if (generation != preparedGeneration) return@launch
                routePlan = RoutePlan(result, origin, demoOrigin, options)
                preparedDestinationDirty = false
                mutableRouteInfo.value = "Vista previa · ${if (demoOrigin) "origen DEMO" else "origen GPS"} · %.1f km · %d min estimados · %d maniobras\nRevisa la ruta en el mapa e inicia el guiado cuando estés listo. DEMO recorre la geometría calculada; GPS permite recálculo por desvío.".format(java.util.Locale.ROOT, result.distanceMeters / 1000.0, (result.durationSeconds + 59) / 60, result.maneuvers.size)
                if (result.hasUnpaved == true) mutableRouteInfo.value += "\nEl servidor indica tramos sin asfaltar."
                showPreparedPreview()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                mutableRouteInfo.value = "Tiempo de espera agotado · solicita la ruta de nuevo."
            } catch (error: Exception) {
                if (generation == preparedGeneration) mutableRouteInfo.value = "No se pudo calcular: ${if (error is java.io.IOException && error.message?.startsWith("Servidor de rutas: HTTP") == true) error.message else "revisa Internet, endpoint y coordenadas"}"
            }
        }
    }
    fun cancelRouteRequest() {
        preparedGeneration++
        if (rerouteJob?.isActive == true) {
            rerouteJob?.cancel()
            rerouteJob = null
            reroutePolicy.markFinished(SystemClock.elapsedRealtime())
            reroutePaused = true
        }
        routeGeneration++
        routeJob?.cancel()
        routeJob = null
    }
    fun previewPreparedRoute() {
        if (hasActiveGuidance()) { mutableRouteInfo.value = "El guiado actual continúa · Iniciar reemplaza la ruta, Detener permite ver la previa"; return }
        showPreparedPreview()
    }
    fun hasActiveGuidance(): Boolean = shownRoute != null && !routePreview
    private fun showPreparedPreview() {
        val plan = routePlan ?: return
        if (hasActiveGuidance()) return
        previewJob?.cancel()
        clearNavigation()
        mutableMode.value = if (plan.demo) NavigationMode.MAP_DEMO else NavigationMode.MAP_GPS
        refreshForeground()
        shownRoute = plan
        routePreview = true
        val state = NavigationState(position = plan.origin, route = plan.route, navigationStatus = NavigationStatus.ROUTE_PREVIEW, accuracyMeters = if (plan.demo) null else lastGpsFix?.accuracyMeters)
        latestNavigationState = state
        val mode = if (plan.demo) NavigationMode.MAP_DEMO else NavigationMode.MAP_GPS
        val generation = preparedGeneration
        previewJob = scope.launch {
            val frame = renderNavigationFrame(state, mode)
            ensureActive()
            if (generation == preparedGeneration && routePreview) publishFrame(frame)
        }
    }
    /** Import never replaces active guidance before Start; a raw trace never contacts a routing server. */
    fun prepareImportedRoute(imported: ImportedRoute) {
        require(imported.route.imported && !imported.needsRoadRouting)
        RouteGuidanceTracker(imported.route) // Validate before invalidating an existing plan.
        invalidateRoutePlan()
        routePlan = RoutePlan(imported.route, imported.route.geometry.first(), false)
        preparedDestinationDirty = false
        mutableRouteInfo.value = if (imported.route.maneuvers.isNotEmpty())
            "GPX por carreteras · %.1f km · %d min\nIndicaciones de giro activas. Si te desvías, vuelve a la ruta: no se recalcula para respetar tus puntos.".format(java.util.Locale.ROOT, imported.route.distanceMeters / 1000.0, (imported.route.durationSeconds + 59) / 60)
        else "Traza GPX · %.1f km · %d puntos\nSigue el trazado: sin indicaciones de giro ni ETA. Revisa el recorrido antes de iniciar.".format(java.util.Locale.ROOT, imported.route.distanceMeters / 1000.0, imported.route.geometry.size)
        if (!hasActiveGuidance()) showPreparedPreview()
    }
    /** Join GPX route points, waypoints or a sparse track along roads; a detailed track is followed as is. */
    fun calculateImportedStops(imported: ImportedRoute, onReady: () -> Unit = {}) {
        if (!imported.needsRoadRouting) { prepareImportedRoute(imported); onReady(); return }
        invalidateRoutePlan() // Never offer the straight lines between stops as a route.
        val generation = preparedGeneration
        // A sparse recorded track may follow motorways; planned stops keep the scenic default.
        val options = if (imported.isTrack) RouteOptions(profile = RouteProfile.FASTEST, avoidUnpaved = true) else RouteOptions(profile = RouteProfile.AVOID_MOTORWAYS, avoidMotorways = true, avoidUnpaved = true)
        mutableRouteInfo.value = "Calculando la ruta por carreteras · ${imported.stops.size} puntos…"
        routeJob?.cancel()
        routeJob = scope.launch {
            try {
                val result = routingEngineFactory(routingEndpoint()).calculateRouteThrough(imported.stops, options)
                ensureActive()
                if (generation != preparedGeneration) return@launch
                if (result.geometry.size !in 2..100_000) throw IllegalArgumentException("Geometría de ruta inválida")
                prepareImportedRoute(ImportedRoute(imported.name, result.copy(imported = true), isTrack = imported.isTrack))
                onReady()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == preparedGeneration)
                    mutableRouteInfo.value = if (error is RoutingHttpException && error.status == 429) "No se pudo calcular: servidor de rutas ocupado. Espera un minuto y pulsa Reintentar."
                        else "No se pudo calcular la ruta por carreteras. Comprueba la conexión a Internet y pulsa Reintentar."
            }
        }
    }
    /**
     * Before starting an imported route away from its first point, prepend a road leg from the
     * current position. [onReady] receives false when that leg could not be calculated.
     */
    fun prepareImportedApproach(onReady: (Boolean) -> Unit) {
        val plan = routePlan?.takeIf { !preparedDestinationDirty && it.route.imported } ?: return onReady(true)
        val base = plan.importedBase ?: plan.route
        val position = lastGpsFix?.position?.takeIf { hasGuidanceGpsFix() } ?: return onReady(false)
        if (!needsApproach(position, base)) { if (plan.importedBase != null) routePlan = plan.copy(route = base, origin = base.geometry.first(), importedBase = null); return onReady(true) }
        val generation = preparedGeneration
        mutableRouteInfo.value = "Calculando el camino hasta el inicio del GPX…"
        routeJob?.cancel()
        routeJob = scope.launch {
            try {
                val approach = routingEngineFactory(routingEndpoint()).calculateRoute(position, base.geometry.first(), APPROACH_OPTIONS)
                ensureActive()
                if (generation != preparedGeneration || routePlan !== plan) return@launch
                val route = prependApproach(approach, base)
                RouteGuidanceTracker(route) // Validate before replacing the plan.
                routePlan = plan.copy(route = route, origin = position, importedBase = base)
                mutableRouteInfo.value = "Camino hasta el inicio del GPX calculado · %.1f km".format(java.util.Locale.ROOT, approach.distanceMeters / 1000.0)
                onReady(true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (generation == preparedGeneration && routePlan === plan) { routePlan = plan.copy(route = base, origin = base.geometry.first(), importedBase = null); mutableRouteInfo.value = "Sin camino hasta el inicio del GPX · dirígete al primer punto"; onReady(false) }
            }
        }
    }
    /** Positive steps zoom in. Called from the phone and from handlebar requests. */
    fun adjustTftZoom(steps: Int) {
        val next = TftCameraPolicy.stepOffset(tftZoomOffset, steps)
        if (next == tftZoomOffset) { mutableTftZoomInfo.value = if (steps > 0) "Zoom máximo" else "Zoom mínimo"; return }
        tftZoomOffset = next
        getSharedPreferences("tft_camera", MODE_PRIVATE).edit().putFloat("zoom_offset", next.toFloat()).apply()
        mutableTftZoomInfo.value = "Zoom TFT %+.1f".format(java.util.Locale("es", "ES"), next)
        forceMapRedraw = true
        if (routePreview && shownRoute != null && navigationJob?.isActive != true) showPreparedPreview()
    }
    private fun consumeForcedRedraw(): Boolean = forceMapRedraw.also { forceMapRedraw = false }
    private fun onDashboardRequest(service: Int, payloadSize: Int) {
        android.util.Log.i("NavFrame", "NAVILITE_REQUEST service=$service bytes=$payloadSize")
        scope.launch {
            dashboardRequests[service] = (dashboardRequests[service] ?: 0) + 1
            when (service) {
                NaviLiteTransport.ZOOM_IN_REQUEST -> adjustTftZoom(1)
                NaviLiteTransport.ZOOM_OUT_REQUEST -> adjustTftZoom(-1)
            }
        }
    }
    fun preparedRouteIsImported() = hasPreparedRoute() && routePlan?.route?.imported == true
    fun hasPreparedRoute() = !preparedDestinationDirty && routePlan != null
    fun routeUsesGps() = !preparedDestinationDirty && routePlan?.demo == false
    fun startRoute(preview: Boolean = false) {
        previewJob?.cancel()
        if (preview && shownRoute != null && !routePreview) {
            mutableRouteInfo.value = "Detén el guiado para ver la vista previa, o inicia la nueva ruta. El guiado actual continúa."
            return
        }
        if (preparedDestinationDirty) { mutableRouteInfo.value = "Destino cambiado · calcula una nueva ruta primero."; return }
        val plan = routePlan ?: run { mutableRouteInfo.value = "Calcula una ruta primero."; return }
        if (!preview && !plan.demo && !hasGuidanceGpsFix()) { mutableRouteInfo.value = "Espera GPS reciente y preciso antes de iniciar guiado"; return }
        shownRoute = plan
        routePreview = preview
        guidance = RouteGuidanceTracker(plan.route)
        routeDemo = RouteDemo(plan.route)
        routeDemoStartedMs = SystemClock.elapsedRealtime()
        reroutePolicy.reset()
        rerouteFailures = 0
        reroutePaused = false
        approachDone = false
        if (plan.demo) startDemo(true, preserveRoute = true) else startGps(true, preserveRoute = true)
        if (plan.route.imported) {
            mutableRouteInfo.value = if (plan.route.maneuvers.isNotEmpty()) "Ruta GPX iniciada · %.1f km · indicaciones de giro activas".format(java.util.Locale.ROOT, plan.route.distanceMeters / 1000.0)
                else "Traza GPX iniciada · %.1f km · sigue el trazado".format(java.util.Locale.ROOT, plan.route.distanceMeters / 1000.0)
            return
        }
        mutableRouteInfo.value = "${if (preview) "Vista previa" else "Guiado iniciado"} · ${if (plan.demo) "posición DEMO simulada" else "GPS real"} · %.1f km · %d min planificados\nGuiado por geometría · ETA estimada. ${if (plan.demo) "Recorrido DEMO ficticio, sin GPS real ni recálculo automático." else "Recálculo por desvío sostenido; no sustituye señales viales."}".format(java.util.Locale.ROOT, plan.route.distanceMeters / 1000.0, (plan.route.durationSeconds + 59) / 60)
    }
    fun stopRoute() {
        cameraJob?.cancel(); cameraJob = null
        radarAlertExpiryJob?.cancel(); radarAlertExpiryJob = null
        speedCameraAlerts.reset(); mutableRadarAlert.value = null
        previewJob?.cancel()
        rerouteJob?.cancel()
        rerouteJob = null
        guidance = null
        routeDemo = null
        cancelRouteRequest()
        shownRoute = null
        routePlan = null
        mutableRouteInfo.value = "Ruta detenida · GPS/mapa y conexión TFT conservados"
        latestNavigationState = latestNavigationState.copy(route = null, nextManeuver = null, remainingDistanceMeters = null, distanceToNextManeuverMeters = null, etaEpochMillis = null, navigationStatus = if (hasFreshGpsFix()) NavigationStatus.NAVIGATING else NavigationStatus.GPS_LOST)
        if (navigationJob?.isActive != true) {
            val epoch = navigationEpoch
            scope.launch {
                val frame = navigationRenderer.render(NavigationState(), NavigationMode.IDLE, null)
                if (epoch == navigationEpoch && shownRoute == null) publishFrame(frame)
            }
        }
    }
    private fun withRoute(state: NavigationState, demo: Boolean): NavigationState {
        val plan = shownRoute?.takeIf { it.demo == demo }
        if (plan == null) return state.copy(route = null, nextManeuver = null, distanceToNextManeuverMeters = null, remainingDistanceMeters = null, etaEpochMillis = null)
        if (state.navigationStatus == NavigationStatus.GPS_LOST) return state.copy(route = plan.route, nextManeuver = null, distanceToNextManeuverMeters = null, remainingDistanceMeters = null, etaEpochMillis = null)
        if (routePreview) return state.copy(route = plan.route, nextManeuver = null, distanceToNextManeuverMeters = null, navigationStatus = NavigationStatus.ROUTE_PREVIEW)
        val guided = if (demo) state.copy(route = plan.route) else {
            val fix = lastGpsFix ?: return state.copy(route = plan.route, navigationStatus = NavigationStatus.GPS_LOST)
            requireNotNull(guidance).update(fix, SystemClock.elapsedRealtime(), System.currentTimeMillis(), SystemClock.elapsedRealtime() - lastGpsElapsed)
        }
        plan.importedBase?.let { base -> guided.remainingDistanceMeters?.let { if (guided.navigationStatus != NavigationStatus.OFF_ROUTE && it <= base.distanceMeters) approachDone = true } }
        return if (rerouteJob?.isActive == true && guided.navigationStatus != NavigationStatus.GPS_LOST) guided.copy(navigationStatus = NavigationStatus.REROUTING, nextManeuver = null, distanceToNextManeuverMeters = null, remainingDistanceMeters = null, etaEpochMillis = null) else guided
    }

    fun requestReroute(manual: Boolean = true) {
        val plan = shownRoute ?: return
        // Only the leg to an imported route's first point is recalculated; the GPX itself is kept.
        val approachBase = plan.importedBase?.takeIf { plan.route.imported && !approachDone }
        if (plan.route.imported && approachBase == null) {
            if (manual) mutableRouteInfo.value = "Ruta GPX · vuelve al trazado; no se modifica ni recalcula la ruta importada."
            return
        }
        if (plan.demo || routePreview || rerouteJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        val fix = lastGpsFix
        if (fix == null || now - lastGpsElapsed > 15_000 || !fix.accuracyMeters.isFinite() || fix.accuracyMeters > 25f || locationProvider?.providerEnabled?.value != true) {
            mutableRouteInfo.value = "Recálculo espera GPS reciente y precisión ≤25 m"
            return
        }
        if (!manual && (reroutePaused || rerouteFailures >= 3)) return
        if (manual && !reroutePolicy.shouldRequest(latestNavigationState.copy(navigationStatus = NavigationStatus.OFF_ROUTE), now)) {
            mutableRouteInfo.value = "Recálculo en espera · deja al menos 30 s entre intentos"
            return
        }
        if (manual) { reroutePaused = false; rerouteFailures = 0 }
        reroutePolicy.markRequested(now)
        val epoch = navigationEpoch
        val generation = routeGeneration
        mutableRouteInfo.value = "RECALCULANDO · ruta anterior conservada hasta recibir una nueva"
        rerouteJob = scope.launch {
            var retryAfterMs = 0L
            try {
                val engine = routingEngineFactory(routingEndpoint())
                val result = if (approachBase != null) prependApproach(engine.calculateRoute(fix.position, approachBase.geometry.first(), APPROACH_OPTIONS), approachBase)
                    else engine.calculateRoute(fix.position, plan.route.destination ?: plan.route.geometry.last(), plan.options)
                ensureActive()
                if (epoch != navigationEpoch || generation != routeGeneration || shownRoute !== plan) return@launch
                val replacement = RoutePlan(result, fix.position, false, plan.options, approachBase)
                shownRoute = replacement
                if (!preparedDestinationDirty && routePlan === plan) routePlan = replacement
                guidance = RouteGuidanceTracker(result)
                rerouteFailures = 0
                mutableRouteInfo.value = "Ruta recalculada · guiado GPS activo"
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                rerouteFailures++
                mutableRouteInfo.value = "Recálculo sin respuesta · ruta anterior conservada · próximo intento tras 30 s"
            } catch (error: Exception) {
                if (epoch != navigationEpoch || generation != routeGeneration) return@launch
                rerouteFailures++
                if (error is RoutingHttpException && error.status == 429) { reroutePaused = true; retryAfterMs = error.retryAfterMs }
                mutableRouteInfo.value = if (reroutePaused || rerouteFailures >= 3) "Recálculo automático pausado · pulsa Recalcular ahora para reintentar" else "Recálculo fallido · ruta anterior conservada · espera ≥30 s"
            } finally {
                if (epoch == navigationEpoch && generation == routeGeneration) reroutePolicy.markFinished(SystemClock.elapsedRealtime(), retryAfterMs)
            }
        }
    }

    private fun isMapMode(mode: NavigationMode) = mode == NavigationMode.MAP_DEMO || mode == NavigationMode.MAP_GPS
    private fun createScheduler(mode: NavigationMode) = if (isMapMode(mode)) RenderScheduler(RenderScheduleConfig(stoppedMaxFps = .5, movingMaxFps = 1.0, turningMaxFps = 1.0, positionDeltaMeters = 5.0, bearingDeltaDegrees = 5f, redrawStationaryBearing = true)) else RenderScheduler()

    fun mapSource(): TileSource {
        val preferences = getSharedPreferences("map", MODE_PRIVATE)
        if (preferences.contains("attribution")) preferences.edit().remove("attribution").apply() // Provider credits are derived, never user editable.
        return try {
            ConfiguredMapDataSource(preferences.getString("style", "") ?: "", preferences.getString("attribution", ConfiguredMapDataSource.DEFAULT_ATTRIBUTION) ?: ConfiguredMapDataSource.DEFAULT_ATTRIBUTION, applicationContext).tileSource()
        } catch (_: Exception) {
            preferences.edit().remove("style").remove("attribution").apply()
            ConfiguredMapDataSource().tileSource()
        }
    }
    fun diagnosticReport(): String {
        val origin = if (mapSource().uri.startsWith("file:")) "LOCAL PMTiles" else "ONLINE"
        val fix = lastGpsFix
        val accuracy = when { fix == null -> "SIN FIX"; !fix.accuracyMeters.isFinite() -> "DESCONOCIDA"; fix.accuracyMeters <= 5 -> "0–5 m"; fix.accuracyMeters <= 15 -> "6–15 m"; fix.accuracyMeters <= 25 -> "16–25 m"; else -> ">25 m" }
        val age = if (fix == null) "SIN FIX" else if (SystemClock.elapsedRealtime() - lastGpsElapsed <= 15_000) "RECIENTE" else "CADUCADO"
        val network = when (mapRenderer?.networkConnected ?: lastMapNetworkConnected) { true -> "CONECTADA"; false -> "SIN CONEXIÓN"; null -> "SIN COMPROBAR" }
        val historyWarning = if (mapHistoryWriteFailed) "\nHistorial persistente: último evento no guardado (TFT_MAP_HISTORY_WRITE_FAILED)." else ""
        return "NavFrame ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}\nMapLibre OpenGL 13.5.2 · TFT nativo 480×240\nOrigen del mapa: $origin\nRed TFT: $network\n${mutableMapDiagnostic.value}\nÚltimo fallo: $lastMapFailureDiagnostic\nGPS: $age · precisión $accuracy · ${mutableNavigationState.value.navigationStatus}\nTFT: ${mutableState.value} · modo ${mutableMode.value}\nZoom TFT: ${"%+.1f".format(java.util.Locale.ROOT, tftZoomOffset)} · peticiones de la moto: ${dashboardRequests.entries.joinToString { "${it.key}×${it.value}" }.ifEmpty { "ninguna" }}\nSin coordenadas, consultas, direcciones ni URLs.$historyWarning"
    }
    fun isRealSessionRequested(): Boolean = realRequested
    fun isGpsSessionActive(): Boolean = gpsRequested
    fun hasFreshGpsFix(): Boolean = usableGpsFix(AndroidLocationProvider.MAP_ACCURACY_METERS)
    fun usesCompassHeading(): Boolean = (locationProvider ?: visibleGpsProvider)?.headingFromCompass == true
    fun hasGuidanceGpsFix(): Boolean = usableGpsFix(AndroidLocationProvider.GUIDANCE_ACCURACY_METERS)
    private fun usableGpsFix(maxAccuracy: Float): Boolean = lastGpsFix?.let { it.accuracyMeters.isFinite() && it.accuracyMeters in 0f..maxAccuracy && SystemClock.elapsedRealtime() - lastGpsElapsed in 0..15_000 && (locationProvider?.providerEnabled?.value == true || (phoneVisible && visibleGpsProvider?.providerEnabled?.value == true)) } == true
    private fun stopVisibleGps() {
        visibleGpsJob?.cancel(); visibleGpsJob = null
        try { visibleGpsProvider?.close() } catch (_: SecurityException) { }
        visibleGpsProvider = null
    }
    fun setPhoneVisible(visible: Boolean) {
        phoneVisible = visible
        if (!visible || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            stopVisibleGps()
            if (!gpsRequested) mutableGpsReady.value = false
            return
        }
        if (gpsRequested || visibleGpsJob?.isActive == true) return
        visibleGpsJob = scope.launch {
            val provider = AndroidLocationProvider(applicationContext)
            visibleGpsProvider = provider
            try {
                coroutineScope {
                    launch { provider.locations.collect { fix ->
                        lastGpsFix = fix; lastGpsElapsed = provider.lastFixElapsedMs
                        mutableGpsReady.value = hasFreshGpsFix()
                        if (!gpsRequested && mutableMode.value !in listOf(NavigationMode.MAP_DEMO, NavigationMode.DEMO)) {
                            latestNavigationState = latestNavigationState.copy(position = fix.position, bearing = fix.bearing, speed = fix.speedMetersPerSecond, accuracyMeters = fix.accuracyMeters, navigationStatus = if (routePreview && shownRoute != null) NavigationStatus.ROUTE_PREVIEW else if (fix.accuracyMeters <= AndroidLocationProvider.MAP_ACCURACY_METERS) NavigationStatus.NAVIGATING else NavigationStatus.GPS_LOST)
                        }
                    } }
                    launch { provider.bearings.collect { bearing ->
                        if (!gpsRequested && mutableMode.value !in listOf(NavigationMode.MAP_DEMO, NavigationMode.DEMO)) latestNavigationState = latestNavigationState.copy(bearing = bearing)
                    } }
                    provider.start()
                    while (isActive) {
                        mutableGpsReady.value = hasFreshGpsFix()
                        if (!gpsRequested && mutableMode.value !in listOf(NavigationMode.MAP_DEMO, NavigationMode.DEMO)) latestNavigationState = latestNavigationState.copy(bearing = provider.currentBearing)
                        if (!gpsRequested && (!provider.providerEnabled.value || SystemClock.elapsedRealtime() - provider.lastFixElapsedMs > 15_000) && mutableMode.value !in listOf(NavigationMode.MAP_DEMO, NavigationMode.DEMO)) latestNavigationState = latestNavigationState.copy(speed = 0f, bearing = Float.NaN, accuracyMeters = null, navigationStatus = NavigationStatus.GPS_LOST)
                        delay(1_000)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableNavigationInfo.value = "GPS visible no disponible · revisa ubicación y permisos" }
            finally { try { provider.close() } catch (_: SecurityException) { }; if (visibleGpsProvider === provider) visibleGpsProvider = null }
        }
    }
    fun recheckPermissions() {
        if (realRequested && Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) disconnect(manual = false)
        if (gpsRequested && checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) stopNavigation()
    }
    fun saveMapSource(styleUrl: String, attribution: String) {
        val next = ConfiguredMapDataSource(styleUrl, attribution, applicationContext).tileSource()
        val previous = mapSource()
        getSharedPreferences("map", MODE_PRIVATE).edit().putString("style", styleUrl.trim()).remove("attribution").apply()
        if (next == previous) return
        // Release the previous local archive even if restarting GPS fails permission/provider checks.
        mapRenderer?.close(); mapRenderer = null
        releaseMapFallback(); onlineRetryAt = 0
        when (mutableMode.value) {
            NavigationMode.MAP_DEMO -> if (routePreview && shownRoute != null) showPreparedPreview() else startDemo(true, preserveRoute = true)
            NavigationMode.MAP_GPS -> if (routePreview && !gpsRequested && shownRoute != null) showPreparedPreview() else startGps(true, preserveRoute = true)
            else -> { mapRenderer?.close(); mapRenderer = null }
        }
    }
    private suspend fun renderNavigationFrame(state: NavigationState, mode: NavigationMode): TftFrame {
        if (!isMapMode(mode)) return navigationRenderer.render(state, mode, state.accuracyMeters)
        val source = mapSource()
        if (state.navigationStatus == NavigationStatus.GPS_LOST && state.position == GeoPoint(0.0, 0.0)) {
            return MapFrameComposer().compose(null, null, state, mode, source.attribution)
        }
        val now = SystemClock.elapsedRealtime()
        if (mapRetryAt > now) return MapFrameComposer().compose(null, null, state, mode, source.attribution, unavailable = true, failureCode = mutableMapDiagnostic.value.split(" · ").getOrNull(1)).also { unavailableMapFrame = it }
        val online = !source.uri.startsWith("file:")
        if (online && now < onlineRetryAt) offlineFallbackFrame(state, mode)?.let { return it }
        mapRenderStarted = SystemClock.elapsedRealtime()
        mapRenderStage = MapRenderStage.INITIALIZE
        try {
            val frame = mapRenderOverride?.invoke(state, mode) ?: run {
                val current = mapRenderer ?: MapLibreTftRenderer(applicationContext, ConfiguredMapDataSource(if (source.uri.startsWith("asset://")) "" else source.uri, source.attribution, applicationContext), { tftZoomOffset }) { stage ->
                    mapRenderStage = stage
                    mutableMapDiagnostic.value = "TFT MAP · ${stage.name} · ${if (source.uri.startsWith("file:")) "LOCAL" else "ONLINE"}"
                }.also { mapRenderer = it }
                current.render(state, mode)
            }
            onlineRetryAt = 0
            releaseMapFallback()
            if (mapFailures > 0) recordMapEvent(MapDiagnosticKind.RECOVERED, state, mode, source, retryMs = 0)
            mutableMapDiagnostic.value = "TFT MAP · OK · ${SystemClock.elapsedRealtime() - mapRenderStarted} ms · 480×240"
            mapFailures = 0
            mapRetryAt = 0
            unavailableMapFrame = null
            if (mode == NavigationMode.MAP_DEMO) mutableNavigationInfo.value = if (shownRoute == null) "MAPA · posición simulada · sin ruta · OpenStreetMap" else "DEMO FICTICIA · ${state.navigationStatus} · ${state.distanceToNextManeuverMeters?.let { "$it m" } ?: "--"} · geometría calculada"
            return frame
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive() // A local map timeout is recoverable; STOP is not.
            return onlineFailed(state, mode, source, MapRenderException(MapRenderFailure.TIMEOUT, mapRenderStage))
        } catch (error: Exception) {
            return onlineFailed(state, mode, source, error as? MapRenderException ?: MapRenderException(classifyMapRenderFailure(error.message), mapRenderStage, mapErrorDetail(error.message, error.javaClass.simpleName)))
        }
    }
    /**
     * A still snapshot fails as a whole when any tile request fails, which is common with
     * patchy mobile coverage. Prefer an installed offline map over an error frame.
     */
    private suspend fun onlineFailed(state: NavigationState, mode: NavigationMode, source: TileSource, error: MapRenderException): TftFrame {
        if (source.uri.startsWith("file:")) return mapUnavailable(state, mode, source, error)
        android.util.Log.w("NavFrame", "MAP_ONLINE_FAILED code=${error.failure.name} stage=${error.stage.name} detail=${error.detail.safeText()}")
        mapRenderer?.close(); mapRenderer = null
        onlineRetryAt = SystemClock.elapsedRealtime() + ONLINE_RETRY_MS
        lastMapFailureDiagnostic = "TFT MAP · ${error.failure.name} · ${error.stage.name} · ${error.detail.safeText()} · ONLINE"
        val frame = offlineFallbackFrame(state, mode)
        if (frame != null) return frame
        onlineRetryAt = 0
        return mapUnavailable(state, mode, source, error)
    }
    private suspend fun offlineFallbackFrame(state: NavigationState, mode: NavigationMode): TftFrame? {
        val now = SystemClock.elapsedRealtime()
        if (offlineSourcesAt == 0L || now - offlineSourcesAt > 60_000) {
            offlineSourcesAt = now
            offlineSources = try {
                val packs = OfflineMapManager(applicationContext).let { it.refresh(); it.packs.value }
                    .filter { it.state == OfflinePackState.READY }
                    .map { RegionalBounds(it.bounds.south, it.bounds.west, it.bounds.north, it.bounds.east) to it.source }
                val regions = RegionalPackManager(applicationContext).let { it.refresh(); RegionalPackManager.latest(it.regions.value) }
                    .map { it.manifest.bounds to it.source }
                packs + regions
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
        }
        val position = state.position
        val source = offlineSources.firstOrNull { (bounds, _) -> position.latitude in bounds.south..bounds.north && position.longitude in bounds.west..bounds.east }?.second
            ?: return null
        if (fallbackUri != source.uri) releaseMapFallback()
        mapRenderStarted = SystemClock.elapsedRealtime()
        return try {
            val renderer = fallbackRenderer ?: MapLibreTftRenderer(applicationContext, ConfiguredMapDataSource(source.uri, source.attribution, applicationContext), { tftZoomOffset })
                .also { fallbackRenderer = it; fallbackUri = source.uri }
            renderer.render(state, mode).also {
                mutableMapDiagnostic.value = "TFT MAP · OFFLINE DE RESPALDO · ${SystemClock.elapsedRealtime() - mapRenderStarted} ms · reintento online en ${ONLINE_RETRY_MS / 1000} s"
                mapFailures = 0; mapRetryAt = 0; unavailableMapFrame = null
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            releaseMapFallback(); null
        } catch (_: Exception) { releaseMapFallback(); null }
    }
    /** Closes the offline fallback renderer, for example before deleting its archive. */
    fun releaseMapFallback() {
        fallbackRenderer?.close(); fallbackRenderer = null; fallbackUri = null
        // Do not rescan (and reopen) packs that may be in the middle of being deleted.
        offlineSources = emptyList(); offlineSourcesAt = SystemClock.elapsedRealtime()
    }
    private suspend fun mapUnavailable(state: NavigationState, mode: NavigationMode, source: TileSource, error: MapRenderException): TftFrame {
        lastMapNetworkConnected = mapRenderer?.networkConnected ?: lastMapNetworkConnected
        mapRenderer?.close()
        mapRenderer = null
        mapFailures = (mapFailures + 1).coerceAtMost(4)
        val retryMs = (2_000L shl (mapFailures - 1)).coerceAtMost(15_000)
        mapRetryAt = SystemClock.elapsedRealtime() + retryMs
        mutableMapDiagnostic.value = "TFT MAP · ${error.failure.name} · ${error.stage.name} · ${SystemClock.elapsedRealtime() - mapRenderStarted} ms · ${error.detail.safeText()} · intento $mapFailures · reintento ${retryMs / 1000} s"
        lastMapFailureDiagnostic = mutableMapDiagnostic.value + " · " + if (mapSource().uri.startsWith("file:")) "LOCAL" else "ONLINE"
        mutableNavigationInfo.value = "MAPA TFT NO DISPONIBLE · ${error.failure.name} · reintento ${retryMs / 1000} s"
        android.util.Log.w("NavFrame", "MAP_UNAVAILABLE code=${error.failure.name} stage=${error.stage.name} detail=${error.detail.safeText()} retryMs=$retryMs") // No URLs, API tokens or coordinates.
        recordMapEvent(MapDiagnosticKind.FAILURE, state, mode, source, retryMs, error)
        return MapFrameComposer().compose(null, null, state, mode, source.attribution, unavailable = true, failureCode = error.failure.name).also { unavailableMapFrame = it }
    }

    private suspend fun recordMapEvent(kind: MapDiagnosticKind, state: NavigationState, mode: NavigationMode, source: TileSource, retryMs: Long, error: MapRenderException? = null) {
        val network = mapRenderer?.networkConnected ?: lastMapNetworkConnected
        val memory = android.app.ActivityManager.MemoryInfo()
        val memoryAvailable = runCatching { getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory) }.isSuccess
        val interactive = runCatching { getSystemService(android.os.PowerManager::class.java).isInteractive }.getOrNull()
        val event = MapDiagnosticEvent(
            kind = kind, timestampMs = System.currentTimeMillis(),
            source = if (source.uri.startsWith("file:")) MapDiagnosticSource.LOCAL else MapDiagnosticSource.ONLINE,
            network = when (network) { true -> MapDiagnosticNetwork.CONNECTED; false -> MapDiagnosticNetwork.DISCONNECTED; null -> MapDiagnosticNetwork.UNKNOWN },
            stage = error?.stage ?: MapRenderStage.COMPOSE, failure = error?.failure,
            resource = error?.detail?.resource ?: MapResourceKind.UNKNOWN, reason = error?.detail?.reason ?: MapFailureReason.UNKNOWN,
            httpStatus = error?.detail?.httpStatus?.takeIf { it in 400..599 },
            errorClass = error?.detail?.errorClass?.takeIf { it.matches(Regex("[A-Za-z0-9_$]{1,80}")) } ?: "OTHER",
            sdkEvidence = error?.detail?.sdkEvidence.orEmpty(),
            screenInteractive = interactive, lowMemory = if (memoryAvailable) memory.lowMemory else null,
            availableMemoryMb = if (memoryAvailable) (memory.availMem / (1024 * 1024)).coerceAtLeast(0) else null,
            durationMs = (SystemClock.elapsedRealtime() - mapRenderStarted).coerceAtLeast(0), attempts = mapFailures,
            retryMs = retryMs, mode = mode, navigation = state.navigationStatus, connection = mutableState.value,
            androidApi = Build.VERSION.SDK_INT,
        )
        val saved = withContext(Dispatchers.IO) {
            try { mapDiagnosticHistory.record(event) } catch (_: Exception) { false }
        }
        mapHistoryWriteFailed = !saved
        if (!saved) android.util.Log.w("NavFrame", "TFT_MAP_HISTORY_WRITE_FAILED")
    }

    private fun updateNotification(text: String) {
        if (!realRequested || notificationText == text) return
        notificationText = text
        getSystemService(NotificationManager::class.java).notify(1, notification())
    }
    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("tft", "Conexión Yamaha TFT", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TftService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "tft")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("NavFrame · Yamaha TFT")
            .setContentText(if (realRequested) notificationText + when (mutableMode.value) { NavigationMode.GPS, NavigationMode.MAP_GPS -> " · navegando"; NavigationMode.DEMO, NavigationMode.MAP_DEMO -> " · simulación"; NavigationMode.IDLE -> "" } else "GPS activo · sin conexión TFT")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Detener todo", stop).build()).build()
    }

    override fun onDestroy() {
        radarAlertExpiryJob?.cancel(); radarAlertExpiryJob = null; mutableRadarAlert.value = null
        voice?.close(); voice = null
        stopVisibleGps()
        realRequested = false
        cancelRouteRequest()
        rerouteJob?.cancel()
        navigationJob?.cancel()
        mapRenderer?.close()
        mapRenderer = null
        releaseMapFallback()
        try { locationProvider?.close() } catch (_: SecurityException) { }
        locationProvider = null
        scope.cancel() // Cancelling runner closes its socket in NonCancellable cleanup.
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
    companion object {
        private const val ONLINE_RETRY_MS = 30_000L
        const val ACTION_REAL = "dev.navframe.CONNECT_REAL"
        const val ACTION_GPS = "dev.navframe.START_GPS"
        const val ACTION_MAP_GPS = "dev.navframe.START_MAP_GPS"
        private val APPROACH_OPTIONS = RouteOptions(profile = RouteProfile.FASTEST, avoidUnpaved = true)
        const val ACTION_ROUTE_GPS = "dev.navframe.START_ROUTE_GPS"
        const val ACTION_STOP_NAVIGATION = "dev.navframe.STOP_NAVIGATION"
        const val ACTION_STOP = "dev.navframe.DISCONNECT"
        const val EXTRA_AUTO = "auto_connection"
        const val EXTRA_ADDRESS = "device_address"
        const val EXTRA_PREVIEW = "route_preview"
    }
}
