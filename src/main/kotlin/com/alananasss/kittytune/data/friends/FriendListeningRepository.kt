package com.alananasss.kittytune.data.friends

import com.alananasss.kittytune.core.NamedPrefs
import com.alananasss.kittytune.domain.Track
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.logging.Logger

data class FriendListeningSettings(val sharingEnabled: Boolean = false, val showName: Boolean = false)
data class FriendContact(val code: String)
data class FriendListeningActivity(val friendCode: String, val track: Track, val displayName: String?, val expiresAtMs: Long)
enum class FriendInviteResult { ADDED, ALREADY_ADDED, INVALID_CODE, OWN_CODE, LIMIT_REACHED, UNAVAILABLE }

/** Explicit sharing only. Activity snapshots are ephemeral; device sync permissions are never involved. */
object FriendListeningRepository {
    private val serialIo = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serialIo)
    private val logger = Logger.getLogger("FriendListeningRepository")
    private var engine: FriendListeningEngine? = null
    private var requestedStarted = false
    private var retryInitializationAfterNs = 0L
    private val _settings = MutableStateFlow(FriendListeningSettings())
    val settings = _settings.asStateFlow()
    private val _activities = MutableStateFlow<List<FriendListeningActivity>>(emptyList())
    val activities = _activities.asStateFlow()
    private val _friends = MutableStateFlow<List<FriendContact>>(emptyList())
    val friends = _friends.asStateFlow()
    private val _inviteCode = MutableStateFlow<String?>(null)
    val inviteCode = _inviteCode.asStateFlow()

    /** Called only by the serial IO dispatcher; simply collecting any UI flow does no disk or crypto work. */
    private fun initialize(): FriendListeningEngine? {
        engine?.let { return it }
        if (retryInitializationAfterNs != 0L && System.nanoTime() - retryInitializationAfterNs < 0) return null
        return try {
            val prefs = NamedPrefs("friend_listening_private")
            FriendListeningEngine(FriendIdentity.load(prefs), FriendPreferenceStore(prefs), FriendPresenceRelay()).also { created ->
                engine = created
                _settings.value = created.settings.value
                _friends.value = created.friends.value
                _inviteCode.value = created.ownInviteCode
                scope.launch { created.settings.collect { _settings.value = it } }
                scope.launch { created.activities.collect { _activities.value = it } }
                scope.launch { created.friends.collect { _friends.value = it } }
                if (requestedStarted) created.start()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            retryInitializationAfterNs = System.nanoTime() + 10_000_000_000L
            logger.warning("Friend exchange initialization failed: ${exception.javaClass.simpleName}")
            null
        }
    }

    private fun enqueue(shouldInitialize: Boolean = true, action: (FriendListeningEngine) -> Unit) {
        submit {
            val current = if (shouldInitialize) initialize() else engine
            current?.let(action)
        }
    }

    private fun submit(action: () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.warning("Friend exchange command failed: ${exception.javaClass.simpleName}")
            }
        }
    }

    fun start() = submit { requestedStarted = true; initialize()?.start() }
    fun close() = submit { requestedStarted = false; engine?.close() }
    fun setSharingEnabled(enabled: Boolean) = enqueue { it.setSharingEnabled(enabled) }
    fun setShowName(enabled: Boolean) = enqueue { it.setShowName(enabled) }
    suspend fun addFriend(code: String): FriendInviteResult = withContext(serialIo) {
        try {
            initialize()?.addFriend(code) ?: FriendInviteResult.UNAVAILABLE
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            logger.warning("Adding a friend failed: ${exception.javaClass.simpleName}")
            FriendInviteResult.UNAVAILABLE
        }
    }
    fun removeFriend(code: String) = enqueue { it.removeFriend(code) }
    fun updatePlayback(track: Track?, isPlaying: Boolean, displayName: String? = null) = enqueue {
        it.updatePlayback(track, isPlaying, displayName)
    }
}

internal interface FriendStore {
    fun settings(): FriendListeningSettings
    fun contacts(): List<String>
    fun sequence(): Long
    fun receivedSequences(): Map<String, Long>
    fun saveSettings(value: FriendListeningSettings)
    fun saveContacts(value: List<String>)
    fun saveSequence(value: Long)
    fun saveReceivedSequences(value: Map<String, Long>)
}

