package info.benjaminhill.beaconmesh.domain

object MeshConfig {
    // 2 bytes for NetID (Magic bytes)
    const val NET_ID: Short = 0xBEAC.toShort()

    // 4 bytes for Broadcast Target
    const val BROADCAST_TARGET_ID: Int = 0xFFFFFFFF.toInt()

    // Max Packet Size (Safety Limit for EndpointName)
    const val MAX_PACKET_SIZE_BYTES = 80

    // Base64 Overhead: 4 chars for every 3 bytes.
    // 80 chars Base64 ~= 60 bytes binary.
    // Header size: 13 bytes.
    // Max payload: ~47 bytes.

    const val INITIAL_TTL: Byte = 3
    const val SERVICE_ID = "info.benjaminhill.beaconmesh"

    // Time to advertise a message before stopping (Duty Cycle)
    const val ADVERTISE_DURATION_MS = 15_000L

    // Time to wait between re-discovery cycles (if needed)
    const val DISCOVERY_CYCLE_MS = 30_000L
}
