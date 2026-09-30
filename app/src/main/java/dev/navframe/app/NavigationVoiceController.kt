package dev.navframe.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.navframe.core.NavigationState
import dev.navframe.core.VoiceGuidancePolicy
import dev.navframe.core.GuidanceLanguage
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owned by the navigation service, so activity closure and screen-off do not stop speech. */
internal class NavigationVoiceController(context: Context) {
    private val context = context.applicationContext
    private val preferences = context.getSharedPreferences("navigation_voice", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean("enabled", true))
    val enabled = mutableEnabled.asStateFlow()
    private val mutableStatus = MutableStateFlow("Voz: preparando motor / Preparing speech engine")
    val status = mutableStatus.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener({ change ->
            if (change < 0) stopSpeech()
        }, main).build()
    private var language = NavigationVoicePreferences.language(context)
    private var policy = VoiceGuidancePolicy(language)
    private var initialized = false
    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var currentId: String? = null
    private var serial = 0L
    private var latest: NavigationState? = null
    private val timeout = Runnable { stopSpeech() }

    init {
        engine = TextToSpeech(context.applicationContext) { result -> main.post {
            if (closed) return@post
            val tts = engine ?: return@post
            initialized = result == TextToSpeech.SUCCESS
            if (initialized) {
                configure(language)
                tts.setAudioAttributes(attributes)
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { finish(id) }
                    @Deprecated("Legacy callback") override fun onError(id: String?) { finish(id) }
                    override fun onError(id: String?, code: Int) { finish(id) }
                    override fun onStop(id: String?, interrupted: Boolean) { finish(id) }
                })
                latest?.let(::update)
            } else mutableStatus.value = "Motor de voz no disponible / Speech engine unavailable"
        } }
    }

    private fun configure(selected: GuidanceLanguage) {
        language = selected
        val tts = engine ?: return
        if (!initialized) return
        val locale = Locale.forLanguageTag(selected.tag)
        val available = tts.setLanguage(locale)
        ready = available >= TextToSpeech.LANG_AVAILABLE
        val spanish = selected == GuidanceLanguage.SPANISH
        if (!ready) {
            mutableStatus.value = if (spanish) "Voz ${selected.nativeName} no disponible: instala datos de voz en Android" else "${selected.nativeName} voice unavailable: install Android voice data"
            return
        }
        tts.voices?.filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired && it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            ?.sortedByDescending { it.locale.country == locale.country }?.firstOrNull()?.let { tts.setVoice(it) }
        if (tts.voice?.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) {
            ready = false
            mutableStatus.value = if (spanish) "Voz ${selected.nativeName}: instala datos de voz en Android" else "${selected.nativeName}: install Android voice data"
            return
        }
        val network = tts.voice?.isNetworkConnectionRequired != false
        mutableStatus.value = if (spanish) "Voz ${selected.nativeName} lista · ${if (network) "requiere Internet" else "sin Internet"}" else "${selected.nativeName} voice ready · ${if (network) "Internet required" else "offline"}"
    }

    fun languageChanged() {
        stopSpeech()
        val selected = latest?.route?.let { GuidanceLanguage.resolve(it.guidanceLanguage) } ?: NavigationVoicePreferences.language(context)
        if (selected != language) policy = VoiceGuidancePolicy(selected)
        configure(selected)
    }

    fun speakCameraAlert(distance: Int) { speakAlert(language.camera(distance)) }

    fun setEnabled(enabled: Boolean) {
        mutableEnabled.value = enabled
        preferences.edit().putBoolean("enabled", enabled).apply()
        if (!enabled) stopSpeech() else latest?.let(::update)
    }

    fun update(state: NavigationState) {
        latest = state
        val selected = state.route?.let { GuidanceLanguage.resolve(it.guidanceLanguage) } ?: NavigationVoicePreferences.language(context)
        if (selected != language) { stopSpeech(); policy = VoiceGuidancePolicy(selected); configure(selected) }
        if (state.route == null || state.navigationStatus == dev.navframe.core.NavigationStatus.ROUTE_PREVIEW) {
            stopSpeech(); policy.reset(); return
        }
        // While muted, consume checkpoints so unmuting does not replay old instructions.
        if (!ready && enabled.value) return
        policy.announcement(state)?.let { speak(it) }
    }

    fun speak(text: String) {
        if (closed || !ready || !enabled.value || text.isBlank()) return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        val id = "nav-${++serial}-${SystemClock.elapsedRealtime()}"
        currentId = id
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 20_000)
        if (engine?.speak(text.take(1000), TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) stopSpeech()
    }
    fun speakAlert(text: String) {
        // A maneuver already being spoken takes priority over advisory alerts.
        if (currentId == null) speak(text)
    }

    private fun finish(id: String?) { main.post { if (id != null && id == currentId) stopSpeech() } }
    private fun stopSpeech() {
        currentId = null
        main.removeCallbacks(timeout)
        engine?.stop()
        audio.abandonAudioFocusRequest(focus)
    }
    fun reset() {
        latest = null; policy.reset(); stopSpeech()
        if (!closed) {
            val selected = NavigationVoicePreferences.language(context)
            if (selected != language) { policy = VoiceGuidancePolicy(selected); configure(selected) }
        }
    }
    fun close() { closed = true; reset(); engine?.shutdown(); engine = null }
}
