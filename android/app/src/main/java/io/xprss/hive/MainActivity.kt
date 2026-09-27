package io.xprss.hive

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.VideoView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : FragmentActivity() {
    internal lateinit var vault: Vault
    private var server: CommandServer? = null
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private var screen = "home"
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraMode = "photo"
    private var recording: Recording? = null
    private var recordingId: String? = null
    private var videoBusy = false
    private var pendingPhotos = 0
    private var cameraMessage: TextView? = null
    private var galleryTemp: File? = null
    private var authBusy = false
    private var publishingTask = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        vault = Vault(this)
        File(cacheDir, "private-preview.mp4").delete()
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 20)
        }
        setContentView(root)
        showHome()
        try { server = CommandServer(this) }
        catch (e: Exception) { toast("Socket non disponibile: ${e.message}") }
    }

    override fun onDestroy() {
        server?.close()
        recording?.stop()
        cameraProvider?.unbindAll()
        clearGalleryTemp()
        io.shutdown()
        super.onDestroy()
    }

    override fun onStop() {
        if (!authBusy && !videoBusy && pendingPhotos == 0 && !vault.isPublishing) {
            vault.lock()
            if (screen != "home") showHome()
        }
        super.onStop()
    }

    override fun onBackPressed() {
        if ((videoBusy || pendingPhotos > 0) && screen == "camera") toast("Attendi la fine della cattura")
        else if (screen == "home") super.onBackPressed() else showHome()
    }

    private fun label(text: String, size: Float = 18f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, 8, 0, 16)
    }

    private fun button(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun error(e: Throwable): String = e.message ?: e.javaClass.simpleName

    private fun lockAfterBackgroundCapture() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            !videoBusy && pendingPhotos == 0 && !vault.isPublishing) {
            vault.lock()
            showHome()
        }
    }

    private fun showHome() {
        if (screen == "camera") {
            cameraProvider?.unbindAll()
            imageCapture = null
            videoCapture = null
        }
        screen = "home"
        clearGalleryTemp()
        root.removeAllViews()
        root.addView(label("Hive · fotocamera privata", 25f))
        val status = vault.status()
        if (status.getString("state") == "idle") {
            root.addView(label("Nessuna sessione attiva"))
            root.addView(button("Avvia sessione") { requestStart { toast(it) } })
        } else {
            val state = status.getString("state")
            val date = DateFormat.getDateTimeInstance().format(Date(status.getLong("started")))
            root.addView(label("$state · dal $date\n${status.getInt("photos")} foto · ${status.getInt("videos")} video · ${status.getLong("bytes")} byte"))
            if (state == "active") {
                root.addView(button("Fotocamera") { requestCamera { if (it.startsWith("ERR")) toast(it) } })
                root.addView(button("Galleria privata") { requestView { if (it.startsWith("ERR")) toast(it) } })
            }
            root.addView(button(if (state == "publishing") "Riprendi pubblicazione" else "Chiudi e pubblica") {
                requestEnd { toast(it) }
            })
        }
        root.addView(button("Autorizza Termux") { requestPair() })
        root.addView(label("Scatti e video fatti in questa app restano privati fino alla chiusura della sessione. La fotocamera di altre app non è controllata.", 14f))
    }

    internal fun handleCommand(command: String, reply: (String) -> Unit) {
        when (command) {
            "start" -> requestStart(reply)
            "end" -> requestEnd(reply)
            "camera" -> requestCamera(reply)
            "view" -> requestView(reply)
            else -> reply("ERR Comando sconosciuto")
        }
    }

    private fun authenticate(title: String, action: () -> Unit, reply: (String) -> Unit) {
        if (authBusy) { reply("ERR Autenticazione già in corso"); return }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            reply("ERR Configura un blocco schermo sicuro")
            return
        }
        try { vault.ensureKeystoreKey() }
        catch (e: Exception) { reply("ERR ${error(e)}"); return }
        authBusy = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authBusy = false
                    try { action() } catch (e: Exception) { reply("ERR ${error(e)}"); showHome() }
                }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    authBusy = false
                    reply("ERR Autenticazione annullata: $message")
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title)
            .setAllowedAuthenticators(authenticators).build()
        try { prompt.authenticate(info) }
        catch (e: Exception) { authBusy = false; reply("ERR ${error(e)}") }
    }

    private fun requestStart(reply: (String) -> Unit) {
        if (vault.hasSession) { reply("ERR Esiste già una sessione"); return }
        val proceed = {
            authenticate("Avvia sessione privata", {
                vault.start()
                showHome()
                reply("OK Sessione avviata")
            }, reply)
        }
        val prefs = getSharedPreferences("local", MODE_PRIVATE)
        if (!prefs.getBoolean("riskAccepted", false)) {
            AlertDialog.Builder(this).setTitle("Prima sessione")
                .setMessage("Nessun backup: disinstallazione, guasto o invalidazione della chiave prima della pubblicazione possono rendere gli scatti irrecuperabili. Procedere?")
                .setPositiveButton("Ho capito") { _, _ ->
                    prefs.edit().putBoolean("riskAccepted", true).apply()
                    proceed()
                }.setNegativeButton("Annulla") { _, _ -> reply("ERR Avvio annullato") }
                .setOnCancelListener { reply("ERR Avvio annullato") }.show()
        } else proceed()
    }

    private fun requestEnd(reply: (String) -> Unit) {
        if (!vault.hasSession) { reply("ERR Nessuna sessione"); return }
        if (publishingTask) { reply("ERR Pubblicazione già in corso"); return }
        if (videoBusy || pendingPhotos > 0) { reply("ERR Attendi la fine della cattura"); return }
        authenticate("Pubblica foto e video", {
            vault.unlock()
            vault.beginPublishing()
            publishingTask = true
            showHome()
            io.execute {
                val result = try { vault.publish(); "OK Pubblicazione completata" }
                catch (e: Exception) { "ERR Pubblicazione interrotta: ${error(e)}" }
                runOnUiThread { publishingTask = false; showHome(); reply(result) }
            }
        }, reply)
    }

    private fun requestCamera(reply: (String) -> Unit) {
        if (!vault.hasSession || vault.isPublishing) { reply("ERR Nessuna sessione attiva"); return }
        if (videoBusy || pendingPhotos > 0) { reply("ERR Cattura in corso"); return }
        authenticate("Apri fotocamera privata", {
            vault.unlock()
            showCamera()
            reply("OK Fotocamera aperta")
        }, reply)
    }

    private fun requestView(reply: (String) -> Unit) {
        if (!vault.hasSession || vault.isPublishing) { reply("ERR Nessuna sessione attiva"); return }
        if (videoBusy || pendingPhotos > 0) { reply("ERR Cattura in corso"); return }
        authenticate("Apri galleria privata", {
            vault.unlock()
            showGallery()
            reply("OK Galleria privata aperta")
        }, reply)
    }

    private fun showCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        screen = "camera"
        cameraMode = "photo"
        root.removeAllViews()
        val previewView = PreviewView(this)
        root.addView(previewView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        cameraMessage = label("Pronto", 15f)
        root.addView(cameraMessage)
        root.addView(button("Modalità foto") {
            if (!videoBusy && pendingPhotos == 0) bindCamera(previewView, "photo")
        })
        root.addView(button("Modalità video") {
            if (!videoBusy && pendingPhotos == 0) bindCamera(previewView, "video")
        })
        root.addView(button("Scatta foto") {
            if (cameraMode == "photo") takePhoto() else toast("Seleziona modalità foto")
        })
        root.addView(button("Avvia / ferma video") {
            if (cameraMode == "video") toggleVideo() else toast("Seleziona modalità video")
        })
        root.addView(button("Indietro") {
            if (!videoBusy && pendingPhotos == 0) showHome() else toast("Attendi la fine della cattura")
        })
        bindCamera(previewView, "photo")
    }

    private fun bindCamera(previewView: PreviewView, mode: String) {
        cameraMode = mode
        cameraMessage?.text = if (mode == "photo") "Modalità foto" else "Modalità video"
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (screen != "camera" || cameraMode != mode) return@addListener
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()
                val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                if (mode == "photo") {
                    val photo = ImageCapture.Builder().build()
                    imageCapture = photo
                    videoCapture = null
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, photo)
                } else {
                    imageCapture = null
                    val video = VideoCapture.withOutput(Recorder.Builder().build())
                    videoCapture = video
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, video)
                }
            } catch (e: Exception) { cameraMessage?.text = "Fotocamera: ${error(e)}" }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                if (vault.isUnlocked) showCamera()
                else requestCamera { if (it.startsWith("ERR")) toast(it) }
            }
            else toast("Il permesso fotocamera è necessario")
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val (item, raw) = try { vault.beginCapture("photo") }
        catch (e: Exception) { toast(error(e)); return }
        pendingPhotos++
        cameraMessage?.text = "Salvataggio foto…"
        try {
            capture.takePicture(ImageCapture.OutputFileOptions.Builder(raw).build(),
                ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    io.execute {
                        val message = try { vault.finishCapture(item.id); "Foto privata salvata" }
                        catch (e: Exception) { "Foto privata da recuperare: ${error(e)}" }
                        runOnUiThread {
                            pendingPhotos--
                            cameraMessage?.text = message
                            lockAfterBackgroundCapture()
                        }
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    vault.discardCapture(item.id)
                    pendingPhotos--
                    cameraMessage?.text = "Scatto non riuscito: ${error(exception)}"
                }
                })
        } catch (e: Exception) {
            vault.discardCapture(item.id)
            pendingPhotos--
            cameraMessage?.text = "Scatto non riuscito: ${error(e)}"
        }
    }

    private fun toggleVideo() {
        if (videoBusy) { recording?.stop(); cameraMessage?.text = "Salvataggio video…"; return }
        val capture = videoCapture ?: return
        val (item, raw) = try { vault.beginCapture("video") }
        catch (e: Exception) { toast(error(e)); return }
        videoBusy = true
        recordingId = item.id
        try {
            var pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(raw).build())
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                pending = pending.withAudioEnabled()
            recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    val id = recordingId ?: item.id
                    recordingId = null
                    io.execute {
                        val message = try {
                            if (raw.length() == 0L) { vault.discardCapture(id); "Video non salvato" }
                            else { vault.finishCapture(id); "Video privato salvato" }
                        } catch (e: Exception) { "Video privato da recuperare: ${error(e)}" }
                        runOnUiThread {
                            videoBusy = false
                            cameraMessage?.text = message
                            lockAfterBackgroundCapture()
                        }
                    }
                }
            }
            cameraMessage?.text = "Registrazione in corso"
        } catch (e: Exception) {
            videoBusy = false
            recordingId = null
            vault.discardCapture(item.id)
            cameraMessage?.text = "Registrazione non riuscita: ${error(e)}"
        }
    }

    private fun showGallery() {
        screen = "gallery"
        clearGalleryTemp()
        root.removeAllViews()
        root.addView(label("Galleria privata", 24f))
        root.addView(button("Indietro") { showHome() })
        val items = vault.itemsForView()
        if (items.isEmpty()) { root.addView(label("Nessun file pronto")); return }
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll)
        items.forEachIndexed { index, item ->
            list.addView(button("${index + 1}. ${if (item.kind == "photo") "Foto" else "Video"}") {
                showMedia(item)
            })
        }
    }

    private fun showMedia(item: MediaItem) {
        screen = "media"
        clearGalleryTemp()
        root.removeAllViews()
        root.addView(button("Torna alla galleria") { showGallery() })
        io.execute {
            try {
                if (item.kind == "photo") {
                    val bytes = ByteArrayOutputStream().also { vault.decrypt(item, it) }.toByteArray()
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: throw IllegalStateException("Immagine non leggibile")
                    runOnUiThread {
                        if (screen != "media") return@runOnUiThread
                        val view = ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_CENTER }
                        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                    }
                } else {
                    val temp = File(cacheDir, "private-preview.mp4")
                    FileOutputStream(temp).use { vault.decrypt(item, it) }
                    galleryTemp = temp
                    runOnUiThread {
                        if (screen != "media") { clearGalleryTemp(); return@runOnUiThread }
                        val view = VideoView(this).apply { setVideoPath(temp.absolutePath); setOnPreparedListener { it.isLooping = false; start() } }
                        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                    }
                }
            } catch (e: Exception) {
                File(cacheDir, "private-preview.mp4").delete()
                runOnUiThread { toast("Anteprima: ${error(e)}") }
            }
        }
    }

    private fun clearGalleryTemp() {
        galleryTemp?.delete()
        galleryTemp = null
    }

    private fun requestPair() {
        authenticate("Autorizza Termux", {
            val fingerprint = termuxFingerprint() ?: throw IllegalStateException("Termux non installato")
            AlertDialog.Builder(this).setTitle("Autorizza Termux")
                .setMessage("Certificato SHA-256 di Termux:\n$fingerprint\n\nAutorizzare questa installazione a controllare le sessioni?")
                .setPositiveButton("Autorizza") { _, _ ->
                    getSharedPreferences("local", MODE_PRIVATE).edit()
                        .putString("termuxFingerprint", fingerprint).apply()
                    toast("Termux autorizzato")
                }.setNegativeButton("Annulla", null).show()
        }, { if (it.startsWith("ERR")) toast(it) })
    }

    internal fun isTrustedTermux(uid: Int): Boolean {
        val expected = getSharedPreferences("local", MODE_PRIVATE)
            .getString("termuxFingerprint", null) ?: return false
        return try {
            packageManager.getPackageUid("com.termux", 0) == uid &&
                termuxFingerprint() == expected
        } catch (_: Exception) { false }
    }

    @Suppress("DEPRECATION")
    private fun termuxFingerprint(): String? {
        return try {
            val info = packageManager.getPackageInfo("com.termux", PackageManager.GET_SIGNING_CERTIFICATES)
            val signer = info.signingInfo?.apkContentsSigners?.singleOrNull() ?: return null
            MessageDigest.getInstance("SHA-256").digest(signer.toByteArray())
                .joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) { null }
    }
}