internal class FriendPreferenceStore(private val prefs: NamedPrefs) : FriendStore {
    private val gson = Gson()
    override fun settings() = FriendListeningSettings(prefs.getBoolean("sharing", false), prefs.getBoolean("show_name", false))
    override fun contacts(): List<String> = runCatching {
        val raw = prefs.getString("contacts", "[]").orEmpty()
        require(raw.length <= 8192)
        gson.fromJson<List<String>>(raw, object : TypeToken<List<String>>() {}.type).take(32)
    }.getOrDefault(emptyList())
    override fun sequence(): Long = prefs.getLong("sequence", 0).coerceAtLeast(0)
    override fun receivedSequences(): Map<String, Long> = runCatching {
        val raw = prefs.getString("received_sequences", "{}").orEmpty()
        require(raw.length <= 8192)
        gson.fromJson<Map<String, Long>>(raw, object : TypeToken<Map<String, Long>>() {}.type)
            .entries.take(32).associate { it.key to it.value.coerceAtLeast(0) }
    }.getOrDefault(emptyMap())
    override fun saveSettings(value: FriendListeningSettings) {
        prefs.putBoolean("sharing", value.sharingEnabled); prefs.putBoolean("show_name", value.showName)
    }
    override fun saveContacts(value: List<String>) = prefs.putString("contacts", gson.toJson(value))
    override fun saveSequence(value: Long) = prefs.putLong("sequence", value)
    override fun saveReceivedSequences(value: Map<String, Long>) = prefs.putString("received_sequences", gson.toJson(value))
}

