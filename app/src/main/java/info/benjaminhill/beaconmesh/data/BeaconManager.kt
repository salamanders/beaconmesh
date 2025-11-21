package info.benjaminhill.beaconmesh.data

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Strategy
import info.benjaminhill.beaconmesh.domain.MeshConfig
import info.benjaminhill.beaconmesh.domain.model.Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

class BeaconManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = MeshConfig.SERVICE_ID
    private val strategy = Strategy.P2P_POINT_TO_POINT

    private val _discoveredPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    val discoveredPackets = _discoveredPackets.asSharedFlow()

    private val packetQueue = Channel<Packet>(
        capacity = 15,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var advertisingJob: Job? = null
    private val isAdvertising = AtomicBoolean(false)

    init {
        startQueueProcessing()
    }

    // Dummy callback - we never connect
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Reject all connections. We only want the advertisement.
            connectionsClient.rejectConnection(endpointId)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {}
        override fun onDisconnected(endpointId: String) {}
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Timber.d("Endpoint found: ${info.endpointName}")
            val packet = Packet.fromBase64(info.endpointName)
            if (packet != null) {
                _discoveredPackets.tryEmit(packet)
            } else {
                Timber.w("Found non-mesh endpoint or bad packet: ${info.endpointName}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            // No-op in connectionless usually, unless we track presence
        }
    }

    fun startScanning() {
        Timber.i("Starting Discovery...")
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener { Timber.d("Discovery started") }
            .addOnFailureListener { e -> Timber.e(e, "Discovery failed") }
    }

    fun stopScanning() {
        connectionsClient.stopDiscovery()
    }

    /**
     * Advertises a packet for a set duration, then stops.
     * This implements the "Duty Cycle".
     */
    fun advertisePacket(packet: Packet) {
        val result = packetQueue.trySend(packet)
        if (result.isSuccess) {
            Timber.d("Packet added to queue: ${packet.sequence}")
        } else {
            Timber.w("Packet queue full/closed, failed to add: ${packet.sequence}")
        }
    }

    private fun startQueueProcessing() {
        advertisingJob = scope.launch(Dispatchers.IO) {
            for (packet in packetQueue) {
                val payload = packet.toBase64()
                Timber.d("Processing queue item, advertising payload: $payload")

                if (isAdvertising.get()) {
                    connectionsClient.stopAdvertising()
                    delay(500) // Wait for radio to clear
                }

                val options = AdvertisingOptions.Builder().setStrategy(strategy).build()

                connectionsClient.startAdvertising(
                    payload,
                    serviceId,
                    connectionLifecycleCallback,
                    options
                ).addOnSuccessListener {
                    Timber.d("Advertising started: ${packet.sequence}")
                    isAdvertising.set(true)
                }.addOnFailureListener { e ->
                    Timber.e(e, "Advertising failed")
                    isAdvertising.set(false)
                }

                delay(MeshConfig.ADVERTISE_DURATION)

                connectionsClient.stopAdvertising()
                isAdvertising.set(false)
                Timber.d("Advertising stopped (Duty Cycle end)")

                // Small cooldown between packets to ensure clean state
                delay(500)
            }
        }
    }

    fun stopAll() {
        advertisingJob?.cancel()
        packetQueue.close() // Or clear? Close stops iteration.
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
    }
}
