package info.benjaminhill.beaconmesh.domain.model

import info.benjaminhill.beaconmesh.domain.MeshConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PacketTest {

    @Test
    fun `packet serialization and deserialization works correctly`() {
        val originalPacket = Packet(
            sourceId = 123456,
            sequence = 42,
            ttl = 3,
            payload = "Hello Mesh"
        )

        val base64 = originalPacket.toBase64()
        val deserializedPacket = Packet.fromBase64(base64)

        assertNotNull("Deserialized packet should not be null", deserializedPacket)
        assertEquals("NetID should match", MeshConfig.NET_ID, deserializedPacket?.netId)
        assertEquals("TargetID should match", MeshConfig.BROADCAST_TARGET_ID, deserializedPacket?.targetId)
        assertEquals("SourceID should match", 123456, deserializedPacket?.sourceId)
        assertEquals("Sequence should match", 42.toShort(), deserializedPacket?.sequence)
        assertEquals("TTL should match", 3.toByte(), deserializedPacket?.ttl)
        assertEquals("Payload should match", "Hello Mesh", deserializedPacket?.payload)
    }

    @Test
    fun `packet handles empty payload`() {
        val originalPacket = Packet(
            sourceId = 999,
            sequence = 1,
            ttl = 1,
            payload = ""
        )

        val base64 = originalPacket.toBase64()
        val deserializedPacket = Packet.fromBase64(base64)

        assertEquals("", deserializedPacket?.payload)
    }

    @Test
    fun `packet handles special characters in payload`() {
        val text = "🔥 Hello World! @#%&*"
        val originalPacket = Packet(
            sourceId = 1,
            sequence = 1,
            ttl = 1,
            payload = text
        )

        val base64 = originalPacket.toBase64()
        val deserializedPacket = Packet.fromBase64(base64)

        assertEquals(text, deserializedPacket?.payload)
    }
}
