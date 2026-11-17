package com.tether.display.proto

import com.tether.display.proto.TetherProtocol.Message
import com.tether.display.proto.TetherProtocol.MsgType
import com.tether.display.proto.TetherProtocol.PacketHeader
import com.tether.display.proto.TetherProtocol.PacketReader
import com.tether.display.proto.TetherProtocol.PacketWriter
import com.tether.display.proto.TetherProtocol.ProtocolException
import com.tether.display.proto.TetherProtocol.TouchAction
import com.tether.display.proto.TetherProtocol.VideoFrameMeta
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class ProtocolTest {

    private val writer = PacketWriter()
    private val reader = PacketReader()

    @Test
    fun `round trip all message types`() {
        val messages = listOf(
            Message.Hello(
                displayWidth = 960,
                displayHeight = 540,
                densityDpi = 240,
                maxDecodeWidth = 1920,
                maxDecodeHeight = 1080,
                maxFps = 60,
                decoderName = "OMX.MTK.VIDEO.DECODER.AVC"
            ),
            Message.Config(width = 1920, height = 1080, fps = 60, codecFlags = 1),
            Message.VideoFrame(
                frameSeq = 42L,
                ptsUs = 123_456_789L,
                isKeyFrame = true,
                isCodecConfig = false,
                nalData = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte())
            ),
            Message.VideoFrame(
                frameSeq = 43L,
                ptsUs = 123_473_455L,
                isKeyFrame = false,
                isCodecConfig = true,
                nalData = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E)
            ),
            Message.Touch(TouchAction.DOWN, pointerId = 0, x = 0, y = 65535),
            Message.Touch(TouchAction.MOVE, pointerId = 0, x = 32768, y = 16384),
            Message.Touch(TouchAction.UP, pointerId = 0, x = 65535, y = 0),
            Message.Touch(TouchAction.CANCEL, pointerId = 0, x = 100, y = 200),
            Message.Ping(9876543210L),
            Message.Pong(9876543210L),
            Message.RequestKeyframe,
            Message.DisplayChange(width = 540, height = 960, densityDpi = 240, rotationDegrees = 90),
            Message.Bye(reasonCode = 7)
        )

        val out = ByteArrayOutputStream()
        for (m in messages) {
            writer.writeMessage(out, m)
        }

        val input = ByteArrayInputStream(out.toByteArray())
        for (expected in messages) {
            val actual = reader.readMessage(input)
            assertEquals(expected, actual)
        }
        assertNull(reader.readMessage(input))
    }

    @Test
    fun `exact wire vector for TOUCH and REQUEST_KEYFRAME`() {
        val out = ByteArrayOutputStream()
        writer.writeTouch(out, TouchAction.MOVE, pointerId = 0, x = 0x1234, y = 0xABCD)
        val expectedTouch = byteArrayOf(
            0x54, 0x48,             // Magic "TH"
            0x01,                   // Version 1
            0x04,                   // MsgType TOUCH
            0x00, 0x00,             // Flags 0
            0x00, 0x00, 0x00, 0x08, // Payload len = 8
            0x01,                   // Action MOVE
            0x00,                   // Pointer 0
            0x12, 0x34,             // X
            0xAB.toByte(), 0xCD.toByte(), // Y
            0x00, 0x00              // Reserved
        )
        assertArrayEquals(expectedTouch, out.toByteArray())

        val reqOut = ByteArrayOutputStream()
        writer.writeRequestKeyframe(reqOut)
        val expectedReq = byteArrayOf(
            0x54, 0x48,
            0x01,
            0x07,
            0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        )
        assertArrayEquals(expectedReq, reqOut.toByteArray())
    }

    @Test
    fun `zero-alloc hot-path video frame reading into reusable buffer`() {
        val nal = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x11, 0x22, 0x33)
        val out = ByteArrayOutputStream()
        writer.writeVideoFrame(
            out,
            Message.VideoFrame(
                frameSeq = 99L,
                ptsUs = 555_000L,
                isKeyFrame = true,
                isCodecConfig = false,
                nalData = nal
            )
        )

        val input = ByteArrayInputStream(out.toByteArray())
        val header = PacketHeader()
        val meta = VideoFrameMeta()
        val reusableSlot = ByteArray(64)

        assertTrue(reader.readHeader(input, header))
        assertEquals(MsgType.VIDEO_FRAME, header.msgType)
        assertTrue((header.flags and TetherProtocol.FLAG_KEYFRAME) != 0)
        assertFalse((header.flags and TetherProtocol.FLAG_CODEC_CONFIG) != 0)

        reader.readVideoMeta(input, header.payloadLength, meta)
        assertEquals(99L, meta.frameSeq)
        assertEquals(555_000L, meta.ptsUs)
        assertEquals(nal.size, meta.nalLength)

        TetherProtocol.readExactly(input, reusableSlot, 0, meta.nalLength)
        assertArrayEquals(nal, reusableSlot.copyOfRange(0, meta.nalLength))
    }

    @Test(expected = ProtocolException::class)
    fun `rejects invalid magic bytes`() {
        val bad = byteArrayOf(0x00, 0x00, 0x01, 0x05, 0, 0, 0, 0, 0, 8)
        reader.readMessage(ByteArrayInputStream(bad))
    }

    @Test(expected = ProtocolException::class)
    fun `rejects unsupported protocol version`() {
        val bad = byteArrayOf(0x54, 0x48, 0x09, 0x05, 0, 0, 0, 0, 0, 8)
        reader.readMessage(ByteArrayInputStream(bad))
    }

    @Test(expected = ProtocolException::class)
    fun `rejects oversized payload length`() {
        val bad = byteArrayOf(0x54, 0x48, 0x01, 0x03, 0, 0, 0x7F, 0x00, 0x00, 0x00)
        reader.readMessage(ByteArrayInputStream(bad))
    }

    @Test
    fun `throws EOFException on truncated header`() {
        val partial = byteArrayOf(0x54, 0x48, 0x01)
        try {
            reader.readMessage(ByteArrayInputStream(partial))
            fail("Expected EOFException")
        } catch (_: EOFException) {
            // Expected
        }
    }
}
