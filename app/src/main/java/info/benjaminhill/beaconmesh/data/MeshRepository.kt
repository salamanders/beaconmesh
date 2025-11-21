package info.benjaminhill.beaconmesh.data

import info.benjaminhill.beaconmesh.domain.DeviceIdentity
import info.benjaminhill.beaconmesh.domain.MeshConfig
import info.benjaminhill.beaconmesh.domain.model.Packet
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

// Manual DI for simplicity as per snippet style, or just Singleton
class MeshRepository(
    private val deviceIdentity: DeviceIdentity
) {
    // Cache: "SourceID:Seq" -> Timestamp
    private val seenMessages = ConcurrentHashMap<String, Long>()

    private val _messages = MutableStateFlow<List<Packet>>(emptyList())
    val messages = _messages.asStateFlow()

    // Packets that need to be rebroadcast (Relay)
    private val _relayQueue = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    val relayQueue = _relayQueue.asSharedFlow()

    // Currently broadcasting packet (User's status)
    private val _currentStatus = MutableStateFlow<Packet?>(null)
    val currentStatus = _currentStatus.asStateFlow()

    private var localSequence: Short = 0

    fun updateStatus(text: String) {
        localSequence++
        val packet = Packet(
            sourceId = deviceIdentity.sourceId,
            sequence = localSequence,
            ttl = MeshConfig.INITIAL_TTL,
            payload = text
        )
        _currentStatus.value = packet
        // Add to our own log? Yes.
        addMessageToLog(packet)
    }

    fun processIncomingPacket(packet: Packet) {
        val key = "${packet.sourceId}:${packet.sequence}"

        // 1. Check Cache
        if (seenMessages.containsKey(key)) {
            // Already seen, ignore
            return
        }
        seenMessages[key] = System.currentTimeMillis()

        // 2. Ignore own messages (echo)
        if (packet.sourceId == deviceIdentity.sourceId) {
            return
        }

        // 3. Log/Display
        addMessageToLog(packet)

        // 4. Relay Logic
        if (packet.ttl > 0) {
            val rebroadcastPacket = packet.copy(ttl = (packet.ttl - 1).toByte())
            // "Target" check: If we are not the target and it's not broadcast, do we relay?
            // Mesh usually relays everything.
            // If target is specific, we relay.
            // If target is us, we consume and maybe don't relay?
            // Connectionless mesh usually floods, so relaying continues until TTL dies.

            _relayQueue.tryEmit(rebroadcastPacket)
        }
    }

    private fun addMessageToLog(packet: Packet) {
        _messages.update { current ->
            (current + packet).sortedByDescending { it.sequence } // Naive sort, should probably be timestamp
                .take(100)
        }
    }
}
