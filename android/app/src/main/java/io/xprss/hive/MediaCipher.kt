package io.xprss.hive

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A bounded-memory, authenticated stream of independent AES-GCM records. */
internal object MediaCipher {
    private const val CHUNK_SIZE = 1024 * 1024
    private val MAGIC = byteArrayOf(0x48, 0x56, 0x43, 0x32)

    fun encrypt(input: InputStream, output: OutputStream, id: String, key: SecretKey,
                random: SecureRandom = SecureRandom()) {
        val data = DataOutputStream(output)
        data.write(MAGIC)
        val buffer = ByteArray(CHUNK_SIZE)
        var index = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            writeRecord(data, key, id, index++, buffer, count, random)
        }
        writeRecord(data, key, id, index, ByteArray(0), 0, random)
        data.flush()
    }

    fun decrypt(input: InputStream, output: OutputStream, id: String, key: SecretKey) {
        val data = DataInputStream(input)
        val header = ByteArray(4)
        data.readFully(header)
        require(header.contentEquals(MAGIC)) { "Formato archivio non valido" }
        var index = 0L
        while (true) {
            val count = data.readInt()
            require(count in 0..CHUNK_SIZE) { "Blocco cifrato non valido" }
            val iv = ByteArray(12)
            data.readFully(iv)
            val ciphertext = ByteArray(count + 16)
            data.readFully(ciphertext)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(recordAad(id, index++, count))
            val plaintext = cipher.doFinal(ciphertext)
            check(plaintext.size == count) { "Blocco cifrato danneggiato" }
            if (count == 0) {
                check(data.read() == -1) { "Dati dopo la fine del file" }
                break
            }
            output.write(plaintext)
        }
        output.flush()
    }

    private fun writeRecord(output: DataOutputStream, key: SecretKey, id: String,
                            index: Long, buffer: ByteArray, count: Int, random: SecureRandom) {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(recordAad(id, index, count))
        output.writeInt(count)
        output.write(iv)
        output.write(cipher.doFinal(buffer, 0, count))
    }

    private fun recordAad(id: String, index: Long, count: Int): ByteArray =
        id.toByteArray(Charsets.UTF_8) + ByteBuffer.allocate(12).putLong(index).putInt(count).array()
}
