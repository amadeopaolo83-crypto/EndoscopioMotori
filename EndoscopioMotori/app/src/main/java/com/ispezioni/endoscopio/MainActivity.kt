package com.ispezioni.endoscopio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.widget.AspectRatioSurfaceView
import com.jiangdg.usb.USBMonitor
import java.io.File
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: FrameLayout
    private lateinit var previewView: AspectRatioSurfaceView
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
    private lateinit var debugText: TextView
    private var fpsCount = 0
    private var fpsT0 = 0L
    private val dbgLines = ArrayDeque<String>()

    private var client: MultiCameraClient? = null
    private var camera: MultiCameraClient.ICamera? = null
    private var cameraOpened = false
    private var selectedDevice: UsbDevice? = null
    private var previewW = 0
    private var previewH = 0
    private var zoom = 1f
    private var askedPermissions = false
    private var statusAction: (() -> Unit)? = null
    private var brightness: Any? = null
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
        installCrashLogger()
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
        debugText = findViewById(R.id.debugText)
        btnRes.visibility = View.GONE
        debugText.visibility = View.GONE

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
            applyLibRotation()
        }
        btnMirror.setOnClickListener {
            prefs.mirror = !prefs.mirror
            updateRotateMirrorLabels()
            applyLibRotation()
        }
        findViewById<Button>(R.id.btnPhoto).setOnClickListener { takePhoto() }
        btnRec.setOnClickListener { if (recording) stopRec() else startRec() }
        findViewById<Button>(R.id.btnGallery).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        statusText.setOnClickListener { statusAction?.invoke() }

        brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) try {
                    (camera as? CameraUVC)?.setBrightness(progress)
                } catch (_: Throwable) {
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        previewView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                val p = pendingOpen
                pendingOpen = null
                if (p != null) openCam(p.first, p.second)
            }

            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, w: Int, h: Int) {}

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                // senza superficie il disegno OpenGL della libreria va in crash: ferma tutto subito
                if (!recording) {
                    android.os.Process.killProcess(android.os.Process.myPid())
                } else {
                    try {
                        camera?.closeCamera()
                    } catch (_: Throwable) {
                    }
                }
            }
        })
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
        ui.removeCallbacks(killRunnable)
        ensurePermissionsThenStart()
    }

    /** Se l'app va in crash scrive l'errore in Download/crash_endoscopio.txt (per poterlo analizzare). */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val ctx = applicationContext
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        "crash_endoscopio_" + System.currentTimeMillis() % 100000 + ".txt")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download")
                }
                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use {
                    it.write(("thread=" + t.name + "\n" + android.util.Log.getStackTraceString(e)).toByteArray())
                }
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    private val killRunnable = Runnable {
        // chiusura pulita: la libreria USB non regge una seconda apertura nello stesso processo
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onStop() {
        val wasRec = recording
        if (wasRec) stopRec()
        super.onStop()
        ui.removeCallbacks(killRunnable)
        ui.postDelayed(killRunnable, if (wasRec) 5000L else 400L)
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

    // ---------------------------------------------------------------- camera UVC (AUSBC)

    private fun initHelper() {
        if (client != null) return
        showStatus(R.string.status_connect)
        val c = MultiCameraClient(SafeContext(this), object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                runOnUiThread {
                    dbg("attach")
                    if (device != null) requestDevice(device)
                }
            }

            override fun onDetachDec(device: UsbDevice?) {
                runOnUiThread { dbg("detach"); onDetached() }
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                runOnUiThread {
                    dbg("connect")
                    if (device != null && ctrlBlock != null) openCam(device, ctrlBlock)
                }
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                runOnUiThread { dbg("disconnect"); onCameraClosed() }
            }

            override fun onCancelDev(device: UsbDevice?) {
                runOnUiThread {
                    dbg("cancel")
                    ui.removeCallbacks(openWatchdog)
                    selectedDevice = null
                    showStatus(R.string.err_usb_denied) {
                        client?.getDeviceList(null)?.firstOrNull()?.let { requestDevice(it) }
                    }
                }
            }
        })
        client = c
        try {
            c.register()
        } catch (e: Throwable) {
            dbg("errore register: ${e.message}")
        }
        // sonda gia' collegata all'avvio dell'app
        try {
            c.getDeviceList(null)?.firstOrNull()?.let { requestDevice(it) }
        } catch (_: Throwable) {
        }
    }

    private fun releaseHelper() {
        ui.removeCallbacks(openWatchdog)
        try {
            camera?.closeCamera()
        } catch (_: Throwable) {
        }
        camera = null
        try {
            client?.unRegister()
            client?.destroy()
        } catch (_: Throwable) {
        }
        client = null
        cameraOpened = false
        selectedDevice = null
        brightness = null
        brightnessRow.visibility = View.GONE
    }

    private fun retryConnect() {
        releaseHelper()
        initHelper()
    }

    private fun requestDevice(device: UsbDevice) {
        if (selectedDevice != null && selectedDevice == device) return
        val c = client ?: return
        selectedDevice = device
        showStatus(R.string.status_permission)
        ui.removeCallbacks(openWatchdog)
        ui.postDelayed(openWatchdog, 15000)
        try {
            c.requestPermission(device)
        } catch (e: Throwable) {
            selectedDevice = null
            showStatus(R.string.err_busy) { retryConnect() }
        }
    }

    private var pendingOpen: Pair<UsbDevice, USBMonitor.UsbControlBlock>? = null

    private fun openCam(device: UsbDevice, ctrl: USBMonitor.UsbControlBlock) {
        if (!previewView.holder.surface.isValid) {
            pendingOpen = device to ctrl
            return
        }
        try {
            camera?.closeCamera()
        } catch (_: Throwable) {
        }
        val cam = CameraUVC(this, device)
        cam.setUsbControlBlock(ctrl)
        camera = cam
        cam.setCameraStateCallBack(object : ICameraStateCallBack {
            override fun onCameraState(self: MultiCameraClient.ICamera, code: ICameraStateCallBack.State, msg: String?) {
                runOnUiThread {
                    dbg("stato $code ${msg ?: ""}")
                    when (code) {
                        ICameraStateCallBack.State.OPENED -> onCameraOpened()
                        ICameraStateCallBack.State.CLOSED -> onCameraClosed()
                        ICameraStateCallBack.State.ERROR -> {
                            showStatus(R.string.err_busy) { retryConnect() }
                        }
                    }
                }
            }
        })
        val w = 640
        val h = 480
        previewW = w
        previewH = h
        layoutPreview()
        val req = CameraRequest.Builder()
            .setPreviewWidth(w)
            .setPreviewHeight(h)
            .create()
        try {
            cam.openCamera(previewView, req)
            dbg("open ${w}x$h")
        } catch (e: Throwable) {
            dbg("errore open: ${e.message}")
            showStatus(R.string.err_busy) { retryConnect() }
        }
    }

    /** Ultimi eventi della sonda, in giallo, per capire cosa succede. */
    private fun dbg(s: String) {
        dbgLines.addLast(s)
        while (dbgLines.size > 7) dbgLines.removeFirst()
        debugText.text = dbgLines.joinToString(" › ")
    }

    /** Legge come la sonda si presenta sull'USB e lo salva in Download (tocca la riga gialla). */
    private fun dumpUsb() {
        Thread {
            val sb = StringBuilder()
            try {
                val um = getSystemService(android.content.Context.USB_SERVICE) as android.hardware.usb.UsbManager
                val dev = selectedDevice ?: um.deviceList.values.firstOrNull()
                if (dev == null) {
                    runOnUiThread { Toast.makeText(this, "Nessuna sonda USB collegata", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                sb.append("VID=%04X PID=%04X\n".format(dev.vendorId, dev.productId))
                val c = um.openDevice(dev)
                if (c != null) {
                    sb.append(c.rawDescriptors.joinToString("") { "%02x".format(it) }).append('\n')
                    c.close()
                }
            } catch (t: Throwable) {
                sb.append("errore ").append(t.javaClass.simpleName).append('\n')
            }
            try {
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, "usb_descrittori.txt")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
                runOnUiThread { Toast.makeText(this, "Salvato in Download: usb_descrittori.txt", Toast.LENGTH_LONG).show() }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "Errore: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showResolutionDialog() {
        val cam = camera
        if (cam == null || !cameraOpened) {
            Toast.makeText(this, R.string.msg_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (recording) {
            Toast.makeText(this, R.string.msg_res_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val sizes = try {
            (cam.getAllPreviewSizes(null) ?: emptyList<PreviewSize>())
                .distinctBy { it.width to it.height }
                .sortedByDescending { it.width.toLong() * it.height }
        } catch (e: Throwable) {
            emptyList()
        }
        val labels = sizes.map { "${it.width} x ${it.height}" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.res_title)
            .setItems(labels) { _, which ->
                val sz = sizes[which]
                prefs.resolution = "${sz.width}x${sz.height}"
                previewW = sz.width
                previewH = sz.height
                btnRes.text = "${sz.width}x${sz.height} ▾"
                restartApp()
            }
            .show()
    }

    /** Riavvio pulito dell'app (la libreria USB non regge il cambio al volo). */
    private fun restartApp() {
        Toast.makeText(this, "Applico la risoluzione…", Toast.LENGTH_SHORT).show()
        try {
            releaseHelper()
        } catch (_: Throwable) {
        }
        val i = packageManager.getLaunchIntentForPackage(packageName)!!
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ui.postDelayed({
            startActivity(i)
            finishAffinity()
            Runtime.getRuntime().exit(0)
        }, 700)
    }

    private fun onCameraOpened() {
        val cam = camera ?: return
        ui.removeCallbacks(openWatchdog)
        cameraOpened = true
        layoutPreview()
        hideStatus()
        applyLibRotation()
        lastKeyText.text = getString(R.string.key_only_keyboard)

        // slider luminosita', solo se la sonda espone il controllo
        val cur = try {
            (cam as? CameraUVC)?.getBrightness()
        } catch (_: Throwable) {
            null
        }
        if (cur != null) {
            brightnessSeek.max = 100
            brightnessSeek.progress = cur.coerceIn(0, 100)
            brightnessRow.visibility = View.VISIBLE
        } else {
            brightnessRow.visibility = View.GONE
        }
    }

    private fun onCameraClosed() {
        cameraOpened = false
        if (recording) {
            recording = false
            ui.removeCallbacks(timerRunnable)
            updateRecUi()
        }
        if (selectedDevice != null && client != null) {
            showStatus(R.string.err_closed) { retryConnect() }
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
        selectedDevice = null
        camera = null
        brightness = null
        brightnessRow.visibility = View.GONE
        previewW = 0
        showStatus(R.string.status_connect)
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
        val iw = if (odd) previewH else previewW
        val ih = if (odd) previewW else previewH
        val s = min(cw.toFloat() / iw, ch.toFloat() / ih)
        val vw = (iw * s).toInt()
        val vh = (ih * s).toInt()
        val lp = previewView.layoutParams as FrameLayout.LayoutParams
        // niente nuovo layout se la misura non cambia (evita cicli continui di layout)
        if (lp.width != vw || lp.height != vh || lp.gravity != Gravity.CENTER) {
            lp.width = vw
            lp.height = vh
            lp.gravity = Gravity.CENTER
            previewView.layoutParams = lp
        }
        applyTransform()
    }

    /** Solo zoom: rotazione e specchio le fa la libreria (applyLibRotation). */
    private fun applyTransform() {
        previewView.scaleX = zoom
        previewView.scaleY = zoom
    }

    /** Rotazione/specchio dentro la libreria (disegno OpenGL), scelti per nome per sicurezza. */
    private fun applyLibRotation() {
        val cam = camera ?: return
        try {
            val cls = Class.forName("com.jiangdg.ausbc.render.env.RotateType")
            val all = cls.enumConstants!!.map { it as Enum<*> }
            val rot = prefs.rotation
            val want = when {
                prefs.mirror && rot == 0 -> "FLIP_LEFT_RIGHT"
                prefs.mirror && rot == 180 -> "FLIP_UP_DOWN"
                else -> "ANGLE_$rot"
            }
            val c = all.firstOrNull { it.name == want } ?: all.firstOrNull { it.name == "ANGLE_$rot" }
            if (c != null) {
                cam.javaClass.getMethod("setRotateType", cls).invoke(cam, c)
                dbg("rot ${c.name}")
            } else dbg("rot? " + all.joinToString(",") { it.name })
        } catch (t: Throwable) {
            dbg("rot errore ${t.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------- foto

    private fun takePhoto() {
        if (camera == null || !cameraOpened) {
            Toast.makeText(this, R.string.msg_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastPhotoMs < 700) return // anti doppio-scatto
        lastPhotoMs = now

        val tmp = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val name = MediaRepo.buildName(noteInput.text.toString(), "jpg")
        val w = previewView.width
        val h = previewView.height
        if (w <= 0 || h <= 0) return
        flash()
        try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(previewView, bmp, { res ->
                if (res == android.view.PixelCopy.SUCCESS) {
                    Thread {
                        val okSave = try {
                            java.io.FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                        } catch (_: Throwable) {
                            false
                        }
                        runOnUiThread {
                            if (okSave) saveAsync(tmp, name, false, 0, false)
                            else Toast.makeText(this, R.string.msg_save_error, Toast.LENGTH_LONG).show()
                        }
                    }.start()
                } else {
                    Toast.makeText(this, getString(R.string.msg_photo_error, "copia $res"), Toast.LENGTH_LONG).show()
                }
            }, ui)
        } catch (e: Throwable) {
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

    private var recorder: FrameRecorder? = null
    private var recFile: File? = null
    private var recName = ""
    private val recCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(data: ByteArray?, width: Int, height: Int, format: IPreviewDataCallBack.DataFormat) {
            if (data == null) return
            recorder?.feed(data, width, height, format.name.contains("RGB", true))
        }
    }

    private fun startRec() {
        val cam = camera
        if (cam == null || !cameraOpened) {
            Toast.makeText(this, R.string.msg_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        val tmp = File(cacheDir, "rec_${System.currentTimeMillis()}.mp4")
        recFile = tmp
        recName = MediaRepo.buildName(noteInput.text.toString(), "mp4")
        recorder = FrameRecorder(tmp.absolutePath, 0)
        try {
            cam.addPreviewDataCallBack(recCallback)
            recording = true
            recStart = SystemClock.elapsedRealtime()
            ui.post(timerRunnable)
            updateRecUi()
        } catch (e: Throwable) {
            recorder = null
            Toast.makeText(this, getString(R.string.msg_rec_error, e.message ?: "?"), Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRec() {
        recording = false
        ui.removeCallbacks(timerRunnable)
        updateRecUi()
        try {
            camera?.removePreviewDataCallBack(recCallback)
        } catch (_: Throwable) {
        }
        val r = recorder
        val f = recFile
        val name = recName
        recorder = null
        if (r == null || f == null) return
        Thread {
            val ok = r.finish()
            runOnUiThread {
                if (ok) saveAsync(f, name, true, 0, false)
                else {
                    f.delete()
                    Toast.makeText(this, getString(R.string.msg_rec_error, r.error ?: "nessun fotogramma"), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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
