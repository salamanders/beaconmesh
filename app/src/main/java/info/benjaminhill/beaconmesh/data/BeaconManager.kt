package info.benjaminhill.beaconmesh.data

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Strategy
import info.benjaminhill.beaconmesh.domain.MeshConfig
import info.benjaminhill.beaconmesh.domain.model.Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

class BeaconManager(
    context: Context,
    private val scope: CoroutineScope
) {
    // Nearby Connections
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val serviceId = MeshConfig.SERVICE_ID
    private val strategy = Strategy.P2P_POINT_TO_POINT

    // Standard BLE
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter
    private val bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner

    private val _discoveredPackets = MutableSharedFlow<Packet>(extraBufferCapacity = 64)
    val discoveredPackets = _discoveredPackets.asSharedFlow()

    private val packetQueue = LinkedBlockingDeque<Packet>(MeshConfig.MAX_QUEUE_SIZE)

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

    private val flipperScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val scanRecord = result.scanRecord ?: return
            val serviceUuid = ParcelUuid.fromString(MeshConfig.FLIPPER_SERVICE_UUID_STRING)
            val data = scanRecord.getServiceData(serviceUuid) ?: return

            if (data.size < 3) return // Seq(2) + Payload(1+)

            try {
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val sequence = buffer.short
                val payloadBytes = ByteArray(data.size - 2)
                buffer.get(payloadBytes)
                val payload = String(payloadBytes, StandardCharsets.UTF_8)

                val packet = Packet(
                    sourceId = MeshConfig.FLIPPER_SOURCE_ID,
                    sequence = sequence,
                    ttl = MeshConfig.INITIAL_TTL,
                    payload = payload
                )
                _discoveredPackets.tryEmit(packet)
                Timber.d("Received Flipper packet: $payload (Seq: $sequence)")
            } catch (e: Exception) {
                Timber.e(e, "Error parsing Flipper packet")
            }
        }
    }

    fun startScanning() {
        Timber.i("Starting Discovery...")
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(serviceId, endpointDiscoveryCallback, options)
            .addOnSuccessListener { Timber.d("Discovery started") }
            .addOnFailureListener { e -> Timber.e(e, "Discovery failed") }

        // Start BLE Scan for Flipper
        try {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(MeshConfig.FLIPPER_SERVICE_UUID_STRING))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bluetoothLeScanner?.startScan(listOf(filter), settings, flipperScanCallback)
            Timber.d("BLE Scanning started for Flipper")
        } catch (e: SecurityException) {
            Timber.e(e, "Missing permission for BLE scan")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start BLE scan")
        }
    }

    fun stopScanning() {
        connectionsClient.stopDiscovery()
        try {
            bluetoothLeScanner?.stopScan(flipperScanCallback)
            Timber.d("BLE Scanning stopped")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop BLE scan")
        }
    }

    /**
     * Advertises a packet for a set duration, then stops.
     * This implements the "Duty Cycle".
     */
    fun advertisePacket(packet: Packet) {
        while (!packetQueue.offer(packet)) {
            packetQueue.poll() // Drop oldest
        }
        Timber.d("Packet added to queue: ${packet.sequence}. Queue size: ${packetQueue.size}")
    }

    private fun startQueueProcessing() {
        advertisingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val packet = try {
                    packetQueue.take()
                } catch (_: InterruptedException) {
                    break
                }
                val payload = packet.toBase64()
                val queueSize = packetQueue.size
                // Dynamic duration: 15s if empty, down to 500ms if full
                val duration = max(
                    500L,
                    MeshConfig.MAX_ADVERTISE_DURATION.inWholeMilliseconds / (queueSize + 1)
                ).milliseconds

                Timber.d("Processing queue item, queue size: $queueSize, advertising for $duration ms. Payload: $payload")

                if (isAdvertising.get()) {
                    connectionsClient.stopAdvertising()
                    delay(500.milliseconds) // Wait for radio to clear
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

                delay(duration)

                connectionsClient.stopAdvertising()
                isAdvertising.set(false)
                Timber.d("Advertising stopped (Duty Cycle end)")

                // Small cooldown between packets to ensure clean state
                delay(500.milliseconds)
            }
        }
    }

    fun stopAll() {
        advertisingJob?.cancel()
        packetQueue.clear()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
    }
}