internal class FriendListeningEngine(
    private val identity: FriendIdentity,
    private val store: FriendStore,
    private val transport: FriendTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val logger = Logger.getLogger("FriendListeningEngine")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tickJob: Job? = null
    private var started = false
    private var connected = false
    private var sequence = store.sequence()
    private val contacts = store.contacts().mapNotNull(FriendInvite::parse)
        .filterNot { it.code == identity.invite.code }.distinctBy { it.code }.take(32).associateBy { it.code }.toMutableMap()
    private val replay = FriendReplayGuard(store.receivedSequences().filterKeys { it in contacts })
    private val lastVerificationAt = mutableMapOf<String, Long>()
    private var track: FriendPublicTrack? = null
    private var name: String? = null
    private var lastPublishedAt = 0L
    private var publishRetryAt = 0L
    private var transportRetryAt = 0L
    private var dirty = true
    private val _settings = MutableStateFlow(store.settings())
    val settings = _settings.asStateFlow()
    private val _friends = MutableStateFlow(contacts.keys.map(::FriendContact))
    val friends = _friends.asStateFlow()
    private val _activities = MutableStateFlow<List<FriendListeningActivity>>(emptyList())
    val activities = _activities.asStateFlow()
    val ownInviteCode: String get() = identity.invite.code

    fun start() = synchronized(lock) {
        if (started) return@synchronized
        started = true
        reconcileTransport()
    }

    fun close() = synchronized(lock) {
        started = false
        tickJob?.cancel(); tickJob = null
        safely("close transport") { transport.close() }; connected = false
        _activities.value = emptyList()
        lastVerificationAt.clear()
        track = null; name = null
        dirty = true
    }

    fun setSharingEnabled(enabled: Boolean) = synchronized(lock) {
        val mustClear = _settings.value.sharingEnabled && !enabled && started
        _settings.value = _settings.value.copy(sharingEnabled = enabled)
        safely("save settings") { store.saveSettings(_settings.value) }
        dirty = true
        try { if (mustClear) publish(clear = true) } finally { reconcileTransport() }
    }

    fun setShowName(enabled: Boolean) = synchronized(lock) {
        _settings.value = _settings.value.copy(showName = enabled)
        safely("save settings") { store.saveSettings(_settings.value) }
        dirty = true
    }

    fun addFriend(raw: String): FriendInviteResult = synchronized(lock) {
        val invite = FriendInvite.parse(raw) ?: return@synchronized FriendInviteResult.INVALID_CODE
        if (invite.code == ownInviteCode) return@synchronized FriendInviteResult.OWN_CODE
        if (invite.code in contacts) return@synchronized FriendInviteResult.ALREADY_ADDED
        if (contacts.size >= 32) return@synchronized FriendInviteResult.LIMIT_REACHED
        contacts[invite.code] = invite
        persistContacts()
        reconcileTransport()
        FriendInviteResult.ADDED
    }

    fun removeFriend(raw: String) = synchronized(lock) {
        val code = FriendInvite.parse(raw)?.code ?: return@synchronized
        contacts.remove(code)
        replay.remove(code); lastVerificationAt.remove(code)
        safely("save replay state") { store.saveReceivedSequences(replay.snapshot()) }
        _activities.value = _activities.value.filterNot { it.friendCode == code }
        persistContacts()
        reconcileTransport()
    }

    fun updatePlayback(value: Track?, isPlaying: Boolean, displayName: String?) = synchronized(lock) {
        val nextTrack = if (isPlaying) FriendPublicTrack.of(value) else null
        val nextName = FriendPublicTrack.text(displayName, 60).takeIf { it.isNotEmpty() }
        if (track != nextTrack || name != nextName) dirty = true
        track = nextTrack; name = nextName
    }

    internal fun tick() = synchronized(lock) {
        if (!started) return@synchronized
        val now = clock()
        _activities.value = _activities.value.filter { it.expiresAtMs > now }
        reconcileTransport()
        if (connected && _settings.value.sharingEnabled && now >= publishRetryAt &&
            (dirty || now - lastPublishedAt >= 10_000)) publish(clear = false)
    }

    private fun publish(clear: Boolean) {
        if (sequence == Long.MAX_VALUE) return
        val now = clock()
        val success = safely("publish presence") {
            val presence = FriendPresence(++sequence, now, now + FriendPresenceWire.TTL_MS,
                name.takeIf { !clear && _settings.value.showName }, track.takeUnless { clear })
            val bytes = FriendPresenceWire.seal(identity, presence)
            store.saveSequence(sequence)
            transport.publish(identity.invite.topic, bytes)
        }
        if (success) {
            lastPublishedAt = now; dirty = false; publishRetryAt = 0
        } else {
            dirty = true; publishRetryAt = now + 5_000
        }
    }

    private fun receive(topic: String, bytes: ByteArray) = synchronized(lock) {
        if (!started || bytes.size > FriendPresenceWire.MAX_BYTES) return@synchronized
        val invite = contacts.values.firstOrNull { it.topic == topic } ?: return@synchronized
        val now = clock()
        if (now - (lastVerificationAt[invite.code] ?: Long.MIN_VALUE / 2) < 100) return@synchronized
        lastVerificationAt[invite.code] = now
        val presence = FriendPresenceWire.open(invite, bytes, now) ?: return@synchronized
        if (!replay.accept(invite.code, presence.sequence)) return@synchronized
        safely("save replay state") { store.saveReceivedSequences(replay.snapshot()) }
        val others = _activities.value.filter { it.friendCode != invite.code && it.expiresAtMs > now }
        _activities.value = if (presence.track == null) others else others + FriendListeningActivity(
            invite.code, presence.track.toTrack(), presence.displayName,
            minOf(presence.expiresAtMs, now + FriendPresenceWire.TTL_MS))
    }

    private fun persistContacts() {
        safely("save contacts") { store.saveContacts(contacts.keys.toList()) }
        _friends.value = contacts.keys.map(::FriendContact)
    }

    private fun reconcileTransport() {
        val wanted = started && (_settings.value.sharingEnabled || contacts.isNotEmpty())
        if (wanted) {
            if (tickJob?.isActive != true) tickJob = scope.launch {
                while (isActive) {
                    safely("heartbeat") { tick() }
                    delay(1_000)
                }
            }
            if (clock() < transportRetryAt) return
            val topics = contacts.values.map { it.topic }.toSet()
            if (safely("update transport") {
                if (!connected) { transport.start(topics, ::receive); connected = true } else transport.updateTopics(topics)
            }) {
                transportRetryAt = 0
            } else {
                connected = false; transportRetryAt = clock() + 5_000
                safely("close transport") { transport.close() }
            }
        } else {
            tickJob?.cancel(); tickJob = null
            if (connected) safely("close transport") { transport.close() }
            connected = false
        }
    }

    private fun safely(operation: String, action: () -> Unit): Boolean = try {
        action()
        true
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        logger.warning("Friend exchange $operation failed: ${exception.javaClass.simpleName}")
        false
    }
}
