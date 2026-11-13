package com.ispezioni.endoscopio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.serenegiant.usb.UVCParam
import java.io.File
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: FrameLayout
    private lateinit var previewView: SurfaceView
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
    private lateinit var frameView: ImageView
    @Volatile private var frameBusy = false
    private val grabbed = ArrayList<ByteArray>()
    @Volatile private var grabLeft = 0
    private var fpsCount = 0
    private var fpsT0 = 0L
    private var fpsVal = 0
    private var lossPct = -1
    private var accum: ByteArray? = null
    private var scratch: ByteArray? = null
    @Volatile private var lastBmp: Bitmap? = null
    private var lastDecode = 0L
    private var lastInfo = 0L
    private val dbgLines = ArrayDeque<String>()
    private var pendingDefault = false

    private var helper: CameraHelper? = null
    private var surface: Surface? = null
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
        debugText = findViewById(R.id.debugText)
        frameView = findViewById(R.id.frameView)
        lastKeyText.setOnClickListener {
            if (cameraOpened) {
                synchronized(grabbed) { grabbed.clear() }
                grabLeft = 4
                Toast.makeText(this, "Salvo 4 fotogrammi grezzi…", Toast.LENGTH_SHORT).show()
            }
        }
        btnRes.setOnClickListener { showResolutionDialog() }
        debugText.setOnClickListener { showDiagnostics() }

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

        previewView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                attachSurfaceIfReady()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (surfaceAdded) {
                    try {
                        helper?.removeSurface(holder.surface)
                    } catch (_: Exception) {
                    }
                }
                surfaceAdded = false
                surface = null
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
        if (prefs.optConfig) CameraGlue.configure(h, granted(Manifest.permission.RECORD_AUDIO))
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
            dbg("attach")
            if (device != null) requestDevice(device)
        }

        override fun onDeviceOpen(device: UsbDevice?, isFirstOpen: Boolean) {
            dbg("deviceOpen")
            dbg(sysCams())
            ui.postDelayed({ dbg(sysCams()) }, 2500)
            openCameraPreferred()
        }

        override fun onCameraOpen(device: UsbDevice?) {
            dbg("cameraOpen")
            onCameraOpened()
        }

        override fun onCameraClose(device: UsbDevice?) {
            dbg("cameraClose")
            onCameraClosed()
        }

        override fun onDeviceClose(device: UsbDevice?) {
            dbg("deviceClose")
        }

        override fun onDetach(device: UsbDevice?) {
            dbg("detach")
            onDetached()
        }

        override fun onCancel(device: UsbDevice?) {
            // permesso USB negato dall'utente
            dbg("cancel")
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
            .distinctBy { it.toString() }
            .sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenBy { it.toString() })
    } catch (e: Exception) {
        emptyList()
    }

    /** Con la prova "frame rate basso": voce con il frame rate minimo dichiarato (costruita da zero). */
    private fun tuneSize(sz: Size): Size {
        if (!prefs.optLowFps) return sz
        return try {
            val low = (sz.fpsList ?: emptyList<Any>()).filterIsInstance<Number>().map { it.toInt() }.filter { it > 0 }.minOrNull()
                ?: return sz
            val ctor = Size::class.java.constructors.first { it.parameterTypes.size == 5 }
            val vals = listOf(sz.type, sz.width, sz.height, low)
            var found: Size? = null
            fun perm(cur: List<Int>, rest: List<Int>) {
                if (found != null) return
                if (rest.isEmpty()) {
                    try {
                        val c = ctor.newInstance(cur[0], cur[1], cur[2], cur[3], sz.fpsList) as Size
                        if (c.width == sz.width && c.height == sz.height && c.fps == low && c.type == sz.type) found = c
                    } catch (_: Throwable) {
                    }
                    return
                }
                for (i in rest.indices) perm(cur + rest[i], rest.filterIndexed { j, _ -> j != i })
            }
            perm(emptyList(), vals)
            dbg(if (found != null) "fps forzato a $low" else "fps NON forzabile")
            found ?: sz
        } catch (t: Throwable) {
            dbg("tune errore ${t.javaClass.simpleName}")
            sz
        }
    }

    /** Camere che Android stesso vede (0 dietro, 1 davanti, 2 esterna/USB). */
    private fun sysCams(): String = try {
        val cm = getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        "camere sistema: " + cm.cameraIdList.joinToString(" ") { id ->
            val f = cm.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
            "$id:${when (f) { 0 -> "front"; 1 -> "back"; 2 -> "EXT"; else -> "?" }}"
        }
    } catch (t: Throwable) {
        "camere sistema: errore ${t.javaClass.simpleName}"
    }

    /** Chiave salvata per una voce dell'elenco (include il formato video). */
    private fun sizeKey(sz: Size): String = sz.toString()

    /** Formato video dichiarato dalla sonda (numero della libreria), se leggibile. */
    private fun sizeFmt(sz: Size): String =
        Regex("type:\\s*(\\d+)").find(sz.toString())?.groupValues?.get(1)?.let { " · formato $it" } ?: ""

    /** Apre alla risoluzione scelta dall'utente; se non c'è, alla predefinita della libreria. */
    private fun openCameraPreferred() {
        val h = helper ?: return
        try {
            val saved = prefs.resolution
            val chosen = if (saved.isEmpty()) null
            else sizeList(h).firstOrNull { sizeKey(it) == saved }
            if (prefs.optQuirk || prefs.optLowFps) {
                val base = chosen ?: sizeList(h).firstOrNull()
                if (base == null) {
                    dbg("open auto")
                    h.openCamera()
                } else {
                    val t = tuneSize(base)
                    dbg("open prova $t")
                    if (prefs.optQuirk) h.openCamera(UVCParam(t, 128)) else h.openCamera(t)
                }
            } else {
                dbg(if (chosen != null) "open ${chosen}" else "open auto")
                if (chosen != null) h.openCamera(chosen) else h.openCamera()
            }
        } catch (e: Exception) {
            dbg("errore open: ${e.message}")
            showStatus(R.string.err_busy) { retryConnect() }
        }
    }

    /** Tocca la riga gialla: interruttori per trovare cosa disturba l'anteprima. */
    private fun showDiagnostics() {
        if (recording) return
        val labels = arrayOf(
            "Tasto sonda UVC",
            "Slider luminosità",
            "Config foto/video (JPG max, audio)",
            "Anteprima adattata (ruota, specchio, zoom)",
            "Banda USB ridotta (quirk)",
            "Frame rate basso"
        )
        val checked = booleanArrayOf(prefs.optButton, prefs.optBrightness, prefs.optConfig, prefs.optFit, prefs.optQuirk, prefs.optLowFps)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Prove diagnostiche")
            .setMultiChoiceItems(labels, checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Applica e riconnetti") { _, _ ->
                prefs.optButton = checked[0]
                prefs.optBrightness = checked[1]
                prefs.optConfig = checked[2]
                prefs.optFit = checked[3]
                prefs.optQuirk = checked[4]
                prefs.optLowFps = checked[5]
                dbgLines.clear()
                retryConnect()
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton("Analisi USB") { _, _ -> dumpUsb() }
            .show()
    }

    /** Legge come la sonda si presenta sull'USB (interfacce, canali, descrittori) e lo salva in Download. */
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
                sb.append("VID=%04X PID=%04X class=%d sub=%d proto=%d\n".format(dev.vendorId, dev.productId, dev.deviceClass, dev.deviceSubclass, dev.deviceProtocol))
                sb.append("nome=").append(dev.productName).append(" prod=").append(dev.manufacturerName).append('\n')
                sb.append("permesso=").append(um.hasPermission(dev)).append('\n')
                for (i in 0 until dev.interfaceCount) {
                    val f = dev.getInterface(i)
                    sb.append("IF id=%d alt=%d class=%d sub=%d proto=%d ep=%d\n".format(f.id, f.alternateSetting, f.interfaceClass, f.interfaceSubclass, f.interfaceProtocol, f.endpointCount))
                    for (e in 0 until f.endpointCount) {
                        val ep = f.getEndpoint(e)
                        sb.append("   EP addr=0x%02X type=%d maxPacket=%d interval=%d dir=%d\n".format(ep.address, ep.type, ep.maxPacketSize, ep.interval, ep.direction))
                    }
                }
                val c = um.openDevice(dev)
                if (c != null) {
                    val raw = c.rawDescriptors
                    sb.append("RAW ").append(raw.size).append(" byte\n")
                    sb.append(raw.joinToString("") { "%02x".format(it) }).append('\n')
                    c.close()
                } else sb.append("openDevice: null\n")
            } catch (t: Throwable) {
                sb.append("errore ").append(t.javaClass.simpleName).append(": ").append(t.message).append('\n')
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

    /** Ultimi eventi della sonda, in giallo, per capire cosa succede. */
    private fun dbg(s: String) {
        dbgLines.addLast(s)
        while (dbgLines.size > 7) dbgLines.removeFirst()
        debugText.text = dbgLines.joinToString(" › ")
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
        sizes.forEach { labels.add("${it.width} x ${it.height}${sizeFmt(it)}") }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.res_title)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    prefs.resolution = ""
                    dbgLines.clear()
                    retryConnect()
                } else {
                    val sz = sizes[which - 1]
                    prefs.resolution = sizeKey(sz)
                    changeSize(h, sz)
                }
            }
            .show()
    }

    /** Cambia risoluzione/formato senza chiudere la sonda: stop, setPreviewSize, start. */
    private fun changeSize(h: CameraHelper, sz: Size) {
        try {
            h.stopPreview()
            h.javaClass.getMethod("setPreviewSize", Size::class.java).invoke(h, tuneSize(sz))
            h.startPreview()
            previewW = sz.width
            previewH = sz.height
            btnRes.text = "${sz.width}x${sz.height}${sizeFmt(sz)} ▾"
            layoutPreview()
            dbg("size $sz")
        } catch (e: Throwable) {
            dbg("errore size: ${e.message ?: e.cause?.message}")
            pendingReopen = sz
            try {
                h.closeCamera()
            } catch (_: Exception) {
                pendingReopen = null
            }
        }
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
            btnRes.text = "${cur.width}x${cur.height}${sizeFmt(cur)} ▾"
            layoutPreview()
        }
        dbg("preview $cur")
        try {
            h.startPreview()
            dbg("startPreview ok")
        } catch (e: Exception) {
            dbg("errore startPreview: ${e.message}")
        }
        attachSurfaceIfReady()
        hideStatus()
        dbg("frame cb: " + FrameProbe.attach(h) { onRawFrame(it) })

        // tasto fisico della sonda (evento UVC)
        val btnOk = prefs.optButton && UvcExtras.registerButton(h) { _, state ->
            if (state != 0) runOnUiThread {
                Toast.makeText(this, R.string.msg_probe_button, Toast.LENGTH_SHORT).show()
                takePhoto()
            }
        }
        lastKeyText.text = when {
            !prefs.optButton -> "Tasto sonda UVC: spento (prova)"
            btnOk -> getString(R.string.key_uvc_listening)
            else -> getString(R.string.key_only_keyboard)
        }

        // slider luminosità, solo se la sonda espone il controllo UVC
        brightness = if (prefs.optBrightness) UvcExtras.brightness(h) else null
        val b = brightness
        if (b != null) {
            brightnessSeek.max = b.max - b.min
            brightnessSeek.progress = (b.get() - b.min).coerceIn(0, b.max - b.min)
            brightnessRow.visibility = View.VISIBLE
        } else {
            brightnessRow.visibility = View.GONE
        }
    }

    /** Fotogramma grezzo: mostra cosa arriva (JPEG o YUYV) e, se leggibile, lo disegna da sé. */
    private fun onRawFrame(buf: java.nio.ByteBuffer) {
        if (grabLeft > 0) {
            val g = buf.duplicate()
            g.rewind()
            val arr = ByteArray(g.remaining())
            g.get(arr)
            var done = false
            synchronized(grabbed) {
                grabbed.add(arr)
                grabLeft--
                done = grabLeft == 0
            }
            if (done) {
                saveRawFrames()
                saveApiDump()
            }
        }
        mergeFrame(buf)
        val now = SystemClock.uptimeMillis()
        fpsCount++
        if (now - fpsT0 >= 1000) {
            fpsVal = (fpsCount * 1000L / (now - fpsT0).coerceAtLeast(1)).toInt()
            fpsCount = 0
            fpsT0 = now
        }
        if (frameBusy || now - lastDecode < 90) return
        frameBusy = true
        lastDecode = now
        try {
            val d = buf.duplicate()
            val len = d.remaining()
            val n = minOf(len, 8)
            val head = ByteArray(n)
            d.get(head)
            d.rewind()
            val w = previewW
            val h = previewH
            var bmp: Bitmap? = null
            var kind = "?"
            if (n >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte()) {
                val arr = ByteArray(len)
                d.get(arr)
                bmp = BitmapFactory.decodeByteArray(arr, 0, len)
                kind = "JPEG"
            } else if (w > 0 && h > 0 && len >= w * h * 2) {
                val arr = accum ?: ByteArray(w * h * 2)
                val px = FrameProbe.yuyvToArgb(arr, w, h)
                bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                lastBmp = bmp
                kind = "YUYV"
            }
            val hex = head.joinToString(" ") { "%02X".format(it) }
            val showInfo = now - lastInfo > 700
            if (showInfo) lastInfo = now
            val b = bmp
            runOnUiThread {
                if (showInfo) lastKeyText.text = "Frame $kind · $fpsVal fps · persi $lossPct%"
                if (b != null) {
                    frameView.setImageBitmap(b)
                    frameView.visibility = View.VISIBLE
                }
            }
        } catch (t: Throwable) {
            runOnUiThread { lastKeyText.text = "Frame errore: ${t.javaClass.simpleName}" }
        } finally {
            frameBusy = false
        }
    }

    /**
     * La sonda non rimanda le parti dell'immagine che non sono cambiate: la libreria le riempie
     * con un colore fisso (54 59 54 4E). Qui quelle parti vengono ignorate e al loro posto si
     * tiene l'immagine precedente, così l'immagine si ricompone nel tempo.
     */
    private fun mergeFrame(buf: java.nio.ByteBuffer) {
        val w = previewW
        val h = previewH
        if (w <= 0 || h <= 0) return
        val n = w * h * 2
        val d = buf.duplicate()
        d.rewind()
        if (d.remaining() < n) return
        var src = scratch
        if (src == null || src.size != n) {
            src = ByteArray(n)
            scratch = src
        }
        d.get(src, 0, n)
        if (src[0].toInt() == 0xFF.toByte().toInt() && src[1].toInt() == 0xD8.toByte().toInt()) return
        var acc = accum
        if (acc == null || acc.size != n) {
            acc = ByteArray(n) { (if (it % 2 == 0) 16 else 128).toByte() }
            accum = acc
        }
        var miss = 0
        var i = 0
        while (i + 3 < n) {
            if (src[i].toInt() == 0x54 && src[i + 1].toInt() == 0x59 &&
                src[i + 2].toInt() == 0x54 && src[i + 3].toInt() == 0x4E
            ) {
                var j = i + 4
                while (j + 3 < n && src[j].toInt() == 0x54 && src[j + 1].toInt() == 0x59 &&
                    src[j + 2].toInt() == 0x54 && src[j + 3].toInt() == 0x4E
                ) j += 4
                if ((j - i) >= 4) {
                    miss += j - i // parte non inviata: si tiene la precedente
                } else {
                    System.arraycopy(src, i, acc, i, j - i)
                }
                i = j
            } else {
                acc[i] = src[i]
                acc[i + 1] = src[i + 1]
                acc[i + 2] = src[i + 2]
                acc[i + 3] = src[i + 3]
                i += 4
            }
        }
        lossPct = (miss * 100L / n).toInt()
    }

    /** Salva i fotogrammi grezzi in Download, per poterli analizzare. */
    private fun saveRawFrames() {
        Thread {
            try {
                val list = synchronized(grabbed) { ArrayList(grabbed) }
                val name = "frames_${previewW}x${previewH}_" +
                    java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date()) + ".raw"
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                    ?: throw IllegalStateException("insert")
                contentResolver.openOutputStream(uri)?.use { o -> list.forEach { o.write(it) } }
                runOnUiThread { Toast.makeText(this, "Salvato in Download: $name", Toast.LENGTH_LONG).show() }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "Errore salvataggio: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** Elenco funzioni della libreria (per sapere quali regolazioni di banda esistono). */
    private fun saveApiDump() {
        Thread {
            try {
                val sb = StringBuilder()
                val names = listOf(
                    "com.serenegiant.usb.UVCParam", "com.herohan.uvcapp.CameraHelper",
                    "com.herohan.uvcapp.ICameraHelper", "com.serenegiant.usb.Size",
                    "com.serenegiant.usb.UVCCamera"
                )
                for (n in names) {
                    sb.append("== ").append(n).append('\n')
                    try {
                        val c = Class.forName(n)
                        c.declaredFields.forEach {
                            sb.append("F ").append(it.type.simpleName).append(' ').append(it.name)
                            if (java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType) {
                                try {
                                    it.isAccessible = true
                                    sb.append(" = ").append(it.getInt(null))
                                } catch (_: Throwable) {
                                }
                            }
                            sb.append('\n')
                        }
                        c.constructors.forEach { sb.append("C ").append(it.toString()).append('\n') }
                        c.methods.filter { it.declaringClass != Any::class.java }
                            .forEach { sb.append("M ").append(it.toString()).append('\n') }
                    } catch (t: Throwable) {
                        sb.append("non trovata: ").append(t.javaClass.simpleName).append('\n')
                    }
                }
                try {
                    val hh = helper
                    sb.append("== SONDA\n")
                    sb.append("formati: ").append(hh?.supportedFormatList?.toString()).append('\n')
                    sb.append("sizes: ").append(hh?.supportedSizeList?.joinToString(" | ") { it.toString() + " fps" + it.fpsList }).append('\n')
                } catch (t: Throwable) {
                    sb.append("sonda errore ").append(t.javaClass.simpleName).append('\n')
                }
                val name = "api_libreria2.txt"
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Download")
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
            } catch (_: Throwable) {
            }
        }.start()
    }

    private fun onCameraClosed() {
        accum = null
        runOnUiThread { frameView.visibility = View.GONE }
        if (surfaceAdded) {
            try {
                surface?.let { helper?.removeSurface(it) }
            } catch (_: Exception) {
            }
        }
        cameraOpened = false
        surfaceAdded = false
        // chiusura non voluta (non è un cambio di risoluzione): avvisa
        if (pendingReopen == null && !pendingDefault && selectedDevice != null && helper != null) {
            showStatus(R.string.err_closed) { retryConnect() }
        }
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
        showStatus(R.string.status_connect)
    }

    private fun attachSurfaceIfReady() {
        val sf = surface ?: return
        val h = helper ?: return
        if (cameraOpened && !surfaceAdded) {
            try {
                h.addSurface(sf, false)
                surfaceAdded = true
                dbg("surface ok")
            } catch (e: Exception) {
                dbg("errore surface: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------------- anteprima (rotazione/specchio/zoom)

    private fun updateRotateMirrorLabels() {
        btnRotate.text = "↻ ${prefs.rotation}°"
        btnMirror.text = if (prefs.mirror) "⇋ SÌ" else "⇋ NO"
    }

    private fun layoutPreview() {
        if (!prefs.optFit) {
            // modalità prova: superficie a tutto schermo, senza trasformazioni (come la demo)
            val cur = previewView.layoutParams
            val full = FrameLayout.LayoutParams.MATCH_PARENT
            if (cur.width != full || cur.height != full) {
                cur.width = full
                cur.height = full
                previewView.layoutParams = cur
            }
            applyTransform()
            return
        }
        val cw = container.width
        val ch = container.height
        if (cw == 0 || ch == 0 || previewW == 0 || previewH == 0) return
        val odd = prefs.rotation == 90 || prefs.rotation == 270
        val s = if (odd) min(cw.toFloat() / previewH, ch.toFloat() / previewW)
        else min(cw.toFloat() / previewW, ch.toFloat() / previewH)
        val vw = (previewW * s).toInt()
        val vh = (previewH * s).toInt()
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

    private fun applyTransform() {
        if (!prefs.optFit) {
            previewView.rotation = 0f
            previewView.scaleX = 1f
            previewView.scaleY = 1f
            return
        }
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
        val merged = lastBmp
        if (merged != null && frameView.visibility == View.VISIBLE) {
            Thread {
                val okSave = try {
                    java.io.FileOutputStream(tmp).use { merged.compress(Bitmap.CompressFormat.JPEG, 100, it) }
                } catch (_: Exception) {
                    false
                }
                runOnUiThread {
                    if (okSave) saveAsync(tmp, name, false, rot, mir)
                    else Toast.makeText(this, R.string.msg_save_error, Toast.LENGTH_LONG).show()
                }
            }.start()
            return
        }
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
