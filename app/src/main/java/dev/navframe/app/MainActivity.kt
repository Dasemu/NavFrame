package dev.navframe.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import dev.navframe.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Navigation-first phone UI. MapView renders service state; the TFT has its own native target. */
open class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observers: Job? = null
    private var searchJob: Job? = null
    private var positionWait: Job? = null
    private var searchGeneration = 0L
    private var updatingDestination = false
    private val placeRepository by lazy { OfflinePlaceRepository(applicationContext) }
    private var placesJob: Job? = null
    private var placesGeneration = 0L
    private var placesDialog: AlertDialog? = null
    private var displayedPlaces: List<OfflinePlace> = emptyList()
    private var service: TftService? = null
    private var bound = false
    private var phoneMap: PhoneMapView? = null
    private var uiStarted = false
    private var uiResumed = false
    private val regionalManager by lazy { RegionalPackManager(applicationContext) }
    private val DEFAULT_REGIONAL_CATALOG = "https://github.com/Dasemu/NavFrame/releases/download/offline-catalog/regional-catalog.json"
    private val regionalDownloads by lazy { RegionalDownloadManager(regionalManager) }
    private val offlineManager by lazy { OfflineMapManager(applicationContext) }
    private var offlineTask: Job? = null
    private var offlineDialog: AlertDialog? = null
    private var routeDialog: AlertDialog? = null
    private var positionNotice: TextView? = null
    private var positionNoticeHide: Runnable? = null
    private var startButton: Button? = null
    private var routeLoading: ProgressBar? = null
    private var retryRouteButton: Button? = null
    private var startRequested = false
    private val unifiedSearch by lazy { UnifiedPlaceSearch(placeRepository) }
    private val viewportPlaces by lazy { OfflineViewportController(scope, placeRepository, { displayedPlaces = it; phoneMap?.setPlaces(it) }) }
    private lateinit var stopButton: Button
    private lateinit var mapHost: FrameLayout
    private lateinit var searchQuery: EditText
    private lateinit var results: LinearLayout
    private lateinit var resultScroll: ScrollView
    private lateinit var hint: TextView
    private lateinit var guidance: TextView
    private lateinit var progress: TextView
    private lateinit var routeInfo: TextView
    private lateinit var connectionInfo: TextView
    private lateinit var permissionInfo: TextView
    private lateinit var credits: TextView
    private lateinit var origin: Spinner
    private lateinit var profileButton: Button
    private val motorcyclePreferences by lazy { getSharedPreferences("yamaha", MODE_PRIVATE) }
    private val routePreferences by lazy { getSharedPreferences("route_preferences", MODE_PRIVATE) }
    private var routeOptions = RouteOptions()
    private val visibleProfiles = listOf(RouteProfile.FASTEST, RouteProfile.AVOID_MOTORWAYS, RouteProfile.AVOID_UNPAVED)
    private var destination: GeoPoint? = null
    private var destinationName = ""
    private enum class Action { POSITION, CALCULATE, ROUTE, PREVIEW, CONNECT, CHANGE_CONNECT }
    private var pendingAction: Action? = null
    private val permissionsPreferences by lazy { getSharedPreferences("permissions", MODE_PRIVATE) }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as TftService.LocalBinder).service
            observeService()
            refreshPermissions()
            tryAutoConnect()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; connectionInfo.text = "●  Yamaha · conectar" }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAction = savedInstanceState?.getString("pending_action")?.let { value -> Action.entries.firstOrNull { it.name == value } }
        routeOptions = RouteOptions(profile = visibleProfiles.firstOrNull { it.name == routePreferences.getString("profile", "AVOID_UNPAVED") } ?: RouteProfile.AVOID_UNPAVED, avoidUnpaved = routePreferences.getBoolean("asphalt", true), avoidMotorways = routePreferences.getBoolean("highways", false))
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(13, 19, 25)) }
        mapHost = FrameLayout(this)
        root.addView(mapHost, FrameLayout.LayoutParams(-1, -1))
        credits = label("© OpenStreetMap", 10f).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3)); setBackgroundColor(0xCC17212D.toInt())
            contentDescription = "Créditos del mapa. Abrir información y licencias"
            setOnClickListener { showInfo() }
        }
        try { phoneMap = createPhoneMap(savedInstanceState?.getBundle("phone_map"))?.also { mapHost.addView(it.view, FrameLayout.LayoutParams(-1, -1)) } }
        catch (_: Exception) { mapHost.addView(label("Mapa no disponible en este dispositivo", 18f)) }
        val top = column().apply { setPadding(dp(12), dp(8), dp(12), 0) }
        val toolbar = row()
        connectionInfo = label("●  Yamaha · conectar", 14f).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL; minHeight = dp(48); setPadding(dp(14), 0, dp(10), 0); background = rounded(0xEE17212D.toInt(), 24); setTextColor(0xFFB5EADA.toInt())
            setOnClickListener { withPermissions(Action.CONNECT) }; contentDescription = "Estado Yamaha. Tocar para conectar"
        }
        toolbar.addView(connectionInfo, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(button("☰") { AlertDialog.Builder(this).setTitle("NavFrame").setItems(arrayOf("Ajustes", "Mapas offline", "Lugares cercanos", "Información y licencias")) { _, item -> when(item) { 0 -> showSettings(); 1 -> showOfflineMaps(); 2 -> showPlaces(); 3 -> showInfo() } }.show() }.apply { contentDescription = "Abrir menú" }, LinearLayout.LayoutParams(dp(56), dp(48)))
        top.addView(toolbar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val searchRow = row().apply { background = rounded(0xF217212D.toInt(), 18); elevation = dp(6).toFloat(); setPadding(dp(8), 0, dp(4), 0) }
        searchQuery = edit("Buscar calles y lugares").apply {
            background = null; setPadding(dp(8), 0, dp(4), 0); isSaveEnabled = false; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { searchDestination(); true } else false }
        }
        searchRow.addView(searchQuery, LinearLayout.LayoutParams(0, dp(56), 1f))
        searchRow.addView(button("⌕") { searchDestination() }.apply { contentDescription = "Buscar destino" }, LinearLayout.LayoutParams(dp(56), dp(56)))
        top.addView(searchRow)
        hint = label("", 13f).apply {
            maxLines = 2; visibility = View.GONE; background = rounded(0xEE17212D.toInt(), 12)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
                override fun afterTextChanged(s: android.text.Editable?) { }
            })
        }
        top.addView(hint)
        val radarNotice = label("", 14f).apply {
            visibility = View.GONE; maxLines = 2; setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(0xEE17212D.toInt(), 12)
            setTextColor(0xFFFFD180.toInt())
        }
        radarBanner = radarNotice
        top.addView(radarNotice)
        results = column().apply { setBackgroundColor(0xF217212D.toInt()) }
        resultScroll = ScrollView(this).apply { addView(results); visibility = View.GONE }
        top.addView(resultScroll, LinearLayout.LayoutParams(-1, dp(180)))
        root.addView(top, FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
        searchQuery.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (updatingDestination) return
                cancelSearch(); positionWait?.cancel(); destination = null; destinationName = ""; routeDialog?.dismiss(); startButton?.isEnabled = false
                if (service?.hasActiveGuidance() != true) service?.selectSearchDestination()
                results.removeAllViews(); resultScroll.visibility = View.GONE; hint.text = ""
                searchOfflineSuggestions(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: android.text.Editable?) { }
        })
        guidance = label("", 20f); progress = label("", 14f); routeInfo = label("", 14f)
        permissionInfo = label("", 12f)
        origin = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("GPS real", "DEMO · posición simulada")) }
        profileButton = button(profileLabel()) { chooseProfile() }
        stopButton = button("STOP · Detener ruta") { positionWait?.cancel(); service?.stopRoute(); routeDialog?.dismiss(); hint.text = ""; stopButton.visibility = View.GONE }.apply { visibility = View.GONE; setTextColor(Color.WHITE); background = rounded(0xEEA83939.toInt(), 18) }
        root.addView(stopButton, FrameLayout.LayoutParams(-1, dp(56), android.view.Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(28)) })
        root.addView(button("◎") { withPermissions(Action.POSITION) }.apply { contentDescription = "Centrar en mi posición y seguir el mapa"; background = rounded(0xFF50D5B6.toInt(), 28); setTextColor(0xFF0D191C.toInt()); elevation = dp(8).toFloat() }, FrameLayout.LayoutParams(dp(56), dp(56), android.view.Gravity.BOTTOM or android.view.Gravity.END).apply { setMargins(0, 0, dp(16), dp(104)) })
        positionNotice = label("", 13f).apply {
            gravity = android.view.Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = rounded(0xDD17212D.toInt(), 16)
            alpha = 0f; visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (Build.VERSION.SDK_INT >= 19) accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(positionNotice, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(24), 0, dp(24), dp(96)) })
        root.addView(credits, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.START).apply { setMargins(dp(8), 0, dp(8), dp(2)) })
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            }
            insets
        }
        setContentView(root)
    }
    /** Allows UI behavior tests to run real Views without invoking the device-only JNI renderer. */
    protected open fun createPhoneMap(savedState: Bundle?): PhoneMapView? = PhoneMapView(this, savedState,
        onAttribution = { if (::credits.isInitialized) credits.text = compactAttribution(it) },
        onPlaceSelected = { chooseDestination(it.position, it.name, "© OpenStreetMap contributors") },
        onError = { hint.text = "Mapa no disponible · revisa Internet o el paquete offline" },
        onViewportChanged = { viewportPlaces.refresh(it) })
    protected open fun hasReliablePosition(): Boolean = service?.hasFreshGpsFix() == true
    protected open fun recenterPhoneMap() { phoneMap?.recenter() }

    override fun onStart() { super.onStart(); uiStarted = true; phoneMap?.onStart(); bound = bindService(Intent(this, TftService::class.java), connection, Context.BIND_AUTO_CREATE) }
    override fun onResume() { super.onResume(); uiResumed = true; phoneMap?.onResume(); refreshPermissions(); tryAutoConnect() }
    override fun onPause() { uiResumed = false; phoneMap?.onPause(); super.onPause() }
    override fun onStop() { uiStarted = false; hidePositionNotice(); placesDialog?.dismiss(); placesJob?.cancel(); placesGeneration++; viewportPlaces.cancel(); service?.setPhoneVisible(false); cancelSearch(); positionWait?.cancel(); observers?.cancel(); if (bound) unbindService(connection); bound = false; service = null; phoneMap?.onStop(); super.onStop() }
    override fun onDestroy() { hidePositionNotice(); routeDialog?.dismiss(); viewportPlaces.cancel(); scope.cancel(); phoneMap?.onDestroy(); phoneMap = null; super.onDestroy() }
    override fun onLowMemory() { super.onLowMemory(); phoneMap?.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) { val map = Bundle(); phoneMap?.onSaveInstanceState(map); outState.putBundle("phone_map", map); pendingAction?.let { outState.putString("pending_action", it.name) }; super.onSaveInstanceState(outState) }
    private var radarBanner: TextView? = null
    private fun observeService() {
        observers?.cancel()
        val current = service ?: return
        phoneMap?.loadStyle(current.mapSource()); phoneMap?.refreshPlaces(); credits.text = compactAttribution(current.mapSource().attribution)
        observers = scope.launch {
            launch { current.radarAlert.collect { alert ->
                radarBanner?.text = alert.orEmpty()
                radarBanner?.visibility = if (alert == null) View.GONE else View.VISIBLE
            } }
            launch { current.connectionState.collect { connectionInfo.text = "●  Yamaha · " + when(it) { ConnectionState.CONNECTED -> "conectada"; ConnectionState.CONNECTING, ConnectionState.HANDSHAKING -> "conectando"; ConnectionState.ERROR -> "reintentar"; else -> "conectar" } } }
            launch { current.routeInfo.collect { routeInfo.text = it; startButton?.isEnabled = current.hasPreparedRoute(); routeLoading?.visibility = if (it.startsWith("Calculando")) View.VISIBLE else View.GONE; retryRouteButton?.visibility = if (it.startsWith("No se pudo") || it.startsWith("Tiempo de espera")) View.VISIBLE else View.GONE } }
            launch { current.navigationState.collect { state ->
                phoneMap?.update(state)
                stopButton.visibility = if (current.hasActiveGuidance()) View.VISIBLE else View.GONE
                if (startRequested && current.hasActiveGuidance()) { routeDialog?.dismiss(); startRequested = false }

                val demo = current.navigationMode.value == NavigationMode.MAP_DEMO || current.navigationMode.value == NavigationMode.DEMO
                guidance.text = when (state.navigationStatus) {
                    NavigationStatus.GPS_LOST -> "Sin GPS reciente y preciso"
                    NavigationStatus.REROUTING -> "Recalculando ruta…"
                    NavigationStatus.OFF_ROUTE -> "Fuera de ruta"
                    NavigationStatus.ARRIVED -> "Has llegado al destino"
                    NavigationStatus.ROUTE_PREVIEW -> "Vista previa de ruta"
                    NavigationStatus.IDLE -> "Listo para navegar"
                    else -> state.nextManeuver?.instruction ?: if (state.route == null) "GPS listo · elige un destino" else "Continúa por la ruta"
                }.let { if (demo) "DEMO · $it" else it }
                val next = state.distanceToNextManeuverMeters?.let { "$it m" } ?: ""
                val remaining = state.remainingDistanceMeters?.let { "%.1f km restantes".format(java.util.Locale.ROOT, it / 1000.0) } ?: ""
                val eta = state.etaEpochMillis?.let { "ETA " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(it)) } ?: ""
                val accuracy = state.accuracyMeters?.takeIf { it.isFinite() }?.let { "GPS ±${it.toInt()} m" } ?: ""
                progress.text = listOf(next, remaining, eta, accuracy).filter { it.isNotEmpty() }.joinToString(" · ")
            } }
        }
    }
    private fun withPermissions(action: Action) {
        if (service == null) { hint.text = "Espera la conexión con el servicio"; return }
        val needsLocation = action == Action.POSITION || (action == Action.CALCULATE && origin.selectedItemPosition == 0) || (action == Action.ROUTE && service?.routeUsesGps() == true)
        val required = mutableListOf<String>()
        if ((action == Action.CONNECT || action == Action.CHANGE_CONNECT) && Build.VERSION.SDK_INT >= 31) required += Manifest.permission.BLUETOOTH_CONNECT
        if (needsLocation) required += Manifest.permission.ACCESS_FINE_LOCATION
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        val blocked = missing.any { permissionsPreferences.getBoolean(it, false) && !shouldShowRequestPermissionRationale(it) }
        if (blocked) { AlertDialog.Builder(this).setTitle("Permiso necesario").setMessage("Activa ubicación precisa o dispositivos cercanos para esta función. Puedes seguir explorando mapas y usando la demo.").setPositiveButton("Abrir ajustes") { _, _ -> openAppSettings() }.setNegativeButton("Cancelar", null).show(); return }
        val request = missing.toMutableList()
        if (missing.contains(Manifest.permission.ACCESS_FINE_LOCATION)) request += Manifest.permission.ACCESS_COARSE_LOCATION
        if ((action == Action.CONNECT || action == Action.CHANGE_CONNECT || (action == Action.ROUTE && service?.routeUsesGps() == true)) && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !permissionsPreferences.getBoolean(Manifest.permission.POST_NOTIFICATIONS, false)) request += Manifest.permission.POST_NOTIFICATIONS
        if (request.isEmpty()) { perform(action); return }
        val ask = {
            pendingAction = action
            request.forEach { permissionsPreferences.edit().putBoolean(it, true).apply() }
            requestPermissions(request.distinct().toTypedArray(), 42)
        }
        if (missing.any { shouldShowRequestPermissionRationale(it) }) AlertDialog.Builder(this).setTitle("Permisos de navegación").setMessage("GPS precisa tu posición para guiarte; Bluetooth conecta la Yamaha. La sesión usa una notificación mientras navegas.").setPositiveButton("Continuar") { _, _ -> ask() }.setNegativeButton("Cancelar", null).show() else ask()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 42) return
        val action = pendingAction; pendingAction = null
        refreshPermissions()
        val needsFine = action == Action.POSITION || (action == Action.CALCULATE && origin.selectedItemPosition == 0) || (action == Action.ROUTE && service?.routeUsesGps() == true)
        val allowed = (!needsFine || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) && ((action != Action.CONNECT && action != Action.CHANGE_CONNECT) || Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
        if (action != null && allowed) perform(action) else hint.text = "Permiso denegado · puedes usar la demo. Toca el aviso de permisos para abrir ajustes."
    }
    private fun refreshPermissions() {
        if (!::permissionInfo.isInitialized) return
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) missing += "GPS preciso"
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) missing += "Bluetooth"
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) missing += "notificaciones"
        permissionInfo.text = if (missing.isEmpty()) "Permisos listos" else "Falta ${missing.joinToString(", ")} · tocar para ajustes"
        service?.recheckPermissions()
        service?.setPhoneVisible(uiStarted && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    private fun perform(action: Action) {
        when (action) {
            Action.POSITION -> {
                val current = service
                current?.setPhoneVisible(true)
                if (hasReliablePosition()) { hidePositionNotice(); recenterPhoneMap() }
                else showPositionNotice(when {
                    current == null || !current.isGpsSessionActive() && current.navigationState.value.accuracyMeters == null -> "Buscando señal GPS…"
                    current.navigationState.value.accuracyMeters?.let { !it.isFinite() || it > 25f } == true -> "Señal GPS poco precisa"
                    else -> "Esperando una posición GPS reciente"
                })
            }
            Action.CONNECT -> if (!connectRemembered(false)) chooseMotorcycle()
            Action.CHANGE_CONNECT -> chooseMotorcycle()
            Action.CALCULATE -> calculateRoute()
            Action.ROUTE, Action.PREVIEW -> {
                if (action == Action.ROUTE && service?.hasPreparedRoute() != true) return
                if (action == Action.ROUTE) startRequested = true
                if (action == Action.PREVIEW) service?.previewPreparedRoute()
                else if (service?.routeUsesGps() == true) try { startForegroundService(Intent(this, TftService::class.java).setAction(TftService.ACTION_ROUTE_GPS).putExtra(TftService.EXTRA_PREVIEW, action == Action.PREVIEW)) } catch (_: RuntimeException) { hint.text = "No se pudo iniciar · revisa permisos de ubicación" }
                else service?.startRoute(action == Action.PREVIEW)
                if (action == Action.ROUTE && service?.hasActiveGuidance() == true) routeDialog?.dismiss()
            }
        }
    }
    private fun showPositionNotice(message: String) {
        val notice = positionNotice ?: return
        positionNoticeHide?.let(notice::removeCallbacks)
        positionNoticeHide = null
        notice.text = message
        notice.visibility = View.VISIBLE
        notice.animate().cancel(); notice.alpha = 0f
        notice.animate().alpha(1f).setDuration(140).start()
        val hide = Runnable {
            notice.animate().alpha(0f).setDuration(220).withEndAction { if (notice.alpha == 0f) notice.visibility = View.GONE }.start()
            positionNoticeHide = null
        }
        positionNoticeHide = hide
        notice.postDelayed(hide, 2600)
    }
    private fun hidePositionNotice() {
        positionNotice?.let { notice ->
            positionNoticeHide?.let(notice::removeCallbacks)
            positionNoticeHide = null
            notice.animate().cancel(); notice.alpha = 0f; notice.visibility = View.GONE
        }
    }
    private fun startGps() {
        try { startForegroundService(Intent(this, TftService::class.java).setAction(TftService.ACTION_MAP_GPS)) }
        catch (_: RuntimeException) { hint.text = "No se pudo iniciar GPS · revisa permisos y ajustes de ubicación" }
    }
    private fun calculateRoute() {
        positionWait?.cancel()
        val target = destination ?: run { hint.text = "Elige primero un resultado de búsqueda"; return }
        val current = service ?: return
        startButton?.isEnabled = false
        if (origin.selectedItemPosition == 1) { current.calculateRoute(target, true, current.routingEndpoint(), routeOptions); return }
        if (current.hasFreshGpsFix()) { current.calculateRoute(target, false, current.routingEndpoint(), routeOptions); return }
        current.setPhoneVisible(true); hint.text = "Esperando una posición GPS reciente…"; routeInfo.text = "Esperando una posición GPS reciente…"; routeLoading?.visibility = View.VISIBLE
        positionWait?.cancel()
        positionWait = scope.launch {
            try {
                withTimeout(20_000) { current.gpsReady.first { it && current.hasFreshGpsFix() } }
                ensureActive()
                current.calculateRoute(target, false, current.routingEndpoint(), routeOptions)
            } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); hint.text = "Sin GPS · activa ubicación y prueba de nuevo"; routeInfo.text = hint.text; routeLoading?.visibility = View.GONE; retryRouteButton?.visibility = View.VISIBLE }
        }
    }
    private fun cancelSearch() { searchGeneration++; searchJob?.cancel(); searchJob = null }
    private fun searchDestination() {
        cancelSearch(); results.removeAllViews(); resultScroll.visibility = View.GONE
        val query = searchQuery.text.toString(); val generation = searchGeneration
        val endpoint = getSharedPreferences("geocoder", MODE_PRIVATE).getString("endpoint", NominatimGeocoder.DEFAULT_ENDPOINT) ?: NominatimGeocoder.DEFAULT_ENDPOINT
        hint.text = "Buscando…"
        searchJob = scope.launch {
            try {
                val response = unifiedSearch.searchExplicit(searchCenter(), query, NominatimGeocoder(endpoint))
                val found = response.results
                ensureActive(); if (generation != searchGeneration) return@launch
                hint.text = if (found.isEmpty()) "Sin resultados · añade ciudad o dirección" else "Elige un destino · © OpenStreetMap contributors"
                resultScroll.visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
                found.forEach { result -> results.addView(button("${result.name}\n${result.sourceLabel}") {
                    chooseDestination(result.position, result.name, result.attribution)
                }.apply { maxLines = 3 }) }
            } catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); hint.text = "Búsqueda sin respuesta · vuelve a pulsar Buscar" }
            catch (error: Exception) { if (generation == searchGeneration) hint.text = if (error is java.io.IOException && error.message?.startsWith("Proveedor") == true) error.message else "No se pudo buscar · revisa consulta, Internet y proveedor" }
        }
    }
    private fun searchCenter() = phoneMap?.center() ?: service?.navigationState?.value?.position?.takeIf { it != GeoPoint(0.0, 0.0) } ?: GeoPoint(43.36, -5.85)
    private fun searchOfflineSuggestions(query: String) {
        if (query.trim().length < 2) return
        val generation = searchGeneration
        searchJob = scope.launch {
            delay(250)
            val found = try { unifiedSearch.searchOffline(searchCenter(), query).take(20) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { emptyList() }
            ensureActive(); if (generation != searchGeneration) return@launch
            results.removeAllViews(); resultScroll.visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
            found.forEach { result -> results.addView(button("${result.name}\n${result.sourceLabel}") { chooseDestination(result.position, result.name, result.attribution) }) }
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus && uiResumed) tryAutoConnect() }
    private fun tryAutoConnect() {
        if (!uiResumed || service?.isRealSessionRequested() != false || !motorcyclePreferences.getBoolean("auto", true) || motorcyclePreferences.getBoolean("paused", false)) return
        connectRemembered(true)
    }
    private fun connectRemembered(automatic: Boolean, replace: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return false
        val address = motorcyclePreferences.getString("address", null) ?: return false
        try {
            val adapter = getSystemService(BluetoothManager::class.java).adapter ?: return false
            if (!adapter.isEnabled || adapter.bondedDevices.none { it.address == address }) return false
            if (service?.isRealSessionRequested() == true && !replace) return true
            if (!automatic) motorcyclePreferences.edit().putBoolean("paused", false).apply()
            startForegroundService(Intent(this, TftService::class.java).setAction(TftService.ACTION_REAL).putExtra(TftService.EXTRA_ADDRESS, address).putExtra(TftService.EXTRA_AUTO, automatic))
            return true
        } catch (_: RuntimeException) { hint.text = "No se pudo conectar Yamaha · revisa Bluetooth y permisos"; return false }
    }
    private fun profileLabel(): String = when (routeOptions.profile) { RouteProfile.FASTEST -> "Rápido"; RouteProfile.AVOID_MOTORWAYS -> "Evitar autopistas"; RouteProfile.TOURING -> "Paseo"; RouteProfile.AVOID_UNPAVED -> "Preferir asfalto" } + if (routeOptions.avoidUnpaved && routeOptions.profile != RouteProfile.AVOID_UNPAVED) " · asfalto" else ""
    private fun chooseProfile() {
        val box = column().apply { setPadding(dp(16), 0, dp(16), 0) }
        val select = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Rápido", "Evitar autopistas", "Preferir asfalto")); setSelection(visibleProfiles.indexOf(routeOptions.profile).coerceAtLeast(0)) }
        val asphalt = CheckBox(this).apply { text = "Priorizar asfalto"; isChecked = routeOptions.avoidUnpaved }
        val highways = CheckBox(this).apply { text = "Preferir menos autopistas"; isChecked = routeOptions.avoidMotorways }
        box.addView(select); box.addView(asphalt); box.addView(highways); box.addView(label("Son preferencias del servidor: no garantizan excluir autopistas o tierra. Revisa la ruta y las señales.", 14f))
        AlertDialog.Builder(this).setTitle("Perfil de ruta en moto").setView(box).setPositiveButton("Aplicar") { _, _ ->
            routeOptions = RouteOptions(visibleProfiles[select.selectedItemPosition], avoidUnpaved = asphalt.isChecked, avoidMotorways = highways.isChecked)
            routePreferences.edit().putString("profile", routeOptions.profile.name).putBoolean("asphalt", routeOptions.avoidUnpaved).putBoolean("highways", routeOptions.avoidMotorways).apply()
            profileButton.text = profileLabel()
            startButton?.isEnabled = false
            service?.invalidateRoutePlan()
            if (destination != null) withPermissions(Action.CALCULATE)
        }.setNegativeButton("Cancelar", null).show()
    }
    private fun chooseDestination(point: GeoPoint, name: String, attribution: String) {
        val current = service ?: run { hint.text = "Espera la conexión con el servicio"; return }
        cancelSearch(); positionWait?.cancel()
        updatingDestination = true
        try { searchQuery.setText(name.take(200)) } finally { updatingDestination = false }
        startRequested = false
        current.selectSearchDestination(); startButton?.isEnabled = false; destination = point; destinationName = name
        hint.text = "Destino: $name · $attribution"
        results.removeAllViews(); resultScroll.visibility = View.GONE
        showRouteSheet()
        withPermissions(Action.CALCULATE)
    }
    private fun compactAttribution(value: String): String = value.replace(" contributors", "").replace("Fuente online predeterminada incluida: ", "").take(150)
    private fun showRouteSheet() {
        routeDialog?.dismiss()
        (origin.parent as? android.view.ViewGroup)?.removeView(origin)
        (profileButton.parent as? android.view.ViewGroup)?.removeView(profileButton)
        (routeInfo.parent as? android.view.ViewGroup)?.removeView(routeInfo)
        val box = column().apply { setPadding(dp(16), dp(8), dp(16), dp(12)) }
        box.addView(label(destinationName, 22f))
        box.addView(label("Perfil de ruta", 14f)); box.addView(profileButton)
        box.addView(label("Origen", 14f)); box.addView(origin)
        var previousOrigin = origin.selectedItemPosition
        origin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (previousOrigin == position) return
                previousOrigin = position; startButton?.isEnabled = false; service?.invalidateRoutePlan(); withPermissions(Action.CALCULATE)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }
        routeLoading = ProgressBar(this).apply { visibility = View.GONE; contentDescription = "Calculando ruta" }; box.addView(routeLoading)
        box.addView(routeInfo)
        retryRouteButton = button("Reintentar cálculo") { withPermissions(Action.CALCULATE) }.apply { visibility = View.GONE }; box.addView(retryRouteButton)
        startButton = button("Iniciar") { withPermissions(Action.ROUTE) }.apply { background = rounded(0xFF50D5B6.toInt(), 16); setTextColor(0xFF0D191C.toInt()); isEnabled = service?.hasPreparedRoute() == true }; box.addView(startButton)
        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Cerrar", null).create()
        routeDialog = dialog; dialog.show()
        dialog.window?.apply { setBackgroundDrawable(rounded(0xFF17212D.toInt(), 24)); setGravity(android.view.Gravity.BOTTOM); setLayout(-1, -2); setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(searchQuery.windowToken, 0)
        searchQuery.clearFocus()
    }
    private fun showPlaces() {
        placesDialog?.dismiss()
        val center = phoneMap?.center() ?: service?.navigationState?.value?.position?.takeIf { it != GeoPoint(0.0, 0.0) } ?: GeoPoint(43.36, -5.85)
        val box = column().apply { setPadding(dp(16), 0, dp(16), 0) }
        box.addView(label("Lugares offline OSM · Asturias incluido y regiones instaladas. Distancia en línea recta desde el centro del mapa. Elegir destino calcula la ruta por Internet.", 13f))
        val category = Spinner(this).apply { adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Todos") + PlaceCategory.entries.map { it.labelSpanish }) }
        val query = edit("Nombre del lugar o negocio").apply { isSaveEnabled = false }
        val status = label("Buscando en el catálogo local…", 13f)
        val list = column()
        box.addView(category); box.addView(query); box.addView(status)
        box.addView(button("Ocultar marcadores") { placesGeneration++; placesJob?.cancel(); displayedPlaces = emptyList(); phoneMap?.setPlaces(emptyList()) })
        box.addView(list)
        val scroll = ScrollView(this).apply { addView(box) }
        val dialog = AlertDialog.Builder(this).setTitle("Lugares cerca del mapa").setView(scroll).setPositiveButton("Cerrar", null).create()
        placesDialog = dialog
        fun refresh(debounce: Boolean) {
            placesJob?.cancel(); val generation = ++placesGeneration
            val text = query.text.toString()
            val selected = PlaceCategory.entries.getOrNull(category.selectedItemPosition - 1)
            placesJob = scope.launch {
                try {
                    if (debounce) delay(250)
                    status.text = "Consultando catálogo offline…"
                    val found = placeRepository.search(center, selected, text)
                    ensureActive(); if (generation != placesGeneration) return@launch
                    displayedPlaces = found; phoneMap?.setPlaces(found)
                    list.removeAllViews()
                    status.text = when {
                        !placeRepository.covers(center) -> "Sin catálogo instalado para este centro del mapa"
                        found.isEmpty() -> "Sin resultados en los datos OSM incluidos"
                        else -> "${found.size} lugares · ${if (text.isBlank()) "hasta 50 km" else "regiones instaladas"} · © OpenStreetMap contributors"
                    }
                    found.forEach { place ->
                        val distance = placeDistance(place)
                        list.addView(button("${place.name}\n${place.category.labelSpanish} · $distance${place.address?.let { " · $it" } ?: ""}") { showPlaceDetails(place) }.apply { maxLines = 3 })
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (generation == placesGeneration) status.text = "No se pudo leer el catálogo local · abre Lugares de nuevo" }
            }
        }
        category.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { refresh(false) }
            override fun onNothingSelected(parent: AdapterView<*>?) { }
        }
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(true) }
            override fun afterTextChanged(s: android.text.Editable?) { }
        })
        dialog.setOnDismissListener { placesGeneration++; placesJob?.cancel(); if (placesDialog === dialog) placesDialog = null }
        dialog.show(); refresh(false)
    }
    private fun placeDistance(place: OfflinePlace): String = place.distanceMeters?.let { if (it < 1000) "${it.toInt()} m" else "%.1f km".format(java.util.Locale.ROOT, it / 1000) } ?: "distancia no disponible"
    private fun showPlaceDetails(place: OfflinePlace) {
        val text = listOfNotNull(place.category.labelSpanish + " · " + placeDistance(place) + " en línea recta", place.address?.let { "Dirección OSM: $it" }, place.openingHours?.let { "Horario OSM: $it\nNo se comprueba si está abierto ahora." }, place.website?.let { "Web OSM: $it" }, place.phone?.let { "Teléfono OSM: $it" }, "© OpenStreetMap contributors · extracto 28/09/2026. Los datos pueden faltar o haber cambiado.").joinToString("\n\n")
        val box = column().apply { setPadding(dp(16), 0, dp(16), 0); addView(label(text, 14f)) }
        place.website?.let { value ->
            val uri = runCatching { Uri.parse(value.trim()) }.getOrNull()
            if (uri?.scheme?.lowercase() in listOf("http", "https") && !uri?.host.isNullOrBlank()) box.addView(button("Abrir web") { try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: ActivityNotFoundException) { hint.text = "No hay navegador disponible" } })
        }
        place.phone?.takeIf { it.length <= 100 && it.matches(Regex("[+0-9() .;/-]+")) }?.let { value -> box.addView(button("Marcar teléfono") { try { startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", value, null))) } catch (_: ActivityNotFoundException) { hint.text = "No hay aplicación de teléfono" } }) }
        AlertDialog.Builder(this).setTitle(place.name).setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Elegir destino") { _, _ ->
            placesDialog?.dismiss()
            chooseDestination(place.position, place.name, "© OpenStreetMap contributors")
        }.setNegativeButton("Cerrar", null).show()
    }
    private fun chooseMotorcycle() {
        try {
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            if (adapter == null || !adapter.isEnabled) { hint.text = "Activa Bluetooth para conectar Yamaha"; startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return }
            val paired = adapter.bondedDevices.sortedBy { it.name ?: "" }
            if (paired.isEmpty()) { hint.text = "Empareja Yamaha en los ajustes Bluetooth"; startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); return }
            AlertDialog.Builder(this).setTitle("Conectar Yamaha").setItems(paired.map { it.name ?: "Dispositivo emparejado" }.toTypedArray()) { _, which ->
                motorcyclePreferences.edit().putString("address", paired[which].address).putString("name", paired[which].name ?: "Yamaha").putBoolean("auto", true).putBoolean("paused", false).apply()
                connectRemembered(false, replace = true)
            }.setNegativeButton("Cerrar", null).setNeutralButton("Desconectar TFT") { _, _ -> service?.disconnect() }.show()
        } catch (_: SecurityException) { refreshPermissions(); hint.text = "Bluetooth necesita permiso de dispositivos cercanos" }
    }
    private fun showSettings() {
        val current = service ?: return
        val content = column().apply { setPadding(dp(16), 0, dp(16), 0) }
        fun entry(title: String, initial: String): EditText { content.addView(label(title, 14f)); return edit(title).apply { setText(initial); content.addView(this) } }
        content.addView(Switch(this).apply {
            text = "Indicaciones de voz"
            isChecked = current.voiceEnabled.value
            setOnCheckedChangeListener { _, enabled -> current.setVoiceEnabled(enabled) }
        })
        content.addView(button("Idioma de voz y ruta · ${current.voiceLanguageSelection()}") {
            val selections = listOf(NavigationVoicePreferences.SYSTEM) + GuidanceLanguage.entries.map { it.tag }
            val names = listOf("Idioma del sistema / System language") + GuidanceLanguage.entries.map { it.nativeName }
            AlertDialog.Builder(this).setTitle("Idioma de navegación / Navigation language")
                .setSingleChoiceItems(names.toTypedArray(), selections.indexOf(current.voiceLanguageSelection())) { dialog, index ->
                    current.setVoiceLanguage(selections[index])
                    hint.text = "Idioma guardado · se aplicará a la próxima ruta / Applies to the next route"
                    dialog.dismiss()
                }.setNegativeButton("Cerrar", null).show()
        })
        val voiceStatusLabel = label(current.voiceStatus.value, 13f)
        content.addView(voiceStatusLabel)
        content.addView(button("Configurar voz de Android") {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
            catch (_: ActivityNotFoundException) { openAppSettings() }
        })
        val routeEndpoint = entry("Servidor Valhalla HTTPS", current.routingEndpoint())
        val geocoderEndpoint = entry("Servidor búsqueda HTTPS", getSharedPreferences("geocoder", MODE_PRIVATE).getString("endpoint", NominatimGeocoder.DEFAULT_ENDPOINT) ?: NominatimGeocoder.DEFAULT_ENDPOINT)
        val catalogPreferences = getSharedPreferences("regional_downloads", MODE_PRIVATE)
        val catalogUrl = entry("Catálogo de regiones HTTPS · repositorio de confianza", catalogPreferences.getString("url", DEFAULT_REGIONAL_CATALOG) ?: DEFAULT_REGIONAL_CATALOG)
        content.addView(button("Guardar catálogo de regiones") {
            try {
                val address = catalogUrl.text.toString().trim()
                if (address.isNotEmpty()) RegionalDownloadManager.validateAddress(address)
                catalogPreferences.edit().putString("url", address).apply()
                hint.text = "Catálogo guardado"
            } catch (_: Exception) { hint.text = "Revisa la URL HTTPS del catálogo" }
        })
        val source = current.mapSource()
        val style = entry("Estilo de mapa HTTPS · vacío para predeterminado", if (source.uri.startsWith("asset://")) "" else source.uri)
        content.addView(label("Créditos obligatorios del proveedor: ${source.attribution} (no editables)", 13f))
        content.addView(button("Guardar servidores y mapa") {
            try {
                ValhallaRoutingEngine.validateEndpoint(routeEndpoint.text.toString()); NominatimGeocoder.validateEndpoint(geocoderEndpoint.text.toString())
                getSharedPreferences("routing", MODE_PRIVATE).edit().putString("endpoint", routeEndpoint.text.toString().trim()).apply()
                getSharedPreferences("geocoder", MODE_PRIVATE).edit().putString("endpoint", geocoderEndpoint.text.toString().trim()).apply()
                current.saveMapSource(style.text.toString(), source.attribution); phoneMap?.loadStyle(current.mapSource()); phoneMap?.refreshPlaces(); credits.text = compactAttribution(current.mapSource().attribution)
                hint.text = "Configuración guardada"
            } catch (_: Exception) { hint.text = "Revisa endpoints y estilo HTTPS" }
        })
        content.addView(label("Yamaha: ${motorcyclePreferences.getString("name", "sin seleccionar")}", 14f))
        content.addView(Switch(this).apply { text = "Conectar Yamaha automáticamente"; isChecked = motorcyclePreferences.getBoolean("auto", true); setOnCheckedChangeListener { _, enabled -> motorcyclePreferences.edit().putBoolean("auto", enabled).apply(); if (enabled) { motorcyclePreferences.edit().putBoolean("paused", false).apply(); tryAutoConnect() } } })
        content.addView(button("Cambiar Yamaha") { withPermissions(Action.CHANGE_CONNECT) })
        content.addView(button("Olvidar Yamaha") { service?.disconnect(); motorcyclePreferences.edit().remove("address").remove("name").apply(); hint.text = "Yamaha olvidada" })
        content.addView(button("Desconectar Yamaha") { service?.disconnect() })
        content.addView(button("Mapas offline") { showOfflineMaps() })
        content.addView(button("Permisos de NavFrame") { openAppSettings() })
        val alertPreferences = getSharedPreferences("navigation_alerts", MODE_PRIVATE)
        content.addView(Switch(this).apply {
            text = "Avisos de posibles radares fijos"
            isChecked = alertPreferences.getBoolean("radars_enabled", false)
            setOnCheckedChangeListener { _, enabled -> alertPreferences.edit().putBoolean("radars_enabled", enabled).apply() }
        })
        content.addView(label("Datos comunitarios OSM, incompletos. Distancia aproximada; comprueba la normativa del país y respeta siempre las señales. © OpenStreetMap contributors · ODbL", 12f))
        content.addView(button("Ajustes de ubicación") { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
        content.addView(button("Batería · optimización") { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: ActivityNotFoundException) { openAppSettings() } })
        content.addView(label("Para pantalla apagada en Xiaomi, permite batería sin restricciones. La app no cambia estos ajustes.", 13f))
        content.addView(button("Opciones avanzadas") { showAdvanced() })
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = AlertDialog.Builder(this).setTitle("Configuración").setView(scroll).setPositiveButton("Cerrar", null).create()
        val voiceObserver = scope.launch { current.voiceStatus.collect { voiceStatusLabel.text = it } }
        dialog.setOnDismissListener { voiceObserver.cancel() }
        dialog.show()
    }
    private fun showAdvanced() {
        val choices = arrayOf("Conectar simulador TFT", "Prueba TFT nativa", "Navegación sintética", "Mapa DEMO sin ruta", "Recalcular ruta GPS", "Detener toda navegación", "Keepalive TFT 0,5 s", "Keepalive TFT 1 s", "Keepalive TFT 2 s", "Introducir destino por coordenadas", "Ver imagen TFT")
        AlertDialog.Builder(this).setTitle("Opciones avanzadas").setItems(choices) { _, index -> when(index) {
            0 -> service?.connectFake(); 1 -> service?.sendTestFrame(false); 2 -> service?.startDemo(); 3 -> service?.startDemo(true); 4 -> service?.requestReroute(); 5 -> service?.stopNavigation(); 6 -> service?.setInterval(500); 7 -> service?.setInterval(1_000); 8 -> service?.setInterval(2_000); 9 -> coordinates(); 10 -> {
                val preview = TftPreviewView(this); service?.lastFrame?.value?.let { preview.showFrame(it) }
                AlertDialog.Builder(this).setTitle("Imagen TFT 480 × 240").setView(preview).setPositiveButton("Cerrar", null).show()
            }
        } }.setNegativeButton("Cerrar", null).show()
    }
    private fun coordinates() {
        val box = column(); val latitude = edit("Latitud"); val longitude = edit("Longitud"); box.addView(latitude); box.addView(longitude)
        AlertDialog.Builder(this).setTitle("Destino por coordenadas").setView(box).setPositiveButton("Elegir") { _, _ ->
            try { val point = GeoPoint(latitude.text.toString().replace(',', '.').toDouble(), longitude.text.toString().replace(',', '.').toDouble()); ValhallaRoutingEngine.validatePoint(point); positionWait?.cancel(); service?.selectSearchDestination(); destination = point; destinationName = "Destino por coordenadas"; hint.text = destinationName; showRouteSheet(); withPermissions(Action.CALCULATE) }
            catch (_: Exception) { hint.text = "Coordenadas fuera de rango" }
        }.setNegativeButton("Cancelar", null).show()
    }
    private fun showInfo() {
        val text = "NavFrame 0.11.0 · mapas OpenStreetMap. Móvil y Yamaha comparten estado; el TFT se renderiza directamente a 480 × 240, sin captura de pantalla.\n\nDEMO es ficticia. GPS/ETA/guiado deben probarse en condiciones reales. Rutas y búsqueda necesitan conexión; los mapas y lugares incluidos de Asturias se consultan offline. Calcular rutas sigue necesitando Internet.\n\nNominatim recibe el texto de búsqueda, no tu GPS. No envíes información personal/confidencial. Prototipo con máximo 1 petición/s agregado y caché.\n\nNaviLite adaptado de Pillion · PolyForm Noncommercial 1.0.0\nRequired Notice: Copyright 2026 the Pillion authors\n\nFuente online predeterminada incluida: OpenFreeMap · © OpenMapTiles · © OpenStreetMap contributors.\n\nAsturias offline: datos © OpenStreetMap contributors, extracto Geofabrik 28/09/2026, licencia ODbL. Límites 42,9–43,75 N / 7,2–4,45 O; zoom 6–14. Tipografía Noto Sans, SIL OFL 1.1."
        val box = column().apply { setPadding(dp(16), 0, dp(16), 0); addView(label(text + "\n\nFuente activa: ${service?.mapSource()?.attribution ?: ConfiguredMapDataSource.DEFAULT_ATTRIBUTION}\nSi una fuente personalizada no declara atribución, corrige el estilo en Ajustes.", 14f)); addView(button("Diagnóstico del mapa TFT") { showMapDiagnostic() }) }
        AlertDialog.Builder(this).setTitle("Información y licencias").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Cerrar", null).setNeutralButton("Licencias") { _, _ -> showLicenses() }.setNegativeButton("Política búsqueda") { _, _ -> openUrl("https://operations.osmfoundation.org/policies/nominatim/") }.show()
    }
    private fun showMapDiagnostic() {
        val report = service?.diagnosticReport() ?: "Servicio no disponible"
        AlertDialog.Builder(this).setTitle("Diagnóstico TFT").setMessage(report).setPositiveButton("Cerrar", null)
            .setNeutralButton("Compartir") { _, _ -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Compartir diagnóstico")) }.show()
    }
    private fun showLicenses() {
        val files = arrayOf("PILLION_LICENSE.md", "MAPLIBRE_LICENSE.md", "GSON_LICENSE.md", "OFFLINE_FONT_LICENSE.md", "OFFLINE_DATA_LICENSE.md")
        AlertDialog.Builder(this).setTitle("Licencias").setItems(arrayOf("Pillion", "MapLibre", "Gson", "Noto Sans · SIL OFL", "Datos Asturias · ODbL")) { _, which ->
            val text = assets.open(files[which]).bufferedReader().use { it.readText() }
            AlertDialog.Builder(this).setTitle(files[which]).setMessage(text).setPositiveButton("Cerrar", null).setNeutralButton("Créditos OSM") { _, _ -> openUrl("https://www.openstreetmap.org/copyright") }.show()
        }.show()
    }
    private fun showOfflineMaps() {
        offlineDialog?.dismiss()
        val box = column().apply { setPadding(dp(16), 0, dp(16), 0) }
        box.addView(label("Paquetes regionales locales: mapa y lugares OSM juntos. Importa un archivo .navframe para instalar o añadir una actualización; las versiones anteriores se conservan. Rutas y búsqueda de direcciones necesitan Internet. Límite total: 1 GiB.", 14f))
        box.addView(button("Descargar regiones por país") { showRegionalCatalog() })
        box.addView(button("Importar/actualizar región (.navframe)") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 74) })
        box.addView(button("Instalar Asturias incluido") { importOffline { offlineManager.installBundledAsturias() } })
        box.addView(button("Importar PMTiles NavFrame") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 73) })
        box.addView(button("Cancelar instalación/importación") { offlineTask?.cancel(); hint.text = "Importación cancelada" })
        box.addView(button("Usar mapa online predeterminado") { selectMapSource(TileSource("asset://map/tft-style.json", ConfiguredMapDataSource.DEFAULT_ATTRIBUTION)) })
        val regionalPacks = column(); box.addView(regionalPacks)
        val installProgress = label("", 13f); box.addView(installProgress)
        val packs = column(); box.addView(packs)
        val scroll = ScrollView(this).apply { addView(box) }
        val dialog = AlertDialog.Builder(this).setTitle("Mapas offline").setView(scroll).setPositiveButton("Cerrar", null).create()
        offlineDialog = dialog
        val collector = scope.launch {
            offlineManager.packs.collect { regions ->
                packs.removeAllViews()
                regions.forEach { pack ->
                    val size = "%.1f MiB".format(java.util.Locale.ROOT, pack.completedBytes / 1048576.0)
                    packs.addView(label("${pack.name} · $size · ${if (pack.state == OfflinePackState.READY) "listo" else "copiando…"}", 14f))
                    if (pack.state == OfflinePackState.READY) {
                        packs.addView(button("Usar ${pack.name}") { selectMapSource(pack.source) })
                        packs.addView(button("Borrar ${pack.name}") {
                            scope.launch {
                                try {
                                    if (service?.mapSource()?.uri == pack.source.uri) {
                                        check(selectMapSource(TileSource("asset://map/tft-style.json", ConfiguredMapDataSource.DEFAULT_ATTRIBUTION)))
                                        rebuildPhoneMap() // Release native readers before removing an active private archive.
                                    }
                                    offlineManager.delete(pack.id)
                                    hint.text = "Paquete offline borrado"
                                } catch (_: Exception) { hint.text = "No se pudo borrar el paquete" }
                            }
                        })
                    }
                }
            }
        }
        val regionalCollector = scope.launch {
            regionalManager.regions.collect { regions ->
                regionalPacks.removeAllViews()
                regions.forEach { region ->
                    regionalPacks.addView(label("${region.manifest.name} · ${region.manifest.version} · OSM ${region.manifest.osmTimestamp.take(10)} · %.1f MiB · mapa y lugares".format(java.util.Locale.ROOT, region.bytes / 1048576.0), 14f))
                    regionalPacks.addView(button("Usar ${region.manifest.name} ${region.manifest.version}") { selectMapSource(region.source) })
                    regionalPacks.addView(button("Borrar ${region.manifest.name} ${region.manifest.version}") {
                        scope.launch {
                            try {
                                if (service?.mapSource()?.uri == region.source.uri) {
                                    check(selectMapSource(TileSource("asset://map/tft-style.json", ConfiguredMapDataSource.DEFAULT_ATTRIBUTION)))
                                    rebuildPhoneMap()
                                }
                                regionalManager.delete(region.folderId)
                                displayedPlaces = emptyList(); phoneMap?.setPlaces(emptyList())
                                hint.text = "Región borrada"
                            } catch (_: Exception) { hint.text = "No se pudo borrar la región" }
                        }
                    })
                }
            }
        }
        val progressCollector = scope.launch {
            regionalManager.progress.collect { value -> installProgress.text = value?.let { "${it.stage} · ${it.completed / 1048576} MiB${it.total?.let { total -> " / ${total / 1048576} MiB" } ?: ""}" } ?: "" }
        }
        dialog.setOnDismissListener { regionalCollector.cancel(); progressCollector.cancel(); collector.cancel(); if (offlineDialog === dialog) offlineDialog = null }
        dialog.show()
        scope.launch { offlineManager.refresh(); regionalManager.refresh() }
    }
    private fun showRegionalCatalog() {
        val address = getSharedPreferences("regional_downloads", MODE_PRIVATE).getString("url", DEFAULT_REGIONAL_CATALOG).orEmpty()
        val box = column().apply { setPadding(dp(16), 0, dp(16), 0) }
        val query = edit("Buscar país o región")
        box.addView(query)
        val status = label("Cargando catálogo…", 14f); box.addView(status)
        val rows = column(); box.addView(rows)
        val progress = label("", 13f); box.addView(progress)
        box.addView(button("Cancelar descarga") { offlineTask?.cancel() })
        box.addView(label("Mantén NavFrame abierto durante la descarga. Se comprueba el archivo antes de instalarlo. Rutas y direcciones necesitan Internet.", 13f))
        val dialog = AlertDialog.Builder(this).setTitle("Regiones del mundo").setView(ScrollView(this).apply { addView(box) }).setPositiveButton("Cerrar", null).create()
        var entries = emptyList<RegionalCatalogEntry>()
        fun render() {
            rows.removeAllViews()
            val search = query.text.toString().trim()
            val filtered = entries.filter { "${it.manifest.name} ${it.country.orEmpty()} ${it.countryCode.orEmpty()} ${it.region.orEmpty()}".contains(search, ignoreCase = true) }
            status.text = if (entries.isEmpty()) "No hay regiones publicadas" else "${filtered.size} regiones · ${entries.size} en catálogo"
            filtered.take(100).forEach { item ->
                val m = item.manifest
                val size = "%.1f MiB".format(java.util.Locale.ROOT, item.archive.bytes / 1048576.0)
                val cameras = item.cameraCount?.let { " · $it cámaras OSM" }.orEmpty()
                rows.addView(label("${item.country.orEmpty()} · ${m.name} · $size$cameras\nCobertura: ${m.bounds.south}, ${m.bounds.west} a ${m.bounds.north}, ${m.bounds.east} · OSM ${m.osmTimestamp.take(10)}", 13f))
                rows.addView(button("Instalar ${m.name}") {
                    if (offlineTask?.isActive == true) { hint.text = "Espera o cancela la instalación actual"; return@button }
                    offlineTask = scope.launch {
                        try {
                            val installed = regionalDownloads.install(address, item)
                            selectMapSource(installed.source)
                            phoneMap?.refreshPlaces()
                            status.text = "${m.name} instalado"
                        } catch (cancelled: CancellationException) { status.text = "Descarga cancelada"; throw cancelled }
                        catch (_: Exception) { status.text = "No se pudo instalar: revisa conexión, espacio y catálogo" }
                    }
                })
            }
            if (filtered.size > 100) rows.addView(label("Afina la búsqueda para ver más regiones", 13f))
        }
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        val loading = scope.launch {
            try {
                require(address.isNotBlank())
                entries = regionalDownloads.fetchCatalog(address).regions.sortedWith(compareBy({ it.country.orEmpty() }, { it.manifest.name }))
                render()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status.text = "Catálogo no disponible. Configura su URL en Ajustes o espera a que el repositorio publique regiones." }
        }
        val observing = scope.launch { regionalManager.progress.collect { p -> progress.text = p?.let { "${it.stage} · ${it.completed / 1048576} / ${(it.total ?: 0) / 1048576} MiB" }.orEmpty() } }
        dialog.setOnDismissListener { loading.cancel(); observing.cancel() }
        dialog.show()
    }

    private fun selectMapSource(source: TileSource): Boolean {
        try {
            val current = service ?: return false
            current.saveMapSource(if (source.uri.startsWith("asset://")) "" else source.uri, source.attribution)
            phoneMap?.loadStyle(current.mapSource()); phoneMap?.refreshPlaces(); credits.text = compactAttribution(current.mapSource().attribution)
            hint.text = if (source.uri.startsWith("file://")) "Mapa offline activo · búsqueda y rutas necesitan Internet" else "Mapa online activo"
            return true
        } catch (_: Exception) { hint.text = "No se pudo activar la fuente de mapa"; return false }
    }
    private fun rebuildPhoneMap() {
        phoneMap?.let { old -> if (uiResumed) old.onPause(); if (uiStarted) old.onStop(); old.onDestroy() }
        mapHost.removeAllViews()
        phoneMap = createPhoneMap(null)?.also {
            mapHost.addView(it.view, FrameLayout.LayoutParams(-1, -1))
            it.setPlaces(displayedPlaces)
            if (uiStarted) it.onStart(); if (uiResumed) it.onResume()
            service?.let { current -> it.loadStyle(current.mapSource()); it.update(current.navigationState.value) }
        }
    }
    private fun importOffline(action: suspend () -> OfflineMapPack) {
        if (offlineTask?.isActive == true) { hint.text = "Ya hay una importación en curso"; return }
        offlineTask = scope.launch {
            try { val pack = action(); hint.text = "${pack.name} listo · pulsa Usar en Mapas offline" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { hint.text = error.message?.takeIf { error is IllegalArgumentException } ?: "No se pudo importar el mapa offline" }
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode !in listOf(73, 74) || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        showOfflineMaps()
        if (requestCode == 74) {
            if (offlineTask?.isActive == true) { hint.text = "Ya hay una importación en curso"; return }
            offlineTask = scope.launch {
                try {
                    val input = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("No se pudo abrir el archivo")
                    val region = regionalManager.importPackage(input)
                    hint.text = "${region.manifest.name} ${region.manifest.version} listo · pulsa Usar en Mapas offline"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { hint.text = error.message?.takeIf { error is IllegalArgumentException } ?: "No se pudo importar la región" }
            }
            return
        }
        importOffline {
            val input = contentResolver.openInputStream(uri) ?: throw IllegalArgumentException("No se pudo abrir el archivo")
            offlineManager.importArchive(input, "Paquete importado")
        }
    }
    private fun openAppSettings() { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
    private fun openUrl(url: String) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0, dp(3), 0, dp(3)) }
    private fun edit(value: String) = EditText(this).apply { hint = value; setSingleLine(true); setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY) }
    private fun rounded(color: Int, radius: Int) = android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title; textSize = if (title.length == 1) 22f else 13f; isAllCaps = false; minHeight = dp(48); setTextColor(Color.WHITE)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(0xFF31413F.toInt(), 16))
            addState(intArrayOf(android.R.attr.state_pressed), rounded(0xFF365A56.toInt(), 16))
            addState(intArrayOf(), rounded(0xEE24333F.toInt(), 16))
        }
        setPadding(dp(12), dp(8), dp(12), dp(8)); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
