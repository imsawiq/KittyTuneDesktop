package com.alananasss.kittytune.media

import com.alananasss.kittytune.audio.automix.AutomixStemAudioProcessor
import com.alananasss.kittytune.audio.ai.karaoke.CachedStemReader
import androidx.compose.animation.core.FastOutSlowInEasing
import com.alananasss.kittytune.audio.AudioEngine
import com.alananasss.kittytune.audio.TrimInteriorJump
import com.alananasss.kittytune.data.MusicManager
import com.alananasss.kittytune.data.StreamResolver
import com.alananasss.kittytune.data.SessionManager
import com.alananasss.kittytune.data.TokenManager
import com.alananasss.kittytune.domain.Track
import com.alananasss.kittytune.ui.player.AudioEffectsState
import com.alananasss.kittytune.utils.SignedUrl
import com.alananasss.kittytune.audio.automix.AutomixManager
import com.alananasss.kittytune.audio.automix.AutomixPlan
import com.alananasss.kittytune.data.local.PlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where the two gain ramps live, as fractions of the fade.
 *
 * Equal power needs **both** ramps over the *same* span: `out = cos(θ)` and `in = sin(θ)` with one
 * `θ`, and their squares only sum to a constant if both are the same function of progress. These two
 * used to span `[0, 0.6]` and `[0.4, 1]` — overlapping, but not identically — so the sum fell to
 * about 0.13 halfway through and the mix sagbed roughly 9 dB at exactly the moment it existed to be
 * seamless. The overlap was real; the arithmetic around it was not.
 *
 * A 40% span keeps the shape those numbers were reaching for: the outgoing track holds full for the
 * first third, the two genuinely blend across the middle, and the incoming is alone for the last
 * third. The point of a crossfade is that the sum does not move, and only a shared span achieves it.
 */
/** The ramp back from a mix's tempo and key: 60 steps of 40 ms, 2.4 s in all. */
private const val STRETCH_RAMP_STEPS = 60
private const val STRETCH_RAMP_STEP_MS = 40L

internal const val CROSSFADE_SPAN_START = 0.3f
internal const val CROSSFADE_SPAN_END = 0.7f

internal fun isIncomingAudioReady(state: AudioEngine.State, isSeeking: Boolean, hasBufferedAudio: Boolean): Boolean =
    state == AudioEngine.State.READY && !isSeeking && hasBufferedAudio

/**
 * The crossfade's two gain ramps, at file level so the mix glow can be lit by the same numbers the
 * ears hear.
 *
 * A second copy of this in the UI would be a second opinion about when a track is audible, and the
 * two would slowly stop agreeing — which is the one thing a glow like that must not do. The span
 * constants above are shared for the same reason: when the engine's overlap was corrected, the glow
 * had to be corrected with it, in the same commit, or it would have been lighting a fade that no
 * longer existed.
 */
internal fun equalPowerIn(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return sin(t * (Math.PI / 2.0).toFloat())
}

internal fun equalPowerOut(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return cos(t * (Math.PI / 2.0).toFloat())
}

/** Metadata attached to a media item (title/artist/artwork for the notification & UI). */
class MediaMetadata private constructor(
    val title: String?,
    val artist: String?,
    val artworkUri: String?,
    val artworkData: ByteArray?,
) {
    class Builder {
        private var title: String? = null
        private var artist: String? = null
        private var artworkUri: String? = null
        private var artworkData: ByteArray? = null
        fun setTitle(v: CharSequence?) = apply { title = v?.toString() }
        fun setArtist(v: CharSequence?) = apply { artist = v?.toString() }
        fun setArtworkUri(v: Any?) = apply { artworkUri = v?.toString() }
        fun setArtworkData(data: ByteArray?, pictureType: Int) = apply { artworkData = data }
        fun build() = MediaMetadata(title, artist, artworkUri, artworkData)
    }

    companion object {
        const val PICTURE_TYPE_FRONT_COVER = 3
    }
}

class RequestMetadata(val mediaUri: String?)

class MediaItem private constructor(
    val mediaId: String,
    val uri: String?,
    val mediaMetadata: MediaMetadata,
    val requestMetadata: RequestMetadata,
    val track: Track?,
    val mimeType: String? = null,
) {
    class Builder {
        private var mediaId: String = ""
        private var uri: String? = null
        private var metadata: MediaMetadata = MediaMetadata.Builder().build()
        private var track: Track? = null
        private var mimeType: String? = null
        fun setUri(v: Any?) = apply { uri = v?.toString() }
        fun setMediaId(v: String) = apply { mediaId = v }
        fun setMediaMetadata(v: MediaMetadata) = apply { metadata = v }
        fun setTrack(v: Track?) = apply { track = v }
        fun setMimeType(v: String?) = apply { mimeType = v }
        fun build() = MediaItem(mediaId, uri, metadata, RequestMetadata(uri), track, mimeType)
    }
}

class Player {

    interface Listener {
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onPlaybackStateChanged(playbackState: Int) {}
        fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {}
        fun onPositionDiscontinuity(oldPosition: PositionInfo, newPosition: PositionInfo, reason: Int) {}
        fun onPlayerError(error: Throwable) {}
    }

    class PositionInfo(val positionMs: Long)

    companion object {
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4

        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2

        const val MEDIA_ITEM_TRANSITION_REASON_REPEAT = 0
        const val MEDIA_ITEM_TRANSITION_REASON_AUTO = 1
        const val MEDIA_ITEM_TRANSITION_REASON_SEEK = 2
        const val MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED = 3

        const val DISCONTINUITY_REASON_SEEK = 1
        const val DISCONTINUITY_REASON_SEEK_ADJUSTMENT = 2

        const val TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED = 0

        /** How often the prebuffer TTL is checked. */
        private const val PREBUFFER_TTL_CHECK_MS = 10_000L
        /** Prebuffered engines older than this are released if not consumed by a crossfade. */
        private const val PREBUFFER_TTL_MS = 60_000L
    }

