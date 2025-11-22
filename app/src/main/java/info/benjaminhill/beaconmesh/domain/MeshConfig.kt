package info.benjaminhill.beaconmesh.domain

import kotlin.time.Duration.Companion.seconds

object MeshConfig {
    // 2 bytes for NetID (Magic bytes)
    const val NET_ID: Short = 0xBEAC.toShort()

    // 4 bytes for Broadcast Target
    const val BROADCAST_TARGET_ID: Int = 0xFFFFFFFF.toInt()

    // Base64 Overhead: 4 chars for every 3 bytes.
    // 80 chars Base64 ~= 60 bytes binary.
    // Header size: 13 bytes.
    // Max payload: ~47 bytes.

    const val INITIAL_TTL: Byte = 3
    const val SERVICE_ID = "MSH"

    // Time to advertise a message before stopping (Duty Cycle)
    val MAX_ADVERTISE_DURATION = 15.seconds

    // Max items in the queue before we start dropping packets
    const val MAX_QUEUE_SIZE = 50

    // Time to wait between re-discovery cycles (if needed)
     val DISCOVERY_CYCLE = 30.seconds
}
