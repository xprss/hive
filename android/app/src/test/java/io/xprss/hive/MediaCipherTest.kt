package io.xprss.hive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.spec.SecretKeySpec

class MediaCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private fun seal(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also {
        MediaCipher.encrypt(ByteArrayInputStream(bytes), it, "capture-1", key)
    }.toByteArray()

    private fun open(bytes: ByteArray, id: String = "capture-1"): ByteArray =
        ByteArrayOutputStream().also {
            MediaCipher.decrypt(ByteArrayInputStream(bytes), it, id, key)
        }.toByteArray()

    @Test fun roundTripAcrossChunkBoundary() {
        val source = ByteArray(1024 * 1024 + 1234) { (it * 31).toByte() }
        assertArrayEquals(source, open(seal(source)))
        assertArrayEquals(byteArrayOf(), open(seal(byteArrayOf())))
    }

    @Test fun alteredOrWrongCaptureIsRejected() {
        val sealed = seal("private photo".toByteArray())
        sealed[30] = (sealed[30].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { open(sealed) }
        assertThrows(Exception::class.java) { open(seal("photo".toByteArray()), "capture-2") }
    }

    @Test fun truncatedOrAppendedFileIsRejected() {
        val sealed = seal("video".toByteArray())
        assertThrows(Exception::class.java) { open(sealed.copyOf(sealed.size - 1)) }
        assertThrows(Exception::class.java) { open(sealed + byteArrayOf(1)) }
    }
}