    private val listeners = mutableListOf<Listener>()
    private val items = mutableListOf<MediaItem>()
    private var currentIndex = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var resolveJob: Job? = null
    private val mediaLoadGeneration = java.util.concurrent.atomic.AtomicLong()
    private val automixStemGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var preparationDeferred = false
    @Volatile private var pendingStartPositionMs = 0L
    @Volatile private var knownMediaDurationMs = 0L
    private var loudnessCacheJob: Job? = null
    private data class PendingLoudnessScan(
        val track: Track,
        val url: String,
        val headers: Map<String, String>,
        val engine: AudioEngine,
        val generation: Long,
        val requiresPlayback: Boolean,
    )
    @Volatile private var pendingLoudnessScan: PendingLoudnessScan? = null

    var onCompletion: (() -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    var activeEngine = AudioEngine()
    private var fadingEngine: AudioEngine? = null

    @Volatile var isCrossfadingOut = false
    var lastEffectsState: AudioEffectsState? = null
        private set

    fun reapplyEffects() {
        lastEffectsState?.let { applyEffects(it) }
    }

    data class PrebufferedTransition(
        val engine: AudioEngine,
        val item: MediaItem,
        val trackId: Long,
        val plan: AutomixPlan?,
        val url: String,
        val headers: Map<String, String>,
        /** Wall-clock ms when the prebuffer was stored, for TTL eviction. */
        val createdAtMs: Long = System.currentTimeMillis(),
    ) {
        fun matches(trackId: Long?, startPositionMs: Long, requestedPlan: AutomixPlan?): Boolean =
            this.trackId == trackId &&
                (plan?.incomingStartMs ?: 0L) == (requestedPlan?.incomingStartMs ?: startPositionMs) &&
                (plan?.tempoRatio ?: 1f) == (requestedPlan?.tempoRatio ?: 1f) &&
                (plan?.pitchRatio ?: 1f) == (requestedPlan?.pitchRatio ?: 1f)
    }

    @Volatile
    private var prebufferedTransition: PrebufferedTransition? = null
    private var prebufferJob: Job? = null
    private var prebufferGeneration = 0L
    private var prebufferInFlight = false
    private var crossfadeJob: Job? = null
    private val volumeLock = Any()
    private var incomingFadeGain = 1f
    private var outgoingFadeGain = 0f
    private var trimSpliceDeck: EngineTrimDeck? = null
    private var trimSpliceGain = 0f
    private val trimSpliceController = TrimSpliceController(scope, object : TrimSpliceHost {
        override val currentIdentity: Any get() = activeEngine
        override val canPrepare: Boolean get() = playWhenReady && !isCrossfadingOut && fadingEngine == null &&
            prebufferedTransition == null && !prebufferInFlight && trimSpliceDeck == null && trimGain == 1f &&
            !AutomixManager.isAutomixing.value && activeEngine.canPrepareTrimSplice

        override fun createIncoming(jump: TrimInteriorJump): TrimSpliceDeck? = synchronized(this@Player) {
            if (!canPrepare) return@synchronized null
            val engine = activeEngine.prepareTrimSpliceHead(jump.landingMs) ?: return@synchronized null
            EngineTrimDeck(engine).also { trimSpliceDeck = it }
        }

        override fun canContinue(outgoing: Any, incoming: TrimSpliceDeck): Boolean = synchronized(this@Player) {
            activeEngine === outgoing && trimSpliceDeck === incoming && playWhenReady &&
                !isCrossfadingOut && !activeEngine.isSeeking && !activeEngine.pendingDeviceSwap && trimGain == 1f &&
                (incoming as? EngineTrimDeck)?.matchesAudioDevice() == true
        }

        override fun setGains(outgoing: Any, incoming: TrimSpliceDeck, outgoingGain: Float, incomingGain: Float): Boolean =
            synchronized(this@Player) {
                if (!canContinue(outgoing, incoming)) return@synchronized false
                synchronized(volumeLock) {
                    incomingFadeGain = outgoingGain
                    trimSpliceGain = incomingGain
                    updateEngineVolume()
                }
                true
            }

        override fun commit(outgoing: Any, incoming: TrimSpliceDeck): Boolean = synchronized(this@Player) {
            if (!canContinue(outgoing, incoming)) return@synchronized false
            val old = activeEngine
            val oldPosition = old.positionMs
            val deck = incoming as EngineTrimDeck
            unbindEngine(old)
            trimSpliceDeck = null
            trimSpliceGain = 0f
            activeEngine = deck.engine
            activeEngine.takeOverSameTrackPlayback()
            bindEngine(activeEngine)
            setFadeGains(1f, 0f)
            old.release(preserveTrackState = true)
            listeners.toList().forEach {
                it.onPositionDiscontinuity(PositionInfo(oldPosition), PositionInfo(activeEngine.positionMs), DISCONTINUITY_REASON_SEEK)
            }
            true
        }

        override fun restore(outgoing: Any, incoming: TrimSpliceDeck) = synchronized(this@Player) {
            if (trimSpliceDeck !== incoming) return@synchronized
            trimSpliceDeck = null
            trimSpliceGain = 0f
            if (activeEngine === outgoing) setFadeGains(1f, 0f)
            // Dispose before releasing the player's secondary slot to a concurrent preparation.
            incoming.release()
        }
    })

    private class EngineTrimDeck(val engine: AudioEngine) : TrimSpliceDeck {
        private val deviceName = PlayerPreferences().getAudioDevice()
        private var released = false
        fun matchesAudioDevice(): Boolean = deviceName == PlayerPreferences().getAudioDevice()
        override val identity: Any get() = engine
        override val isPrimed: Boolean get() = engine.hasPrimedAudio
        override val isBuffered: Boolean get() = engine.hasBufferedAudio && !engine.isSeeking
        override val hasFailed: Boolean get() = engine.state == AudioEngine.State.IDLE || engine.state == AudioEngine.State.ENDED
        override val positionMs: Long get() = engine.positionMs
        override fun play() = engine.play()
        @Synchronized
        override fun release() {
            if (released) return
            released = true
            engine.release(preserveTrackState = true)
        }
    }

    @Synchronized
    fun prepareTrimSplice(jump: TrimInteriorJump): Boolean = trimSpliceController.prepare(jump)
    fun isTrimSpliceReady(jump: TrimInteriorJump): Boolean = trimSpliceController.isReady(jump)
    internal suspend fun tryTrimSplice(jump: TrimInteriorJump): TrimSpliceResult = trimSpliceController.splice(jump)
    fun cancelTrimSplice() = trimSpliceController.cancel()

    /**
     * Prebuffered engines that aren't consumed by a crossfade within this TTL are released to avoid
     * holding ~1.5-2 MB of native DSP + FFmpeg state for a track the user never skipped to. The
     * prebuffer is re-created on demand if the user does skip later, so the cost of early release
     * is one re-resolve, not a lost feature (audit Phase 2 RAM gain).
     */
    private val prebufferTtlJob: Job = scope.launch {
        while (isActive) {
            delay(PREBUFFER_TTL_CHECK_MS)
            val pb = prebufferedTransition ?: continue
            if (System.currentTimeMillis() - pb.createdAtMs > PREBUFFER_TTL_MS) {
                releasePrebuffered()
            }
        }
    }

    @Synchronized
    fun isPrebuffered(trackId: Long): Boolean {
        val pb = prebufferedTransition ?: return false
        // TTL check: a prebuffer older than PREBUFFER_TTL_MS is stale (the user didn't skip to it)
        // — release it and report not-prebuffered so the caller re-resolves if needed.
        if (System.currentTimeMillis() - pb.createdAtMs > PREBUFFER_TTL_MS) {
            releasePrebuffered()
            return false
        }
        return pb.trackId == trackId && pb.engine.state != AudioEngine.State.IDLE
    }

    @Synchronized
    fun releasePrebuffered() {
        prebufferGeneration++
        prebufferInFlight = false
        prebufferJob?.cancel()
        prebufferJob = null
        val pb = prebufferedTransition ?: return
        prebufferedTransition = null
        try {
            pb.engine.stop(preserveTrackState = true)
            pb.engine.release(preserveTrackState = true)
        } catch (_: Exception) {}
    }

    @Synchronized
    internal fun takeCompatiblePrebuffer(trackId: Long?, startPositionMs: Long, plan: AutomixPlan?): PrebufferedTransition? {
        val prepared = prebufferedTransition
        if (!isPrebuffered(trackId ?: Long.MIN_VALUE) || prepared?.matches(trackId, startPositionMs, plan) != true) {
            releasePrebuffered()
            return null
        }
        prebufferGeneration++
        prebufferInFlight = false
        prebufferJob?.cancel()
        prebufferJob = null
        prebufferedTransition = null
        return prepared
    }

    @Synchronized
    fun prebufferTransition(item: MediaItem, nextTrack: Track, automixPlan: AutomixPlan? = null) {
        if (isCrossfadingOut || prebufferInFlight || trimSpliceController.isReserved || trimSpliceDeck != null) return
        if (isPrebuffered(nextTrack.id) && prebufferedTransition?.matches(nextTrack.id, 0L, automixPlan) == true) return
        releasePrebuffered()
        prebufferInFlight = true
        val generation = prebufferGeneration
        prebufferJob = scope.launch {
            var engine: AudioEngine? = null
            var retained = false
            try {
                val rawUrl = item.uri?.takeIf {
                    if (it.startsWith("metrofuse-deezer://")) true
                    else if (SignedUrl.isNetworkUrl(it)) !SignedUrl.isExpired(it)
                    else if (it.startsWith("file:")) runCatching { java.io.File(java.net.URI(it)).exists() }.getOrDefault(false)
                    else java.io.File(it).exists()
                } ?: withContext(Dispatchers.IO) { StreamResolver.resolveStream(nextTrack) }

                if (rawUrl == null) return@launch

                val url = if (rawUrl.startsWith("metrofuse-deezer://")) {
                    com.alananasss.kittytune.audio.providers.deezer.DeezerAudioProxy.getPlayableUrlFromDeezerUri(rawUrl)
                } else {
                    rawUrl
                }
                val headers = buildHeaders(nextTrack)

                val preparedEngine = AudioEngine()
                engine = preparedEngine
                lastEffectsState?.let { preparedEngine.applyEffects(it) }

                if (automixPlan != null && (automixPlan.tempoRatio != 1f || automixPlan.pitchRatio != 1f)) {
                    preparedEngine.setStretcherRatio(automixPlan.tempoRatio, automixPlan.pitchRatio)
                }

                val startPos = automixPlan?.incomingStartMs ?: 0L
                preparedEngine.setVolume(0f)
                preparedEngine.setMediaItem(url, headers, startPos, nextTrack.id, announceTrackChange = false)
                preparedEngine.prepare()

                synchronized(this@Player) {
                    if (!isActive || generation != prebufferGeneration || isCrossfadingOut) return@launch
                    prebufferedTransition = PrebufferedTransition(preparedEngine, item, nextTrack.id, automixPlan, url, headers)
                    retained = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                com.alananasss.kittytune.utils.Logger.w("Player", "Prebuffer failed: ${error.message}")
            } finally {
                synchronized(this@Player) {
                    if (generation == prebufferGeneration) prebufferInFlight = false
                }
                if (!retained) engine?.release(preserveTrackState = true)
            }
        }
    }

    @Volatile var playWhenReady: Boolean = false
        set(value) {
            synchronized(this) {
                if (!value) cancelTrimSplice()
                field = value
                // Both sides pause together; a paused transition retains its current gains.
                if (value) {
                    preparationDeferred = false
                    if (activeEngine.currentUrl != null) activeEngine.play()
                    else if (resolveJob?.isActive != true) loadCurrent()
                    fadingEngine?.play()
                    startPendingLoudnessScan()
                } else {
                    activeEngine.pause()
                    fadingEngine?.pause()
                }
            }
        }

    var repeatMode: Int = REPEAT_MODE_OFF

    val isSeeking: Boolean get() = activeEngine.isSeeking
    val isPlaying: Boolean get() = activeEngine.isPlaying
    val currentPosition: Long get() = if (activeEngine.currentUrl == null) pendingStartPositionMs else activeEngine.positionMs
    val duration: Long get() = activeEngine.durationMs.takeIf { it > 0L } ?: knownMediaDurationMs
    val isLoading: Boolean get() = activeEngine.state == AudioEngine.State.BUFFERING

    /** The slider's position, 0..1. Engines are given [volumeAmplitude], never this directly. */
    var volume: Float = 1f
        set(value) {
            synchronized(volumeLock) {
                field = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
                updateEngineVolume()
            }
        }

    /** Temporary attenuation for a trim seek; independent of the user's master volume. */
    var trimGain: Float = 1f
        set(value) {
            synchronized(volumeLock) {
                field = if (value.isFinite()) value.coerceIn(0f, 1f) else 1f
                updateEngineVolume()
            }
        }

    /** Sleep attenuation spans track changes without replacing the user's slider position. */
    internal var sleepTimerGain: Float = 1f
        set(value) {
            synchronized(volumeLock) {
                field = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
                updateEngineVolume()
            }
        }

    // The shared lock keeps slider changes and transition ticks from overwriting each other.
    private fun updateEngineVolume() {
        synchronized(volumeLock) {
            val amplitude = volumeAmplitude * trimGain * sleepTimerGain
            activeEngine.setVolume(amplitude * incomingFadeGain)
            fadingEngine?.setVolume(amplitude * outgoingFadeGain)
            trimSpliceDeck?.engine?.setVolume(amplitude * trimSpliceGain)
        }
    }

    internal fun setFadeGains(incoming: Float, outgoing: Float) {
        synchronized(volumeLock) {
            incomingFadeGain = incoming
            outgoingFadeGain = outgoing
            updateEngineVolume()
        }
    }

    private val volumeAmplitude: Float
        get() = com.alananasss.kittytune.audio.VolumeCurve.sliderToAmplitude(volume)

    /**
     * The current track's own trim, in dB. Re-applied whenever playback moves to an engine, since
     * crossfade swaps between two of them and only one carries the trim at a time (issue #33).
     */
    var trackGainDb: Float = 0f
        set(value) {
            field = value
            activeEngine.setTrackGainDb(value)
            trimSpliceDeck?.engine?.setTrackGainDb(value)
        }

    val mediaItemCount: Int get() = items.size
    val currentMediaItem: MediaItem? get() = items.getOrNull(currentIndex)
    val currentMediaItemIndex: Int get() = currentIndex

    init {
        bindEngine(activeEngine)
    }

    private fun bindEngine(eng: AudioEngine) {
        eng.onPlayingChanged = { playing -> listeners.forEach { it.onIsPlayingChanged(playing) } }
        eng.onStateChanged = { st ->
            val mapped = when (st) {
                AudioEngine.State.BUFFERING -> STATE_BUFFERING
                AudioEngine.State.READY -> STATE_READY
                AudioEngine.State.ENDED -> STATE_ENDED
                AudioEngine.State.IDLE -> STATE_IDLE
            }
            listeners.forEach { it.onPlaybackStateChanged(mapped) }
        }
        eng.onError = { err ->
            onError?.invoke(err)
            listeners.forEach { it.onPlayerError(err) }
        }
        eng.onCompletion = {
            val trackId = currentMediaItem?.track?.id ?: com.alananasss.kittytune.data.MusicManager.currentTrack?.id
            trackId?.let { id ->
                val lufs = eng.getIntegratedLoudness()
                val tp = eng.getMaxTruePeakDb()
                if (lufs > -60f && lufs < 0f) {
                    com.alananasss.kittytune.data.TrackLoudnessRepository.saveLoudness(id, lufs, tp)
                }
            }
            onCompletion?.invoke()
        }
        eng.onReResolveUrl = { failedUrl ->
            val track = currentMediaItem?.track ?: com.alananasss.kittytune.data.MusicManager.currentTrack
            track?.let { t ->
                // Only throw the cache away when it is still holding the URL that just failed.
                // Otherwise the queue's prefetch has already refreshed it and can answer without
                // a round-trip — which is the difference between a seek resuming now and a seek
                // waiting on two SoundCloud requests.
                if (StreamResolver.cachedStreamUrl(t.id) == failedUrl) {
                    StreamResolver.evictStream(t.id)
                }
                withContext(Dispatchers.IO) {
                    StreamResolver.resolveStream(t)
                }
            }
        }
    }

    private fun unbindEngine(eng: AudioEngine) {
        eng.onPlayingChanged = null
        eng.onStateChanged = null
        eng.onError = null
        eng.onCompletion = null
        eng.onReResolveUrl = null
    }

    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    @Synchronized
    fun setMediaItemUrl(
        url: String,
        headers: Map<String, String>,
        startPositionMs: Long = 0L,
        deferPreparation: Boolean = false,
        knownDurationMs: Long = 0L,
    ) {
        cancelTrimSplice()
        resetMediaPreparation(startPositionMs, knownDurationMs, deferPreparation)
        activeEngine.setMediaItem(url, headers, startPositionMs, currentMediaItem?.track?.id,
            deferPreparation = deferPreparation, knownDurationMs = knownDurationMs)
    }

    @Synchronized
    fun setMediaItem(item: MediaItem, startPositionMs: Long = 0L, deferPreparation: Boolean = false) {
        cancelTrimSplice()
        resetMediaPreparation(startPositionMs, item.track?.effectiveFullDurationMs ?: item.track?.durationMs ?: 0L, deferPreparation)
        releasePrebuffered()
        crossfadeJob?.cancel()
        isCrossfadingOut = false
        fadingEngine?.stop(preserveTrackState = true)
        fadingEngine?.release(preserveTrackState = true)
        fadingEngine = null
        setFadeGains(1f, 0f)
        items.clear()
        items.add(item)
        currentIndex = 0

        val uriStr = item.uri
        val isDirectUrl = uriStr != null && (
            uriStr.startsWith("http://") ||
            uriStr.startsWith("https://") ||
            uriStr.startsWith("file:") ||
            uriStr.startsWith("metrofuse-deezer://") ||
            java.io.File(uriStr).exists()
        ) && !SignedUrl.isExpired(uriStr)

        if (isDirectUrl && uriStr != null) {
            val url = if (uriStr.startsWith("metrofuse-deezer://")) {
                com.alananasss.kittytune.audio.providers.deezer.DeezerAudioProxy.getPlayableUrlFromDeezerUri(uriStr)
            } else {
                uriStr
            }
            val headers = buildHeaders(item.track)
            val track = item.track
            if (track != null) {
                val cached = kotlinx.coroutines.runBlocking { com.alananasss.kittytune.data.TrackLoudnessRepository.getLoudness(track.id) }
                if (cached != null) {
                    activeEngine.setTrackLoudness(cached.integratedLufs, cached.truePeakDb)
                } else {
                    activeEngine.clearTrackLoudness()
                }
            } else {
                activeEngine.clearTrackLoudness()
            }
            updateEngineVolume()
            activeEngine.setTrackGainDb(trackGainDb)
            activeEngine.setMediaItem(url, headers, startPositionMs, track?.id,
                deferPreparation = deferPreparation, knownDurationMs = knownMediaDurationMs)
        } else {
            activeEngine.stop()
        }

        loadCurrent()
        listeners.forEach { it.onMediaItemTransition(currentMediaItem, MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
    }

    @Synchronized
    fun crossfadeToMediaItem(
        item: MediaItem,
        startPositionMs: Long,
        crossfadeDurationMs: Long,
        automixPlan: AutomixPlan? = null
    ) {
        // MusicManager already points at incoming here; capture the actual outgoing source first.
        val outgoingTrackId = activeEngine.automixSourceTrackId ?: currentMediaItem?.track?.id
        val outgoingDurationMs = activeEngine.durationMs
        cancelTrimSplice()
        resetMediaPreparation(startPositionMs, item.track?.effectiveFullDurationMs ?: item.track?.durationMs ?: 0L, false)
        val stemGeneration = automixStemGeneration.get()
        items.clear()
        items.add(item)
        currentIndex = 0

        crossfadeJob?.cancel()
        resolveJob?.cancel()

        fadingEngine?.stop(preserveTrackState = true)
        fadingEngine?.release(preserveTrackState = true)
        fadingEngine = null

        val oldEngine = activeEngine
        unbindEngine(oldEngine)

        val targetTrackId = item.track?.id
        val pre = takeCompatiblePrebuffer(targetTrackId, startPositionMs, automixPlan)
        val isAdopted = pre != null

        val newEngine = pre?.engine ?: AudioEngine()
        if (isAdopted) newEngine.activateTrackPlayback()
        val effectivePlan = automixPlan

        bindEngine(newEngine)
        activeEngine = newEngine
        fadingEngine = oldEngine
        isCrossfadingOut = true
        setFadeGains(0f, 1f)

        if (effectivePlan != null) {
            AutomixManager.setIsAutomixing(true)
        }

        if (!isAdopted) {
            lastEffectsState?.let { newEngine.applyEffects(it) }
            if (effectivePlan != null && (effectivePlan.tempoRatio != 1f || effectivePlan.pitchRatio != 1f)) {
                newEngine.setStretcherRatio(effectivePlan.tempoRatio, effectivePlan.pitchRatio)
            }
        }

        listeners.forEach { it.onMediaItemTransition(currentMediaItem, MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }

        val actualStartPos = effectivePlan?.incomingStartMs ?: startPositionMs

        startCrossfade(
            newEngine = newEngine,
            oldEngine = oldEngine,
            item = item,
            startPositionMs = actualStartPos,
            crossfadeDurationMs = crossfadeDurationMs,
            effectivePlan = effectivePlan,
            isAdopted = isAdopted,
            outgoingTrackId = outgoingTrackId,
            outgoingDurationMs = outgoingDurationMs,
            stemGeneration = stemGeneration,
        )
    }

    private fun startCrossfade(
        newEngine: AudioEngine,
        oldEngine: AudioEngine,
        item: MediaItem,
        startPositionMs: Long,
        crossfadeDurationMs: Long,
        effectivePlan: AutomixPlan?,
        isAdopted: Boolean,
        outgoingTrackId: Long?,
        outgoingDurationMs: Long,
        stemGeneration: Long,
    ) {
        crossfadeJob = scope.launch {
            var preserveOutgoingForRetry = true
            try {
                if (!isAdopted) {
                    val rawUrl = item.uri?.takeIf {
                        if (it.startsWith("metrofuse-deezer://")) true
                        else if (SignedUrl.isNetworkUrl(it)) !SignedUrl.isExpired(it)
                        else if (it.startsWith("file:")) runCatching { java.io.File(java.net.URI(it)).exists() }.getOrDefault(false)
                        else java.io.File(it).exists()
                    } ?: item.track?.let { withContext(Dispatchers.IO) { StreamResolver.resolveStream(it) } }

                    if (rawUrl == null) {
                        reportCrossfadeFailure(newEngine, oldEngine,
                            IllegalStateException("Incoming stream unavailable"))
                        return@launch
                    }
                    val url = if (rawUrl.startsWith("metrofuse-deezer://")) {
                        com.alananasss.kittytune.audio.providers.deezer.DeezerAudioProxy.getPlayableUrlFromDeezerUri(rawUrl)
                    } else {
                        rawUrl
                    }
                    val headers = buildHeaders(item.track)

                    val track = item.track
                    if (track != null) {
                        val cached = com.alananasss.kittytune.data.TrackLoudnessRepository.getLoudness(track.id)
                        if (cached != null) {
                            newEngine.setTrackLoudness(cached.integratedLufs, cached.truePeakDb)
                        } else {
                            newEngine.clearTrackLoudness()
                            com.alananasss.kittytune.data.TrackLoudnessRepository.scanTrackAsync(track, url, headers) { scanned ->
                                if (currentMediaItem?.track?.id == track.id) {
                                    newEngine.setTrackLoudness(scanned.integratedLufs, scanned.truePeakDb)
                                }
                            }
                        }
                    } else {
                        newEngine.clearTrackLoudness()
                    }

                    newEngine.setMediaItem(url, headers, startPositionMs, track?.id)
                    newEngine.prepare()
                }

                setFadeGains(0f, 1f)
                newEngine.setTrackGainDb(trackGainDb)

                // Prepared engines are paused and have not decoded PCM yet. Wait for resume before
                // starting the bounded readiness window; opening a device is not audible readiness.
                while (!playWhenReady && isActive) delay(50)
                if (!isActive || activeEngine !== newEngine || fadingEngine !== oldEngine) return@launch
                newEngine.play()
                var readinessWaitMs = 0L
                while (!isIncomingAudioReady(newEngine.state, newEngine.isSeeking, newEngine.hasBufferedAudio)) {
                    if (!isActive || activeEngine !== newEngine || fadingEngine !== oldEngine) return@launch
                    if (playWhenReady) readinessWaitMs += 50L
                    if (readinessWaitMs >= 5_000L) {
                        preserveOutgoingForRetry = true
                        reportCrossfadeFailure(newEngine, oldEngine,
                            java.util.concurrent.TimeoutException("Incoming audio readiness timeout"))
                        return@launch
                    }
                    delay(50)
                }
                preserveOutgoingForRetry = false

                val transitionDuration = effectivePlan?.overlapMs ?: crossfadeDurationMs
                var remainingMs = oldEngine.durationMs - oldEngine.positionMs
                if (remainingMs < 0) remainingMs = 0

                val actualCrossfadeMs = if (effectivePlan != null) {
                    if (oldEngine.isPlaying) {
                        if (remainingMs > 0 && remainingMs < transitionDuration) remainingMs.coerceAtLeast(1000L)
                        else transitionDuration
                    } else 0L
                } else if (oldEngine.isPlaying && remainingMs > 0 && remainingMs < transitionDuration) {
                    remainingMs
                } else if (!oldEngine.isPlaying || remainingMs == 0L) {
                    0L
                } else {
                    transitionDuration
                }

                if (effectivePlan != null && actualCrossfadeMs > 0L && outgoingTrackId != null && oldEngine.canUseAutomixStem) {
                    val rangeStart = oldEngine.positionMs
                    val rangeEnd = (rangeStart + actualCrossfadeMs).coerceAtMost(outgoingDurationMs)
                    var preparedStem: CachedStemReader? = null
                    try {
                        withContext(Dispatchers.IO) {
                            preparedStem = AutomixStemAudioProcessor.openCachedStem(
                                com.alananasss.kittytune.audio.ai.karaoke.KaraokeManager.getStemFile(outgoingTrackId),
                                outgoingDurationMs, rangeStart, rangeEnd,
                            )
                        }
                        synchronized(this@Player) {
                            val reader = preparedStem
                            if (reader != null && isActive && activeEngine === newEngine && fadingEngine === oldEngine &&
                                automixStemGeneration.get() == stemGeneration) {
                                oldEngine.installAutomixStem(reader, outgoingTrackId, stemGeneration)
                                preparedStem = null // engine owns (or rejected and closed) it
                            }
                        }
                    } finally {
                        // withContext has prompt cancellation: even a completed IO open can be discarded.
                        preparedStem?.close()
                    }
                }
                if (!isActive || activeEngine !== newEngine || fadingEngine !== oldEngine) return@launch
                val bassDuckingEnabled = PlayerPreferences().getAutomixBassDuckingEnabled()

                if (actualCrossfadeMs <= 0L) {
                    setFadeGains(1f, 0f)
                    oldEngine.stop(preserveTrackState = true)
                    oldEngine.release(preserveTrackState = true)
                    AutomixManager.setMixProgress(0f)
                } else {
                    val steps = (actualCrossfadeMs / 15L).toInt().coerceIn(50, 800)
                    val delayMs = (actualCrossfadeMs / steps).coerceAtLeast(5L)

                    for (i in 0..steps) {
                        if (fadingEngine != oldEngine) break
                        if (!isActive) break

                        var bufferWait = 0
                        while (!newEngine.isPlaying && isActive && newEngine.state == AudioEngine.State.BUFFERING && bufferWait < 50) {
                            delay(100)
                            bufferWait++
                        }
                        // Paused mid-fade: hold the fade where it is, so it resumes rather than having finished
                        // in silence.
                        var pauseWait = 0
                        while (!playWhenReady && isActive && pauseWait < 600) {
                            delay(50)
                            pauseWait++
                        }

                        if (oldEngine.state == AudioEngine.State.ENDED || oldEngine.state == AudioEngine.State.IDLE) {
                            setFadeGains(1f, 0f)
                            break
                        }

                        val progress = i.toFloat() / steps
                        val fadeOut = equalPowerOut(CROSSFADE_SPAN_START, CROSSFADE_SPAN_END, progress)
                        val fadeIn = equalPowerIn(CROSSFADE_SPAN_START, CROSSFADE_SPAN_END, progress)

                        // The artwork is lit by this, so the glow and the mix are the same event
                        // rather than two things that usually happen together.
                        AutomixManager.setMixProgress(progress)

                        if (!isActive || fadingEngine !== oldEngine) break
                        setFadeGains(fadeIn, fadeOut)
                        if (automixStemGeneration.get() == stemGeneration) {
                            oldEngine.setAutomixStemSuppression(stemGeneration,
                                FastOutSlowInEasing.transform((progress / CROSSFADE_SPAN_START).coerceIn(0f, 1f)))
                        }

                        if (effectivePlan != null && bassDuckingEnabled) {
                            oldEngine.setDuckMix(equalPowerIn(0.45f, 1f, progress))
                            newEngine.setDuckMix(1f - equalPowerIn(0f, 0.55f, progress))
                        }

                        delay(delayMs)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                preserveOutgoingForRetry = true
                reportCrossfadeFailure(newEngine, oldEngine, error)
            } finally {
                oldEngine.clearAutomixStem(stemGeneration)
                if (!preserveOutgoingForRetry && fadingEngine === oldEngine && activeEngine === newEngine) {
                    setFadeGains(1f, 0f)
                    oldEngine.stop(preserveTrackState = true)
                    oldEngine.release(preserveTrackState = true)
                    fadingEngine = null

                    oldEngine.resetDuckMix()
                    newEngine.resetDuckMix()

                    if (effectivePlan != null) {
                        AutomixManager.setIsAutomixing(false)
                        AutomixManager.clearPlan()

                        if (effectivePlan.tempoRatio != 1f || effectivePlan.pitchRatio != 1f) {
                            // Back to the track's own tempo and key, in steps too small to hear. Ten jumps of a
                            // fifth of a second each made the start of a mixed-in track warble, as if it were
                            // playing at a lower quality for its first seconds (issue #66).
                            scope.launch restoreTempo@{
                                val startTempo = effectivePlan.tempoRatio
                                val startPitch = effectivePlan.pitchRatio
                                for (step in 1..STRETCH_RAMP_STEPS) {
                                    delay(STRETCH_RAMP_STEP_MS)
                                    if (!isActive || activeEngine !== newEngine || isCrossfadingOut) return@restoreTempo
                                    val frac = FastOutSlowInEasing.transform(step.toFloat() / STRETCH_RAMP_STEPS)
                                    val curTempo = startTempo + frac * (1f - startTempo)
                                    val curPitch = startPitch + frac * (1f - startPitch)
                                    newEngine.setStretcherRatio(curTempo, curPitch)
                                }
                                newEngine.setStretcherRatio(1f, 1f)
                            }
                        }
                    }
                    isCrossfadingOut = false
                    // Every exit from the loop above lands here, including a fade cut short or a track
                    // that ended underneath it. Leaving the artwork lit for a mix that is over would be
                    // worse than never lighting it.
                    AutomixManager.setMixProgress(0f)
                }
            }
        }
    }

    private suspend fun reportCrossfadeFailure(newEngine: AudioEngine, oldEngine: AudioEngine, error: Throwable) {
        withContext(Dispatchers.Main) {
            handleCrossfadeFailure(newEngine, oldEngine, error)
        }
    }

    /** Only the transition that still owns both engines can report failure or change their gains. */
    @Synchronized
    internal fun handleCrossfadeFailure(newEngine: AudioEngine, oldEngine: AudioEngine, error: Throwable) {
        if (activeEngine !== newEngine || fadingEngine !== oldEngine) return
        setFadeGains(0f, 1f)
        newEngine.stop()
        onError?.invoke(error)
        listeners.toList().forEach { it.onPlayerError(error) }
    }

    fun addMediaItem(item: MediaItem) { items.add(item) }
    fun addMediaItems(newItems: List<MediaItem>) { items.addAll(newItems) }
    fun removeMediaItem(index: Int) { if (index in items.indices) items.removeAt(index) }

    fun prepare() { if (!preparationDeferred) activeEngine.prepare() }

    fun play() { playWhenReady = true }
    fun pause() { playWhenReady = false }
    fun stop() {
        cancelTrimSplice()
        resetMediaPreparation(0L, 0L, false)
        crossfadeJob?.cancel()
        resolveJob?.cancel()
        releasePrebuffered()
        isCrossfadingOut = false
        fadingEngine?.stop(preserveTrackState = true)
        fadingEngine?.release(preserveTrackState = true)
        fadingEngine = null
        activeEngine.stop()
    }
    fun release() {
        cancelTrimSplice()
        resetMediaPreparation(0L, 0L, false)
        crossfadeJob?.cancel()
        resolveJob?.cancel()
        releasePrebuffered()
        isCrossfadingOut = false
        fadingEngine?.release(preserveTrackState = true)
        fadingEngine = null
        activeEngine.release()
        scope.coroutineContext[Job]?.cancel()
    }

    @Synchronized
    fun seekTo(positionMs: Long) {
        cancelAutomixStem()
        cancelTrimSplice()
        pendingStartPositionMs = positionMs.coerceAtLeast(0L).let { if (duration > 0L) it.coerceAtMost(duration) else it }
        if (activeEngine.currentUrl != null || !preparationDeferred) activeEngine.seekTo(pendingStartPositionMs)
        val pi = PositionInfo(positionMs)
        listeners.forEach { it.onPositionDiscontinuity(pi, pi, DISCONTINUITY_REASON_SEEK) }
    }

    @Synchronized
    fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        cancelTrimSplice()
        if (mediaItemIndex in items.indices) {
            currentIndex = mediaItemIndex
            resetMediaPreparation(positionMs, currentMediaItem?.track?.effectiveFullDurationMs ?: currentMediaItem?.track?.durationMs ?: 0L, preparationDeferred)
            loadCurrent()
            listeners.forEach { it.onMediaItemTransition(currentMediaItem, MEDIA_ITEM_TRANSITION_REASON_AUTO) }
        }
    }

    private fun resetMediaPreparation(positionMs: Long, durationMs: Long, deferPreparation: Boolean) {
        cancelAutomixStem()
        mediaLoadGeneration.incrementAndGet()
        resolveJob?.cancel()
        loudnessCacheJob?.cancel()
        loudnessCacheJob = null
        pendingLoudnessScan = null
        pendingStartPositionMs = positionMs.coerceAtLeast(0L)
        knownMediaDurationMs = durationMs.coerceAtLeast(0L)
        preparationDeferred = deferPreparation
        if (deferPreparation) playWhenReady = false
    }

    /** Cached gain is cheap; a restored paused track must not launch a full stream loudness scan. */
    fun scheduleTrackLoudness(track: Track, url: String, headers: Map<String, String>) {
        val request = PendingLoudnessScan(track, url, headers, activeEngine, mediaLoadGeneration.get(),
            preparationDeferred || activeEngine.isPreparationDeferred)
        loudnessCacheJob?.cancel()
        loudnessCacheJob = scope.launch {
            val cached = com.alananasss.kittytune.data.TrackLoudnessRepository.getLoudness(track.id)
            if (!isActive) return@launch
            synchronized(this@Player) {
                if (!ownsLoudnessRequest(request)) return@synchronized
                if (cached != null) request.engine.setTrackLoudness(cached.integratedLufs, cached.truePeakDb)
                else {
                    request.engine.clearTrackLoudness()
                    pendingLoudnessScan = request
                    startPendingLoudnessScan()
                }
            }
        }
    }

    private fun ownsLoudnessRequest(request: PendingLoudnessScan): Boolean =
        request.generation == mediaLoadGeneration.get() && request.engine === activeEngine && request.engine.currentUrl == request.url

    @Synchronized
    private fun startPendingLoudnessScan() {
        val request = pendingLoudnessScan ?: return
        if (!ownsLoudnessRequest(request)) { pendingLoudnessScan = null; return }
        if (preparationDeferred || request.engine.isPreparationDeferred || request.requiresPlayback && !playWhenReady) return
        pendingLoudnessScan = null
        com.alananasss.kittytune.data.TrackLoudnessRepository.scanTrackAsync(request.track, request.url, request.headers) { scanned ->
            synchronized(this@Player) {
                if (ownsLoudnessRequest(request)) request.engine.setTrackLoudness(scanned.integratedLufs, scanned.truePeakDb)
            }
        }
    }

    private fun loadCurrent() {
        val item = currentMediaItem ?: return
        val engine = activeEngine
        val generation = mediaLoadGeneration.get()
        resolveJob?.cancel()
        resolveJob = scope.launch {
            val rawUrl = item.uri?.takeIf {
                if (it.startsWith("metrofuse-deezer://")) true
                else if (SignedUrl.isNetworkUrl(it)) !SignedUrl.isExpired(it)
                else if (it.startsWith("file:")) runCatching { java.io.File(java.net.URI(it)).exists() }.getOrDefault(false)
                else java.io.File(it).exists()
            }
                ?: item.track?.let { withContext(Dispatchers.IO) { StreamResolver.resolveStream(it) } }
            if (!isActive || generation != mediaLoadGeneration.get() || activeEngine !== engine || currentMediaItem !== item) return@launch
            if (rawUrl == null) {
                synchronized(this@Player) {
                    if (generation != mediaLoadGeneration.get() || activeEngine !== engine || currentMediaItem !== item || preparationDeferred) return@synchronized
                    engine.stop()
                    fadingEngine?.stop(preserveTrackState = true)
                    fadingEngine?.release(preserveTrackState = true)
                    fadingEngine = null
                    listeners.forEach { it.onPlaybackStateChanged(STATE_ENDED) }
                }
                return@launch
            }
            val url = if (rawUrl.startsWith("metrofuse-deezer://")) {
                com.alananasss.kittytune.audio.providers.deezer.DeezerAudioProxy.getPlayableUrlFromDeezerUri(rawUrl)
            } else {
                rawUrl
            }
            val headers = buildHeaders(item.track)

            val track = item.track
            val cached = track?.let { com.alananasss.kittytune.data.TrackLoudnessRepository.getLoudness(it.id) }
            if (!isActive) return@launch
            synchronized(this@Player) {
                if (generation != mediaLoadGeneration.get() || activeEngine !== engine || currentMediaItem !== item) return@synchronized
                val defer = preparationDeferred && !playWhenReady
                fadingEngine?.stop(preserveTrackState = true)
                fadingEngine?.release(preserveTrackState = true)
                fadingEngine = null
                updateEngineVolume()
                engine.setTrackGainDb(trackGainDb)
                if (engine.currentUrl != url) {
                    engine.setMediaItem(url, headers, pendingStartPositionMs, track?.id,
                        deferPreparation = defer, knownDurationMs = knownMediaDurationMs)
                }
                if (cached != null) engine.setTrackLoudness(cached.integratedLufs, cached.truePeakDb)
                else {
                    engine.clearTrackLoudness()
                    if (track != null) pendingLoudnessScan = PendingLoudnessScan(track, url, headers, engine, generation, defer)
                }
                if (!defer && engine.state != AudioEngine.State.READY) engine.prepare()
                if (playWhenReady) engine.play()
                startPendingLoudnessScan()
            }
        }
    }

    /** The request headers a stream for [track] is opened with. */
    fun buildHeaders(track: Track?): Map<String, String> {
        val headers = mutableMapOf("User-Agent" to "SoundCloud/2025.12.10-release (Android 10; Android)")
        if (track?.source != "youtube") {
            headers["Origin"] = "https://soundcloud.com"
            headers["Referer"] = "https://soundcloud.com/"
        }
        return headers
    }

    private fun cancelAutomixStem() {
        automixStemGeneration.incrementAndGet()
        activeEngine.clearAutomixStem()
        fadingEngine?.clearAutomixStem()
    }

    fun applyEffects(state: AudioEffectsState) {
        cancelAutomixStem()
        cancelTrimSplice()
        lastEffectsState = state
        activeEngine.applyEffects(state)
        fadingEngine?.applyEffects(state)
    }

    fun onKaraokeStemReady(trackId: Long) {
        activeEngine.onKaraokeStemReady(trackId)
        fadingEngine?.onKaraokeStemReady(trackId)
    }
}

typealias PlayerCompat = Player

