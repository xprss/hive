package io.xprss.hive

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class MediaItem(
    val id: String,
    val kind: String,
    var phase: String = "CAPTURING",
    var bytes: Long = 0
)

internal data class Session(
    val id: String,
    val started: Long,
    var phase: String,
    val wrappedKey: String,
    val items: MutableList<MediaItem>
)

/** No public media is written until publish(). All capture paths live under noBackupFilesDir. */
internal class Vault(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    private val journal = AtomicFile(File(root, "session.json"))
    private val random = SecureRandom()
    private var session: Session? = readSession()
    @Volatile private var unlockedKey: SecretKey? = null

    val hasSession: Boolean get() = synchronized(this) { session != null }
    val isPublishing: Boolean get() = synchronized(this) { session?.phase == "PUBLISHING" }
    val isUnlocked: Boolean get() = unlockedKey != null

    fun ensureKeystoreKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    30,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                ).build()
        )
        generator.generateKey()
    }

    private fun wrappingKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ks.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("Chiave Android Keystore non disponibile")
    }

    @Synchronized fun start() {
        check(session == null) { "Esiste già una sessione" }
        ensureKeystoreKey()
        val raw = ByteArray(32).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val wrapped = cipher.iv + cipher.doFinal(raw)
        val key = SecretKeySpec(raw, "AES")
        raw.fill(0)
        val s = Session(UUID.randomUUID().toString(), System.currentTimeMillis(), "ACTIVE",
            Base64.encodeToString(wrapped, Base64.NO_WRAP), mutableListOf())
        persist(s)
        session = s
        unlockedKey = key
    }

    @Synchronized fun unlock() {
        val s = session ?: error("Nessuna sessione")
        ensureKeystoreKey()
        val wrapped = Base64.decode(s.wrappedKey, Base64.DEFAULT)
        require(wrapped.size > 28) { "Chiave di sessione danneggiata" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
        val raw = cipher.doFinal(wrapped, 12, wrapped.size - 12)
        unlockedKey = SecretKeySpec(raw, "AES")
        raw.fill(0)
        recoverCaptures()
    }

    fun lock() { unlockedKey = null }

    @Synchronized fun status(): JSONObject {
        val s = session ?: return JSONObject().put("state", "idle")
        val photoCount = s.items.count { it.kind == "photo" }
        val videoCount = s.items.count { it.kind == "video" }
        return JSONObject().put("state", s.phase.lowercase()).put("started", s.started)
            .put("photos", photoCount).put("videos", videoCount)
            .put("bytes", s.items.sumOf {
                rawFile(it.id).length() + encryptedFile(it.id).length()
            })
    }

    @Synchronized fun itemsForView(): List<MediaItem> {
        check(unlockedKey != null) { "Sbloccare la sessione" }
        return session?.items?.filter { it.phase == "READY" }?.map { it.copy() } ?: emptyList()
    }

    @Synchronized fun beginCapture(kind: String): Pair<MediaItem, File> {
        val s = session ?: error("Avviare prima una sessione")
        check(s.phase == "ACTIVE") { "Pubblicazione in corso" }
        check(unlockedKey != null) { "Sbloccare la sessione" }
        require(kind == "photo" || kind == "video")
        val item = MediaItem(UUID.randomUUID().toString(), kind)
        s.items.add(item)
        persist(s)
        return item.copy() to rawFile(item.id)
    }

    @Synchronized fun finishCapture(id: String) {
        val s = session ?: error("Nessuna sessione")
        val item = s.items.single { it.id == id }
        check(item.phase == "CAPTURING")
        encryptCapture(item)
        item.phase = "READY"
        item.bytes = encryptedFile(id).length()
        persist(s)
    }

    @Synchronized fun discardCapture(id: String) {
        val s = session ?: return
        val item = s.items.find { it.id == id } ?: return
        rawFile(id).delete()
        encryptedFile(id).delete()
        s.items.remove(item)
        persist(s)
    }

    @Synchronized private fun recoverCaptures() {
        val s = session ?: return
        var changed = false
        for (item in s.items.toList()) {
            if (item.phase != "CAPTURING") continue
            when {
                encryptedFile(item.id).exists() -> {
                    item.phase = "READY"
                    item.bytes = encryptedFile(item.id).length()
                    rawFile(item.id).delete()
                }
                rawFile(item.id).exists() && rawFile(item.id).length() > 0L -> {
                    encryptCapture(item)
                    item.phase = "READY"
                    item.bytes = encryptedFile(item.id).length()
                }
                else -> s.items.remove(item)
            }
            changed = true
        }
        if (changed) persist(s)
        root.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
    }

    private fun encryptCapture(item: MediaItem) {
        val raw = rawFile(item.id)
        require(raw.exists() && raw.length() > 0) { "File di cattura mancante" }
        val key = unlockedKey ?: error("Sessione bloccata")
        val part = File(root, "${item.id}.part")
        try {
            FileInputStream(raw).use { input ->
                FileOutputStream(part).use { output ->
                    MediaCipher.encrypt(input, output, item.id, key, random)
                    output.fd.sync()
                }
            }
            check(part.renameTo(encryptedFile(item.id))) { "Impossibile salvare il file cifrato" }
            check(raw.delete()) { "Impossibile rimuovere il file temporaneo" }
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    fun decrypt(item: MediaItem, output: OutputStream) {
        val key = unlockedKey ?: error("Sessione bloccata")
        FileInputStream(encryptedFile(item.id)).use { input ->
            MediaCipher.decrypt(input, output, item.id, key)
        }
    }

    @Synchronized fun beginPublishing() {
        val current = session ?: error("Nessuna sessione")
        check(unlockedKey != null) { "Sessione bloccata" }
        check(current.items.all { it.phase == "READY" || it.phase == "PUBLISHED" }) {
            "Cattura incompleta: riprova dopo avere liberato spazio"
        }
        if (current.phase != "PUBLISHING") {
            current.phase = "PUBLISHING"
            persist(current)
        }
    }

    /** Resumable: deterministic display name reconciles a row inserted before journal commit. */
    fun publish() {
        beginPublishing()
        val s = synchronized(this) { session ?: error("Nessuna sessione") }
        for (item in s.items.toList()) {
            if (item.phase != "PUBLISHED") {
                check(item.phase == "READY") { "Cattura ancora in corso" }
                publishOne(s, item)
                synchronized(this) {
                    item.phase = "PUBLISHED"
                    persist(s)
                }
            }
            val encrypted = encryptedFile(item.id)
            check(!encrypted.exists() || encrypted.delete()) { "Impossibile pulire l'archivio privato" }
        }
        synchronized(this) {
            journal.delete()
            session = null
            unlockedKey = null
        }
    }

    private fun publishOne(s: Session, item: MediaItem) {
        val image = item.kind == "photo"
        val collection = if (image) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = "hive_${s.id}_${item.id}" + if (image) ".jpg" else ".mp4"
        val path = if (image) "Pictures/Hive/" else "Movies/Hive/"
        val args = Bundle().apply {
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
            )
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(name, path, context.packageName))
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        var uri: android.net.Uri? = null
        var pending = true
        context.contentResolver.query(collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING), args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                uri = ContentUris.withAppendedId(collection, cursor.getLong(0))
                pending = cursor.getInt(1) != 0
            }
        }
        if (uri == null) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (image) "image/jpeg" else "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                put(MediaStore.MediaColumns.DATE_TAKEN, s.started)
            }
            uri = context.contentResolver.insert(collection, values)
                ?: error("Impossibile creare elemento MediaStore")
        }
        if (!pending) return
        val target = uri ?: error("Elemento MediaStore mancante")
        context.contentResolver.openOutputStream(target, "wt")?.use { decrypt(item, it) }
            ?: error("Impossibile scrivere elemento MediaStore")
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        check(context.contentResolver.update(target, done, null, null) == 1) {
            "Impossibile rendere visibile l'elemento"
        }
    }

    private fun rawFile(id: String) = File(root, "$id.raw")
    private fun encryptedFile(id: String) = File(root, "$id.enc")

    private fun readSession(): Session? {
        val data = try {
            JSONObject(journal.openRead().bufferedReader().use { it.readText() })
        } catch (_: java.io.FileNotFoundException) { return null }
        val array = data.getJSONArray("items")
        val items = mutableListOf<MediaItem>()
        for (i in 0 until array.length()) {
            val v = array.getJSONObject(i)
            items.add(MediaItem(v.getString("id"), v.getString("kind"), v.getString("phase"), v.getLong("bytes")))
        }
        return Session(data.getString("id"), data.getLong("started"), data.getString("phase"),
            data.getString("wrappedKey"), items)
    }

    private fun persist(s: Session) {
        val data = JSONObject().put("id", s.id).put("started", s.started)
            .put("phase", s.phase).put("wrappedKey", s.wrappedKey)
        val items = JSONArray()
        for (item in s.items) {
            items.put(JSONObject().put("id", item.id).put("kind", item.kind)
                .put("phase", item.phase).put("bytes", item.bytes))
        }
        data.put("items", items)
        val stream = journal.startWrite()
        try {
            stream.write(data.toString().toByteArray(Charsets.UTF_8))
            journal.finishWrite(stream)
        } catch (e: Exception) {
            journal.failWrite(stream)
            throw e
        }
    }

    companion object {
        private const val KEY_ALIAS = "hive.session.wrapper.v1"
    }
}
