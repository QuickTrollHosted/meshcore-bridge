package fr.qth.meshcorebridge.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FramingTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun encodeToClient_hasMarkerAndLittleEndianLength() {
        val payload = ByteArray(300) { it.toByte() }
        val enc = Framing.encodeToClient(payload)
        assertEquals(0x3E, enc[0].toInt())
        assertEquals(300 and 0xFF, enc[1].toInt() and 0xFF)   // 0x2C
        assertEquals(300 ushr 8, enc[2].toInt() and 0xFF)     // 0x01
        assertArrayEquals(payload, enc.copyOfRange(3, enc.size))
    }

    @Test fun encodeToNode_usesLessThanMarker() {
        assertArrayEquals(b(0x3C, 2, 0, 0x16, 0x03), Framing.encodeToNode(b(0x16, 0x03)))
    }

    @Test fun singleFrame() {
        val d = TcpFrameDecoder()
        val r = d.feed(b(0x3C, 2, 0, 0x16, 0x03))
        assertEquals(1, r.size)
        assertArrayEquals(b(0x16, 0x03), r[0])
        assertFalse(d.hasPartialFrame)
    }

    @Test fun concatenatedFrames() {
        val d = TcpFrameDecoder()
        val data = b(0x3C, 1, 0, 0xAA, 0x3C, 3, 0, 1, 2, 3, 0x3C, 1, 0, 0xBB)
        val r = d.feed(data)
        assertEquals(3, r.size)
        assertArrayEquals(b(0xAA), r[0])
        assertArrayEquals(b(1, 2, 3), r[1])
        assertArrayEquals(b(0xBB), r[2])
    }

    @Test fun fragmentation_byteByByte() {
        val d = TcpFrameDecoder()
        val wire = Framing.encodeToNode(b(1, 2, 3, 4, 5)) + Framing.encodeToNode(b(9))
        val got = mutableListOf<ByteArray>()
        for (x in wire) got += d.feed(byteArrayOf(x))
        assertEquals(2, got.size)
        assertArrayEquals(b(1, 2, 3, 4, 5), got[0])
        assertArrayEquals(b(9), got[1])
    }

    @Test fun fragmentation_everySplitPoint() {
        val wire = Framing.encodeToNode(b(7, 8, 9)) + Framing.encodeToNode(b(1))
        for (split in 0..wire.size) {
            val d = TcpFrameDecoder()
            val got = d.feed(wire.copyOfRange(0, split)) + d.feed(wire.copyOfRange(split, wire.size))
            assertEquals("split=$split", 2, got.size)
            assertArrayEquals(b(7, 8, 9), got[0])
            assertArrayEquals(b(1), got[1])
        }
    }

    @Test fun incompleteFrame_isKeptUntilRest() {
        val d = TcpFrameDecoder()
        assertTrue(d.feed(b(0x3C, 4, 0, 1, 2)).isEmpty())
        assertTrue(d.hasPartialFrame)
        val r = d.feed(b(3, 4))
        assertEquals(1, r.size)
        assertArrayEquals(b(1, 2, 3, 4), r[0])
        assertFalse(d.hasPartialFrame)
    }

    @Test fun incompleteHeader_isKept() {
        val d = TcpFrameDecoder()
        assertTrue(d.feed(b(0x3C)).isEmpty())
        assertTrue(d.feed(b(2)).isEmpty())
        assertTrue(d.hasPartialFrame)
        val r = d.feed(b(0, 5, 6))
        assertEquals(1, r.size)
        assertArrayEquals(b(5, 6), r[0])
    }

    @Test fun garbageBeforeMarker_isSkippedAndCounted() {
        val d = TcpFrameDecoder()
        val r = d.feed(b(0x00, 0xFF, 0x3E, 0x3C, 1, 0, 0x42))
        assertEquals(1, r.size)
        assertArrayEquals(b(0x42), r[0])
        assertEquals(3, d.discardedBytes)
    }

    @Test fun zeroLength_isIgnoredLikeFirmware() {
        val d = TcpFrameDecoder()
        val r = d.feed(b(0x3C, 0, 0, 0x3C, 1, 0, 0x01))
        assertEquals(1, r.size)
        assertArrayEquals(b(1), r[0])
    }

    @Test fun oversizeLength_throwsAndResets() {
        val d = TcpFrameDecoder(maxFrameSize = 100)
        try {
            d.feed(b(0x3C, 101, 0, 1, 2, 3))
            fail("FrameException attendue")
        } catch (e: FrameException) {
            assertTrue(e.message!!.contains("101"))
        }
        assertFalse(d.hasPartialFrame)
        // Le décodeur reste utilisable après reset.
        assertEquals(1, d.feed(b(0x3C, 1, 0, 5)).size)
    }

    @Test fun maxLengthExactly_isAccepted() {
        val d = TcpFrameDecoder(maxFrameSize = 176)
        val payload = ByteArray(176) { 1 }
        val r = d.feed(Framing.encodeToNode(payload))
        assertEquals(1, r.size)
        assertEquals(176, r[0].size)
    }

    @Test fun lengthUsesBothBytes_littleEndian() {
        val d = TcpFrameDecoder(maxFrameSize = 400)
        val payload = ByteArray(0x0102) { 3 }  // 258 octets : 0x02 0x01
        val r = d.feed(byteArrayOf(0x3C, 0x02, 0x01) + payload)
        assertEquals(1, r.size)
        assertEquals(258, r[0].size)
    }

    @Test fun feedWithOffsetAndLength() {
        val d = TcpFrameDecoder()
        val buf = b(0xFF, 0x3C, 1, 0, 0x11, 0xEE)
        val r = d.feed(buf, 1, 4)
        assertEquals(1, r.size)
        assertArrayEquals(b(0x11), r[0])
    }
}
