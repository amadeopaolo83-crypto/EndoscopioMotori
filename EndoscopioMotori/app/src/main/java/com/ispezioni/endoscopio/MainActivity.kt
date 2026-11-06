package com.ispezioni.endoscopio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import java.io.File
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: FrameLayout
    private lateinit var previewView: TextureView
    private lateinit var statusText: TextView
    private lateinit var noteInput: EditText
    private lateinit var btnRotate: Button
    private lateinit var btnMirror: Button
    private lateinit var btnRec: Button
    private lateinit var timerText: TextView
    private lateinit var lastKeyText: TextView
    private lateinit var brightnessRow: View
    private lateinit var brightnessSeek: SeekBar
    private lateinit var flashView: View
    private lateinit var btnRes: Button
    private var pendingDefault = false

    private var helper: CameraHelper? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surfaceAdded = false
    private var cameraOpened = false
    private var selectedDevice: UsbDevice? = null
    private var previewW = 0
    private var previewH = 0
    private var zoom = 1f
    private var pendingReopen: Size? = null
    private var reopenTried = false
    private var askedPermissions = false
    private var statusAction: (() -> Unit)? = null
    private var brightness: BrightnessControl? = null
    private var lastPhotoMs = 0L

    private var recording = false
    private var recStart = 0L

    private val ui = Handler(Looper.getMainLooper())
    private val openWatchdog = Runnable {
        if (!cameraOpened) showStatus(R.string.err_busy) { retryConnect() }
    }
    private val timerRunnable = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - recStart) / 1000
            timerText.text = "● %02d:%02d".format(s / 60, s % 60)
            ui.postDelayed(this, 500)
        }
    }

    private val permLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { ensurePermissionsThenStart() }

    // ---------------------------------------------------------------- ciclo di vita

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        container = findViewById(R.id.previewContainer)
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        noteInput = findViewById(R.id.noteInput)
        btnRotate = findViewById(R.id.btnRotate)
        btnMirror = findViewById(R.id.btnMirror)
        btnRec = findViewById(R.id.btnRec)
        timerText = findViewById(R.id.timerText)
        lastKeyText = findViewById(R.id.lastKeyText)
        brightnessRow = findViewById(R.id.brightnessRow)
        brightnessSeek = findViewById(R.id.brightnessSeek)
        flashView = findViewById(R.id.flashView)
        btnRes = findViewById(R.id.btnRes)
        btnRes.setOnClickListener { showResolutionDialog() }

        noteInput.setText(prefs.note)
        noteInput.doAfterTextChanged { prefs.note = it?.toString() ?: "" }
        noteInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                v.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else false
        }

        updateRotateMirrorLabels()
        btnRotate.setOnClickListener {
            prefs.rotation = (prefs.rotation + 90) % 360
            updateRotateMirrorLabels()
            layoutPreview()
        }
        btnMirror.setOnClickListener {
            prefs.mirror = !prefs.mirror
            updateRotateMirrorLabels()
            applyTransform()
        }
        findViewById<Button>(R.id.btnPhoto).setOnClickListener { takePhoto() }
        btnRec.setOnClickListener { if (recording) stopRec() else startRec() }
        findViewById<Button>(R.id.btnGallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        statusText.setOnClickListener { statusAction?.invoke() }

        brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val b = brightness ?: return
                if (fromUser) b.set(b.min + progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        previewView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surfaceTexture = st
                attachSurfaceIfReady()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                if (surfaceAdded) {
                    try {
                        helper?.removeSurface(st)
                    } catch (_: Exception) {
                    }
                }
                surfaceAdded = false
                surfaceTexture = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPreview() }

        // Zoom digitale con pizzico; doppio tocco = azzera
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    zoom = (zoom * d.scaleFactor).coerceIn(1f, 6f)
                    applyTransform()
                    return true
                }
            })
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoom = 1f
                applyTransform()
                return true
            }
        })
        container.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            tapDetector.onTouchEvent(ev)
            true
        }

        showStatus(R.string.status_connect)
    }

    override fun onStart() {
        super.onStart()
        ensurePermissionsThenStart()
    }

    override fun onStop() {
        if (recording) stopRec()
        releaseHelper()
        super.onStop()
    }

    // ---------------------------------------------------------------- permessi

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf<String>()
        if (!granted(Manifest.permission.CAMERA)) needed += Manifest.permission.CAMERA
        if (!granted(Manifest.permission.RECORD_AUDIO)) needed += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            needed += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (needed.isNotEmpty() && !askedPermissions) {
            askedPermissions = true
            permLauncher.launch(needed.toTypedArray())
            return
        }
        if (granted(Manifest.permission.CAMERA)) {
            initHelper()
        } else {
            showStatus(R.string.err_camera_perm) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        }
    }

    // ---------------------------------------------------------------- camera UVC

    private fun initHelper() {
        if (helper != null) return
        val h = CameraHelper()
        // senza permesso microfono la registrazione parte comunque, ma senza audio
        CameraGlue.configure(h, granted(Manifest.permission.RECORD_AUDIO))
        h.setStateCallback(stateCallback)
        helper = h
        reopenTried = false
        showStatus(R.string.status_connect)
        // sonda già collegata all'avvio dell'app
        try {
            h.deviceList?.firstOrNull()?.let { requestDevice(it) }
        } catch (_: Exception) {
        }
    }

    private fun releaseHelper() {
        ui.removeCallbacks(openWatchdog)
        helper?.let {
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        helper = null
        cameraOpened = false
        surfaceAdded = false
        selectedDevice = null
        pendingReopen = null
        brightness = null
        brightnessRow.visibility = View.GONE
    }

    private fun retryConnect() {
        releaseHelper()
        initHelper()
    }

    private fun requestDevice(device: UsbDevice) {
        if (selectedDevice != null && selectedDevice == device) return
        val h = helper ?: return
        selectedDevice = device
        showStatus(R.string.status_permission)
        ui.removeCallbacks(openWatchdog)
        ui.postDelayed(openWatchdog, 15000)
        try {
            h.selectDevice(device)
        } catch (e: Exception) {
            selectedDevice = null
            showStatus(R.string.err_busy) { retryConnect() }
        }
    }

    private val stateCallback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice?) {
            if (device != null) requestDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
            openCameraPreferred()
        }

        override fun onCameraOpen(device: UsbDevice?) {
            onCameraOpened()
        }

        override fun onCameraClose(device: UsbDevice?) {
            onCameraClosed()
        }

        override fun onDeviceClose(device: UsbDevice?) {}

        override fun onDetach(device: UsbDevice?) {
            onDetached()
        }

        override fun onCancel(device: UsbDevice?) {
            // permesso USB negato dall'utente
            ui.removeCallbacks(openWatchdog)
            selectedDevice = null
            showStatus(R.string.err_usb_denied) {
                helper?.deviceList?.firstOrNull()?.let { requestDevice(it) }
            }
        }
    }

    /** Elenco risoluzioni senza doppioni, dalla più grande. */
    private fun sizeList(h: CameraHelper): List<Size> = try {
        (h.supportedSizeList ?: emptyList<Size>())
            .distinctBy { it.width to it.height }
            .sortedByDescending { it.width.toLong() * it.height }
    } catch (e: Exception) {
        emptyList()
    }

    /** Apre alla risoluzione scelta dall'utente; se non c'è, alla predefinita della libreria. */
    private fun openCameraPreferred() {
        val h = helper ?: return
        try {
            val saved = prefs.resolution
            val chosen = if (saved.isEmpty()) null
            else sizeList(h).firstOrNull { "${it.width}x${it.height}" == saved }
            if (chosen != null) h.openCamera(chosen) else h.openCamera()
        } catch (e: Exception) {
            showStatus(R.string.err_busy) { retryConnect() }
        }
    }

    private fun showResolutionDialog() {
        val h = helper
        if (h == null || !cameraOpened) {
            Toast.makeText(this, R.string.msg_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (recording) {
            Toast.makeText(this, R.string.msg_res_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val sizes = sizeList(h)
        val labels = ArrayList<String>()
        labels.add(getString(R.string.res_auto))
        sizes.forEach { labels.add("${it.width} x ${it.height}") }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.res_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    prefs.resolution = ""
                    pendingDefault = true
                } else {
                    val s = sizes[which - 1]
                    prefs.resolution = "${s.width}x${s.height}"
                    pendingReopen = s
                }
                try {
                    h.closeCamera()
                } catch (e: Exception) {
                    pendingReopen = null
                    pendingDefault = false
                }
            }
            .show()
    }

    private fun onCameraOpened() {
        val h = helper ?: return
        ui.removeCallbacks(openWatchdog)
        cameraOpened = true

        val cur = try {
            h.previewSize
        } catch (e: Exception) {
            null
        }
        if (cur != null) {
            previewW = cur.width
            previewH = cur.height
            btnRes.text = "${cur.width}x${cur.height} ▾"
            layoutPreview()
        }
        try {
            h.startPreview()
        } catch (_: Exception) {
        }
        attachSurfaceIfReady()
        previewView.alpha = 1f
        hideStatus()

        // tasto fisico della sonda (evento UVC)
        val btnOk = UvcExtras.registerButton(h) { _, state ->
            if (state != 0) runOnUiThread {
                Toast.makeText(this, R.string.msg_probe_button, Toast.LENGTH_SHORT).show()
                takePhoto()
            }
        }
        lastKeyText.text = getString(if (btnOk) R.string.key_uvc_listening else R.string.key_only_keyboard)

        // slider luminosità, solo se la sonda espone il controllo UVC
        brightness = UvcExtras.brightness(h)
        val b = brightness
        if (b != null) {
            brightnessSeek.max = b.max - b.min
            brightnessSeek.progress = (b.get() - b.min).coerceIn(0, b.max - b.min)
            brightnessRow.visibility = View.VISIBLE
        } else {
            brightnessRow.visibility = View.GONE
        }
    }

    private fun onCameraClosed() {
        if (surfaceAdded) {
            try {
                surfaceTexture?.let { helper?.removeSurface(it) }
            } catch (_: Exception) {
            }
        }
        cameraOpened = false
        surfaceAdded = false
        val p = pendingReopen
        if (p != null || pendingDefault) {
            pendingReopen = null
            pendingDefault = false
            try {
                if (p != null) helper?.openCamera(p) else helper?.openCamera()
            } catch (e: Exception) {
                showStatus(R.string.err_busy) { retryConnect() }
            }
        }
    }

    private fun onDetached() {
        ui.removeCallbacks(openWatchdog)
        if (recording) {
            recording = false
            ui.removeCallbacks(timerRunnable)
            updateRecUi()
            Toast.makeText(this, R.string.msg_rec_lost, Toast.LENGTH_LONG).show()
        }
        cameraOpened = false
        surfaceAdded = false
        selectedDevice = null
        reopenTried = false
        pendingReopen = null
        brightness = null
        brightnessRow.visibility = View.GONE
        previewW = 0
        previewView.alpha = 0f
        showStatus(R.string.status_connect)
    }

    private fun attachSurfaceIfReady() {
        val st = surfaceTexture ?: return
        val h = helper ?: return
        if (cameraOpened && !surfaceAdded) {
            try {
                h.addSurface(st, false)
                surfaceAdded = true
            } catch (_: Exception) {
            }
        }
    }

    // ---------------------------------------------------------------- anteprima (rotazione/specchio/zoom)

    private fun updateRotateMirrorLabels() {
        btnRotate.text = "↻ ${prefs.rotation}°"
        btnMirror.text = if (prefs.mirror) "⇋ SÌ" else "⇋ NO"
    }

    private fun layoutPreview() {
        val cw = container.width
        val ch = container.height
        if (cw == 0 || ch == 0 || previewW == 0 || previewH == 0) return
        val odd = prefs.rotation == 90 || prefs.rotation == 270
        val s = if (odd) min(cw.toFloat() / previewH, ch.toFloat() / previewW)
        else min(cw.toFloat() / previewW, ch.toFloat() / previewH)
        val lp = FrameLayout.LayoutParams((previewW * s).toInt(), (previewH * s).toInt(), Gravity.CENTER)
        previewView.layoutParams = lp
        applyTransform()
    }

    private fun applyTransform() {
        previewView.rotation = prefs.rotation.toFloat()
        previewView.scaleX = zoom * (if (prefs.mirror) -1f else 1f)
        previewView.scaleY = zoom
    }

    // ---------------------------------------------------------------- foto

    private fun takePhoto() {
        val h = helper
        if (h == null || !cameraOpened) {
            Toast.makeText(this, R.string.msg_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPhotoMs < 700) return // anti doppio-scatto
        lastPhotoMs = now

        val tmp = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val name = MediaRepo.buildName(noteInput.text.toString(), "jpg")
        val rot = prefs.rotation
        val mir = prefs.mirror
        flash()
        try {
            CameraGlue.takePicture(h, tmp, object : CameraGlue.Done {
                override fun ok() {
                    runOnUiThread { saveAsync(tmp, name, false, rot, mir) }
                }

                override fun fail(message: String?) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.msg_photo_error, message ?: "?"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.msg_photo_error, e.message ?: "?"), Toast.LENGTH_LONG).show()
        }
    }

    private fun flash() {
        flashView.alpha = 0.7f
        flashView.visibility = View.VISIBLE
        flashView.animate().alpha(0f).setDuration(180).withEndAction {
            flashView.visibility = View.GONE
        }.start()
    }

    private fun saveAsync(file: File, name: String, isVideo: Boolean, rot: Int, mir: Boolean) {
        val appCtx = applicationContext
        Thread {
            var ok = false
            try {
                if (!isVideo) MediaRepo.writeExifOrientation(file, rot, mir)
                ok = MediaRepo.saveToGallery(appCtx, file, name, isVideo) != null
            } catch (_: Exception) {
            } finally {
                file.delete()
            }
            runOnUiThread {
                val msg = if (ok) getString(if (isVideo) R.string.msg_video_saved else R.string.msg_photo_saved, name)
                else getString(R.string.msg_save_error)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    // ---------------------------------------------------------------- video

    private fun startRec() {
        val h = helper
        if (h == null || !cameraOpened) {
            Toast.makeText(this, R.string.msg_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val tmp = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        val name = MediaRepo.buildName(noteInput.text.toString(), "mp4")
        try {
            CameraGlue.startRecording(h, tmp, object : CameraGlue.Done {
                override fun ok() {
                    runOnUiThread { saveAsync(tmp, name, true, 0, false) }
                }

                override fun fail(message: String?) {
                    runOnUiThread {
                        if (recording) {
                            recording = false
                            ui.removeCallbacks(timerRunnable)
                            updateRecUi()
                        }
                        tmp.delete()
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.msg_rec_error, message ?: "?"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            })
            recording = true
            recStart = SystemClock.elapsedRealtime()
            ui.post(timerRunnable)
            updateRecUi()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.msg_rec_error, e.message ?: "?"), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRec() {
        recording = false
        ui.removeCallbacks(timerRunnable)
        updateRecUi()
        try {
            helper?.let { CameraGlue.stopRecording(it) }
        } catch (_: Exception) {
        }
    }

    private fun updateRecUi() {
        btnRec.setText(if (recording) R.string.btn_stop else R.string.btn_rec)
        btnRec.setBackgroundResource(if (recording) R.drawable.bg_btn_rec_active else R.drawable.bg_btn_rec)
        timerText.visibility = if (recording) View.VISIBLE else View.GONE
        if (recording) timerText.text = "● 00:00"
    }

    // ---------------------------------------------------------------- tasto fisico (come tasto tastiera)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val typing = currentFocus is EditText
        val isProbeKey = when (code) {
            KeyEvent.KEYCODE_CAMERA, KeyEvent.KEYCODE_FOCUS -> true
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_CENTER -> !typing
            else -> false
        }
        val ignoredForDiagnostics = code == KeyEvent.KEYCODE_BACK ||
                code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN ||
                code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_APP_SWITCH
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !ignoredForDiagnostics) {
            lastKeyText.text = getString(
                R.string.key_last,
                "${KeyEvent.keyCodeToString(code)} (${event.device?.name ?: "?"})"
            )
        }
        if (isProbeKey) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) takePhoto()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- messaggi a schermo

    private fun showStatus(@StringRes id: Int, action: (() -> Unit)? = null) {
        statusAction = action
        statusText.setText(id)
        statusText.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        statusAction = null
        statusText.visibility = View.GONE
    }
}
