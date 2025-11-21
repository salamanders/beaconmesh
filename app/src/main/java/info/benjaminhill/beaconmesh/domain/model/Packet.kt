package info.benjaminhill.beaconmesh.domain.model

import info.benjaminhill.beaconmesh.domain.MeshConfig
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Binary Structure:
 * [NetID: 2b][TargetID: 4b][SourceID: 4b][Seq: 2b][TTL: 1b][Payload: Var]
 * Total Header: 13 bytes
 */
data class Packet(
    val netId: Short = MeshConfig.NET_ID,
    val targetId: Int = MeshConfig.BROADCAST_TARGET_ID,
    val sourceId: Int,
    val sequence: Short,
    val ttl: Byte,
    val payload: String
) {
    fun toBase64(): String {
        val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
        val capacity = 13 + payloadBytes.size
        val buffer = ByteBuffer.allocate(capacity)

        buffer.putShort(netId)
        buffer.putInt(targetId)
        buffer.putInt(sourceId)
        buffer.putShort(sequence)
        buffer.put(ttl)
        buffer.put(payloadBytes)

        return Base64.getEncoder().encodeToString(buffer.array())
    }

    companion object {
        fun fromBase64(base64String: String): Packet? {
            return try {
                val bytes = Base64.getDecoder().decode(base64String)
                if (bytes.size < 13) {
                    Timber.w("Packet too short: ${bytes.size}")
                    return null
                }

                val buffer = ByteBuffer.wrap(bytes)
                val netId = buffer.short
                if (netId != MeshConfig.NET_ID) {
                    Timber.d("Invalid NetID: $netId")
                    return null
                }

                val targetId = buffer.int
                val sourceId = buffer.int
                val sequence = buffer.short
                val ttl = buffer.get()

                val payloadBytes = ByteArray(bytes.size - 13)
                buffer.get(payloadBytes)
                val payload = String(payloadBytes, StandardCharsets.UTF_8)

                Packet(netId, targetId, sourceId, sequence, ttl, payload)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse packet: $base64String")
                null
            }
        }
    }
}
