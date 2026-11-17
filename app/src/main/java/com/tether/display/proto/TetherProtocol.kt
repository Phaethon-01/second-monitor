package com.tether.display.proto

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kotlin implementation of the Tether binary wire protocol.
 *
 * Frame layout (10-byte Big-Endian header + variable payload):
 * - [0..1] Magic: 'T' (0x54), 'H' (0x48)
 * - [2]    Version: 1
 * - [3]    MsgType: 1..9
 * - [4..5] Flags: unsigned 16-bit bitmask
 * - [6..9] Payload length: unsigned/non-negative 32-bit length (max 8 MiB)
 *
 * NOTE: Designed to match all messages & fields in the Tether Android PRD.
 * When docs/protocol-vectors.json from the Shared spec is added, update and verify
 * against those vectors in ProtocolTest.
 */
object TetherProtocol {
    const val DEFAULT_PORT = 27183
    const val PROTOCOL_VERSION: Byte = 1
    const val MAGIC_0: Byte = 0x54 // 'T'
    const val MAGIC_1: Byte = 0x48 // 'H'
    const val HEADER_SIZE = 10
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024 // 8 MiB safety cap

    // Header flags
    const val FLAG_NONE: Int = 0x0000
    const val FLAG_KEYFRAME: Int = 0x0001
    const val FLAG_CODEC_CONFIG: Int = 0x0002

    // Video frame metadata prefix size inside VIDEO_FRAME payload (frameSeq: Long + ptsUs: Long)
    const val VIDEO_FRAME_META_SIZE = 16
    // Touch payload fixed size (action: Byte, pointerId: Byte, x: Short, y: Short, reserved: Short)
    const val TOUCH_PAYLOAD_SIZE = 8
    const val PING_PONG_PAYLOAD_SIZE = 8
    const val CONFIG_PAYLOAD_SIZE = 16
    const val DISPLAY_CHANGE_PAYLOAD_SIZE = 16
    const val BYE_PAYLOAD_SIZE = 4
    const val HELLO_FIXED_SIZE = 26 // 6 ints (24B) + 1 short nameLen (2B)

    enum class MsgType(val code: Byte) {
        HELLO(0x01),
        CONFIG(0x02),
        VIDEO_FRAME(0x03),
        TOUCH(0x04),
        PING(0x05),
        PONG(0x06),
        REQUEST_KEYFRAME(0x07),
        DISPLAY_CHANGE(0x08),
        BYE(0x09);

        companion object {
            private val BY_CODE = values().associateBy { it.code }
            fun fromCode(code: Byte): MsgType? = BY_CODE[code]
        }
    }

    enum class TouchAction(val code: Byte) {
        DOWN(0x00),
        MOVE(0x01),
        UP(0x02),
        CANCEL(0x03);

        companion object {
            private val BY_CODE = values().associateBy { it.code }
            fun fromCode(code: Byte): TouchAction? = BY_CODE[code]
        }
    }

    sealed class Message {
        data class Hello(
            val displayWidth: Int,
            val displayHeight: Int,
            val densityDpi: Int,
            val maxDecodeWidth: Int,
            val maxDecodeHeight: Int,
            val maxFps: Int,
            val decoderName: String
        ) : Message()

        data class Config(
            val width: Int,
            val height: Int,
            val fps: Int,
            val codecFlags: Int = 0
        ) : Message()

        data class VideoFrame(
            val frameSeq: Long,
            val ptsUs: Long,
            val isKeyFrame: Boolean,
            val isCodecConfig: Boolean,
            val nalData: ByteArray,
            val nalLength: Int = nalData.size
        ) : Message() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is VideoFrame) return false
                return frameSeq == other.frameSeq &&
                    ptsUs == other.ptsUs &&
                    isKeyFrame == other.isKeyFrame &&
                    isCodecConfig == other.isCodecConfig &&
                    nalLength == other.nalLength &&
                    nalData.copyOfRange(0, nalLength).contentEquals(
                        other.nalData.copyOfRange(0, other.nalLength)
                    )
            }

            override fun hashCode(): Int {
                var result = frameSeq.hashCode()
                result = 31 * result + ptsUs.hashCode()
                result = 31 * result + isKeyFrame.hashCode()
                result = 31 * result + isCodecConfig.hashCode()
                result = 31 * result + nalLength
                return result
            }
        }

        data class Touch(
            val action: TouchAction,
            val pointerId: Int,
            val x: Int, // 0..65535
            val y: Int  // 0..65535
        ) : Message()

        data class Ping(val timestampNs: Long) : Message()
        data class Pong(val timestampNs: Long) : Message()
        data object RequestKeyframe : Message()

        data class DisplayChange(
            val width: Int,
            val height: Int,
            val densityDpi: Int,
            val rotationDegrees: Int
        ) : Message()

        data class Bye(val reasonCode: Int = 0) : Message()
    }

    class ProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

    data class PacketHeader(
        var msgType: MsgType = MsgType.PING,
        var flags: Int = 0,
        var payloadLength: Int = 0
    )

    /**
     * Reusable zero-allocation packet writer for hot-path messages (TOUCH, PING, REQUEST_KEYFRAME)
     * and control messages. Not thread-safe; call from a single writer thread/coroutine.
     */
    class PacketWriter {
        private val scratch = ByteArray(1024)
        private val scratchBuf: ByteBuffer = ByteBuffer.wrap(scratch).order(ByteOrder.BIG_ENDIAN)

        @Throws(IOException::class)
        fun writeHeader(
            out: OutputStream,
            type: MsgType,
            flags: Int,
            payloadLength: Int
        ) {
            if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
                throw ProtocolException("Invalid payload length: $payloadLength")
            }
            scratchBuf.clear()
            writeHeaderToBuffer(scratchBuf, type, flags, payloadLength)
            out.write(scratch, 0, HEADER_SIZE)
        }

        @Throws(IOException::class)
        fun writeHello(out: OutputStream, msg: Message.Hello) {
            val nameBytes = msg.decoderName.toByteArray(Charsets.UTF_8)
            val truncatedLen = minOf(nameBytes.size, 256)
            val payloadLen = HELLO_FIXED_SIZE + truncatedLen
            val totalLen = HEADER_SIZE + payloadLen
            val buf = if (totalLen <= scratch.size) {
                scratchBuf.clear()
                scratchBuf
            } else {
                ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN)
            }
            writeHeaderToBuffer(buf, MsgType.HELLO, FLAG_NONE, payloadLen)
            buf.putInt(msg.displayWidth)
            buf.putInt(msg.displayHeight)
            buf.putInt(msg.densityDpi)
            buf.putInt(msg.maxDecodeWidth)
            buf.putInt(msg.maxDecodeHeight)
            buf.putInt(msg.maxFps)
            buf.putShort(truncatedLen.toShort())
            buf.put(nameBytes, 0, truncatedLen)
            out.write(buf.array(), 0, totalLen)
            out.flush()
        }

        @Throws(IOException::class)
        fun writeConfig(out: OutputStream, msg: Message.Config) {
            scratchBuf.clear()
            writeHeaderToBuffer(scratchBuf, MsgType.CONFIG, FLAG_NONE, CONFIG_PAYLOAD_SIZE)
            scratchBuf.putInt(msg.width)
            scratchBuf.putInt(msg.height)
            scratchBuf.putInt(msg.fps)
            scratchBuf.putInt(msg.codecFlags)
            out.write(scratch, 0, HEADER_SIZE + CONFIG_PAYLOAD_SIZE)
            out.flush()
        }

        @Throws(IOException::class)
        fun writeVideoFrame(out: OutputStream, msg: Message.VideoFrame) {
            val payloadLen = VIDEO_FRAME_META_SIZE + msg.nalLength
            if (payloadLen > MAX_PAYLOAD_BYTES) {
                throw ProtocolException("Video frame exceeds max payload: $payloadLen")
            }
            var flags = FLAG_NONE
            if (msg.isKeyFrame) flags = flags or FLAG_KEYFRAME
            if (msg.isCodecConfig) flags = flags or FLAG_CODEC_CONFIG

            scratchBuf.clear()
            writeHeaderToBuffer(scratchBuf, MsgType.VIDEO_FRAME, flags, payloadLen)
            scratchBuf.putLong(msg.frameSeq)
            scratchBuf.putLong(msg.ptsUs)
            out.write(scratch, 0, HEADER_SIZE + VIDEO_FRAME_META_SIZE)
            if (msg.nalLength > 0) {
                out.write(msg.nalData, 0, msg.nalLength)
            }
            out.flush()
        }

        /** Zero-allocation hot-path touch writer. */
        @Throws(IOException::class)
        fun writeTouch(
            out: OutputStream,
            action: TouchAction,
            pointerId: Int,
            x: Int,
            y: Int,
            flush: Boolean = true
        ) {
            val clampedX = x.coerceIn(0, 65535)
            val clampedY = y.coerceIn(0, 65535)
            scratchBuf.clear()
            writeHeaderToBuffer(scratchBuf, MsgType.TOUCH, FLAG_NONE, TOUCH_PAYLOAD_SIZE)
            scratchBuf.put(action.code)
            scratchBuf.put(pointerId.toByte())
            scratchBuf.putShort(clampedX.toShort())
            scratchBuf.putShort(clampedY.toShort())
            scratchBuf.putShort(0)
            out.write(scratch, 0, HEADER_SIZE + TOUCH_PAYLOAD_SIZE)
            if (flush) out.flush()
        }

        @Throws(IOException::class)
        fun writePing(out: OutputStream, timestampNs: Long) {
            scratchBuf.clear()
            writeHeaderToBuffer(scratchBuf, MsgType.PING, FLAG_NONE, PING_PONG_PAYLOAD_SIZE)
            scratchBuf.putLong(timestampNs)
            out.write(scratch, 0, HEADER_SIZE + PING_PONG_PAYLOAD_SIZE)
            out.flush()
        }

        @Throws(IOException::class)
        fun writePong(out: OutputStream, timestampNs: Long) {
            scratchBuf.clear()
            writeHeaderToBuffer(scratchBuf, MsgType.PONG, FLAG_NONE, PING_PONG_PAYLOAD_SIZE)
            scratchBuf.putLong(timestampNs)
            out.write(scratch, 0, HEADER_SIZE + PING_PONG_PAYLOAD_SIZE)
            out.flush()
        }

        @Throws(IOException::class)
        fun writeRequestKeyframe(out: OutputStream) {
            scratchBuf.clear()
            writeHeaderToBuffer(scratchBuf, MsgType.REQUEST_KEYFRAME, FLAG_NONE, 0)
            out.write(scratch, 0, HEADER_SIZE)
            out.flush()
        }

        @Throws(IOException::class)
        fun writeDisplayChange(out: OutputStream, msg: Message.DisplayChange) {
            scratchBuf.clear()
            writeHeaderToBuffer(scratchBuf, MsgType.DISPLAY_CHANGE, FLAG_NONE, DISPLAY_CHANGE_PAYLOAD_SIZE)
            scratchBuf.putInt(msg.width)
            scratchBuf.putInt(msg.height)
            scratchBuf.putInt(msg.densityDpi)
            scratchBuf.putInt(msg.rotationDegrees)
            out.write(scratch, 0, HEADER_SIZE + DISPLAY_CHANGE_PAYLOAD_SIZE)
            out.flush()
        }

        @Throws(IOException::class)
        fun writeBye(out: OutputStream, reasonCode: Int = 0) {
            scratchBuf.clear()
            writeHeaderToBuffer(scratchBuf, MsgType.BYE, FLAG_NONE, BYE_PAYLOAD_SIZE)
            scratchBuf.putInt(reasonCode)
            out.write(scratch, 0, HEADER_SIZE + BYE_PAYLOAD_SIZE)
            out.flush()
        }

        @Throws(IOException::class)
        fun writeMessage(out: OutputStream, message: Message) {
            when (message) {
                is Message.Hello -> writeHello(out, message)
                is Message.Config -> writeConfig(out, message)
                is Message.VideoFrame -> writeVideoFrame(out, message)
                is Message.Touch -> writeTouch(out, message.action, message.pointerId, message.x, message.y)
                is Message.Ping -> writePing(out, message.timestampNs)
                is Message.Pong -> writePong(out, message.timestampNs)
                is Message.RequestKeyframe -> writeRequestKeyframe(out)
                is Message.DisplayChange -> writeDisplayChange(out, message)
                is Message.Bye -> writeBye(out, message.reasonCode)
            }
        }

        private fun writeHeaderToBuffer(
            buf: ByteBuffer,
            type: MsgType,
            flags: Int,
            payloadLength: Int
        ) {
            buf.put(MAGIC_0)
            buf.put(MAGIC_1)
            buf.put(PROTOCOL_VERSION)
            buf.put(type.code)
            buf.putShort(flags.toShort())
            buf.putInt(payloadLength)
        }
    }

    /**
     * Reusable zero-allocation packet reader.
     * Supports reading VIDEO_FRAME payloads directly into caller-provided pre-allocated buffers.
     */
    class PacketReader {
        private val headerBytes = ByteArray(HEADER_SIZE)
        private val headerBuf = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        private val controlBytes = ByteArray(1024)
        private val controlBuf = ByteBuffer.wrap(controlBytes).order(ByteOrder.BIG_ENDIAN)

        @Throws(IOException::class)
        fun readHeader(input: InputStream, outHeader: PacketHeader): Boolean {
            val read = readExactlyOrEof(input, headerBytes, 0, HEADER_SIZE)
            if (!read) return false

            if (headerBytes[0] != MAGIC_0 || headerBytes[1] != MAGIC_1) {
                throw ProtocolException(
                    "Invalid magic: 0x${(headerBytes[0].toInt() and 0xFF).toString(16)} " +
                        "0x${(headerBytes[1].toInt() and 0xFF).toString(16)}"
                )
            }
            val version = headerBytes[2]
            if (version != PROTOCOL_VERSION) {
                throw ProtocolException("Unsupported protocol version: $version")
            }
            val type = MsgType.fromCode(headerBytes[3])
                ?: throw ProtocolException("Unknown message type: 0x${(headerBytes[3].toInt() and 0xFF).toString(16)}")

            headerBuf.position(4)
            val flags = headerBuf.short.toInt() and 0xFFFF
            val payloadLen = headerBuf.int
            if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES) {
                throw ProtocolException("Invalid payload length: $payloadLen")
            }

            outHeader.msgType = type
            outHeader.flags = flags
            outHeader.payloadLength = payloadLen
            return true
        }

        /**
         * Reads the 16-byte VIDEO_FRAME metadata header, returning frameSeq and ptsUs,
         * and returns the remaining NAL byte count. Caller then reads nalLength bytes
         * directly into a reusable slot buffer via [readExactly].
         */
        @Throws(IOException::class)
        fun readVideoMeta(
            input: InputStream,
            payloadLength: Int,
            outMeta: VideoFrameMeta
        ) {
            if (payloadLength < VIDEO_FRAME_META_SIZE) {
                throw ProtocolException("VIDEO_FRAME payload too small: $payloadLength")
            }
            readExactly(input, controlBytes, 0, VIDEO_FRAME_META_SIZE)
            controlBuf.position(0)
            outMeta.frameSeq = controlBuf.long
            outMeta.ptsUs = controlBuf.long
            outMeta.nalLength = payloadLength - VIDEO_FRAME_META_SIZE
        }

        /** Reads a complete message into a newly allocated object (convenience / tests / control msgs). */
        @Throws(IOException::class)
        fun readMessage(input: InputStream): Message? {
            val header = PacketHeader()
            if (!readHeader(input, header)) return null
            val len = header.payloadLength
            return when (header.msgType) {
                MsgType.HELLO -> {
                    if (len < HELLO_FIXED_SIZE) {
                        throw ProtocolException("HELLO payload too small: $len")
                    }
                    val buf = ensureAndReadControl(input, len)
                    val dw = buf.int
                    val dh = buf.int
                    val dpi = buf.int
                    val mw = buf.int
                    val mh = buf.int
                    val fps = buf.int
                    val nameLen = buf.short.toInt() and 0xFFFF
                    if (HELLO_FIXED_SIZE + nameLen != len) {
                        throw ProtocolException("HELLO nameLen mismatch: expected ${HELLO_FIXED_SIZE + nameLen}, got $len")
                    }
                    val nameBytes = ByteArray(nameLen)
                    buf.get(nameBytes)
                    Message.Hello(dw, dh, dpi, mw, mh, fps, String(nameBytes, Charsets.UTF_8))
                }

                MsgType.CONFIG -> {
                    if (len != CONFIG_PAYLOAD_SIZE) {
                        throw ProtocolException("Invalid CONFIG length: $len")
                    }
                    val buf = ensureAndReadControl(input, len)
                    Message.Config(
                        width = buf.int,
                        height = buf.int,
                        fps = buf.int,
                        codecFlags = buf.int
                    )
                }

                MsgType.VIDEO_FRAME -> {
                    val meta = VideoFrameMeta()
                    readVideoMeta(input, len, meta)
                    val nal = ByteArray(meta.nalLength)
                    readExactly(input, nal, 0, meta.nalLength)
                    Message.VideoFrame(
                        frameSeq = meta.frameSeq,
                        ptsUs = meta.ptsUs,
                        isKeyFrame = (header.flags and FLAG_KEYFRAME) != 0,
                        isCodecConfig = (header.flags and FLAG_CODEC_CONFIG) != 0,
                        nalData = nal,
                        nalLength = meta.nalLength
                    )
                }

                MsgType.TOUCH -> {
                    if (len != TOUCH_PAYLOAD_SIZE) {
                        throw ProtocolException("Invalid TOUCH length: $len")
                    }
                    val buf = ensureAndReadControl(input, len)
                    val actionByte = buf.get()
                    val action = TouchAction.fromCode(actionByte)
                        ?: throw ProtocolException("Invalid touch action: $actionByte")
                    val pointerId = buf.get().toInt() and 0xFF
                    val x = buf.short.toInt() and 0xFFFF
                    val y = buf.short.toInt() and 0xFFFF
                    Message.Touch(action, pointerId, x, y)
                }

                MsgType.PING -> {
                    if (len != PING_PONG_PAYLOAD_SIZE) {
                        throw ProtocolException("Invalid PING length: $len")
                    }
                    val buf = ensureAndReadControl(input, len)
                    Message.Ping(buf.long)
                }

                MsgType.PONG -> {
                    if (len != PING_PONG_PAYLOAD_SIZE) {
                        throw ProtocolException("Invalid PONG length: $len")
                    }
                    val buf = ensureAndReadControl(input, len)
                    Message.Pong(buf.long)
                }

                MsgType.REQUEST_KEYFRAME -> {
                    if (len != 0) {
                        throw ProtocolException("Invalid REQUEST_KEYFRAME length: $len")
                    }
                    Message.RequestKeyframe
                }

                MsgType.DISPLAY_CHANGE -> {
                    if (len != DISPLAY_CHANGE_PAYLOAD_SIZE) {
                        throw ProtocolException("Invalid DISPLAY_CHANGE length: $len")
                    }
                    val buf = ensureAndReadControl(input, len)
                    Message.DisplayChange(
                        width = buf.int,
                        height = buf.int,
                        densityDpi = buf.int,
                        rotationDegrees = buf.int
                    )
                }

                MsgType.BYE -> {
                    if (len != BYE_PAYLOAD_SIZE) {
                        throw ProtocolException("Invalid BYE length: $len")
                    }
                    val buf = ensureAndReadControl(input, len)
                    Message.Bye(buf.int)
                }
            }
        }

        /** Reads payload bytes for control messages cleanly without allocation when <= 1024 B. */
        @Throws(IOException::class)
        fun readControlBuffer(input: InputStream, length: Int): ByteBuffer {
            return ensureAndReadControl(input, length)
        }

        private fun ensureAndReadControl(input: InputStream, length: Int): ByteBuffer {
            if (length <= controlBytes.size) {
                readExactly(input, controlBytes, 0, length)
                controlBuf.position(0)
                controlBuf.limit(length)
                return controlBuf
            }
            val temp = ByteArray(length)
            readExactly(input, temp, 0, length)
            return ByteBuffer.wrap(temp).order(ByteOrder.BIG_ENDIAN)
        }

        @Throws(IOException::class)
        fun skipFully(input: InputStream, count: Int) {
            var remaining = count
            while (remaining > 0) {
                val chunk = minOf(remaining, controlBytes.size)
                readExactly(input, controlBytes, 0, chunk)
                remaining -= chunk
            }
        }
    }

    class VideoFrameMeta(
        var frameSeq: Long = 0L,
        var ptsUs: Long = 0L,
        var nalLength: Int = 0
    )

    @Throws(IOException::class)
    fun readExactly(input: InputStream, dest: ByteArray, offset: Int, length: Int) {
        var total = 0
        while (total < length) {
            val n = input.read(dest, offset + total, length - total)
            if (n < 0) throw EOFException("Unexpected EOF after $total of $length bytes")
            total += n
        }
    }

    @Throws(IOException::class)
    private fun readExactlyOrEof(input: InputStream, dest: ByteArray, offset: Int, length: Int): Boolean {
        val first = input.read(dest, offset, length)
        if (first < 0) return false
        var total = first
        while (total < length) {
            val n = input.read(dest, offset + total, length - total)
            if (n < 0) throw EOFException("Truncated packet header after $total of $length bytes")
            total += n
        }
        return true
    }
}
