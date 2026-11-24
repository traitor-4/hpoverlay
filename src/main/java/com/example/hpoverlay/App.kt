package com.example.hpoverlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Open once: grants permissions and starts. Open again while running: stops. */
class MainActivity : Activity() {
    private val REQ = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { begin() } catch (t: Throwable) { showError(t) }
    }

    private fun showError(t: Throwable) {
        val tv = TextView(this).apply {
            text = "ERROR (send a screenshot of this):\n\n" + Log.getStackTraceString(t)
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(24, 64, 24, 24)
        }
        setContentView(ScrollView(this).apply { addView(tv) })
    }

    private fun begin() {
        if (HpOverlayService.running) {
            stopService(Intent(this, HpOverlayService::class.java))
            finish(); return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            finish(); return   // grant it, then open the app again
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ)
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        try {
            if (req == REQ && res == RESULT_OK && data != null) {
                startForegroundService(
                    Intent(this, HpOverlayService::class.java)
                        .putExtra("code", res).putExtra("data", data))
            }
            finish()
        } catch (t: Throwable) { showError(t) }
    }
}

class HpOverlayService : Service() {

    companion object { var running = false }

    // ---- TUNE THESE ----------------------------------------------------
    // Search box (fractions of screen) where YOUR hero's HP bar appears.
    private val REGION = floatArrayOf(0.35f, 0.25f, 0.65f, 0.55f) // l, t, r, b
    // Pixel test for the filled (green) part of the bar.
    private fun isFill(r: Int, g: Int, b: Int) = g > 120 && g > r + 60 && g > b + 60
    private val INTERVAL_MS = 300L
    // --------------------------------------------------------------------

    private class Bar(val len: Int, val x: Int, val y: Int)

    private lateinit var wm: WindowManager
    private lateinit var hpLabel: TextView   // percentage drawn on the bar
    private lateinit var btn: TextView       // fixed corner button / status
    private lateinit var hpLp: WindowManager.LayoutParams
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var capW = 0
    private var capH = 0
    private var fullWidth = 0   // bar length in px at 100% HP
    private var lastLen = 0

    // ---- Low-HP kill counter ------------------------------------------
    // Box (fractions of screen) around YOUR kills number in the top-right
    // K / D / A row (the first number, next to the crossed swords).
    private val KILL_REGION = floatArrayOf(0.82f, 0.03f, 0.845f, 0.082f)
    private val LOW_HP_PCT = 10          // counts kills made below this HP %
    private val LOW_HP_WINDOW_MS = 5000L // kill counts if HP was low within this time
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private var lowKills = 0
    private var lowHpUntil = 0L
    private var lastKills = -1
    private var pendingKills = -1
    private var pendingCount = 0
    private var lastOcrAt = 0L
    private var ocrBusy = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try { doStart(intent) } catch (t: Throwable) {
            Toast.makeText(this, "Error: " + t.javaClass.simpleName + ": " + t.message,
                Toast.LENGTH_LONG).show()
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun doStart(intent: Intent?): Int {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("hp", "HP overlay", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "hp")
            .setContentTitle("HP overlay running")
            .setSmallIcon(android.R.drawable.ic_menu_view).build()
        startForeground(1, n,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent?.getIntExtra("code", 0) ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data") ?: return START_NOT_STICKY
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)
        }
        running = true
        showOverlays()
        handler.post(loop)
        return START_NOT_STICKY
    }

    private fun showOverlays() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1) Percentage text that sits on the HP bar (touches pass through)
        hpLabel = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            visibility = View.INVISIBLE
        }
        hpLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(hpLabel, hpLp)

        // 2) Small fixed button: tap it when your hero is at FULL HP
        btn = TextView(this).apply {
            text = "Tap at full HP"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setPadding(16, 6, 16, 6)
            setOnClickListener { if (lastLen > 0) fullWidth = lastLen }
            setOnLongClickListener { lowKills = 0; true }  // long-press = reset counter
        }
        val blp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 500; y = 10 }
        wm.addView(btn, blp)
    }

    private fun ensureCapture() {
        val b = wm.currentWindowMetrics.bounds
        if (b.width() == capW && b.height() == capH && reader != null) return
        capW = b.width(); capH = b.height()
        reader?.close()
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2)
        val dpi = resources.displayMetrics.densityDpi
        if (vd == null) {
            vd = projection?.createVirtualDisplay("hp", capW, capH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, handler)
        } else {
            vd?.resize(capW, capH, dpi)
            vd?.surface = reader!!.surface
        }
    }

    private val loop = object : Runnable {
        override fun run() {
            try {
                ensureCapture()
                reader?.acquireLatestImage()?.use { img -> update(findBar(img)); readKills(img) }
            } catch (e: Exception) { /* skip this frame */ }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    private var lastBarX = -1
    private var lastBarY = -1

    private fun showLabel(text: String) {
        hpLabel.text = text
        hpLabel.visibility = View.VISIBLE
        // Centre the text on the bar (bar's left edge is fixed, so use full width)
        hpLp.x = lastBarX + fullWidth / 2 - hpLabel.width / 2
        hpLp.y = lastBarY - hpLabel.height / 2
        wm.updateViewLayout(hpLabel, hpLp)
    }

    private fun update(bar: Bar?) {
        if (bar == null) {
            lastLen = 0
            if (fullWidth > 0 && lastBarX >= 0) {
                // Bar was seen before but no fill found: HP is almost empty
                lowHpUntil = SystemClock.uptimeMillis() + LOW_HP_WINDOW_MS
                setStatus("HP <5% (fill not found)")
                showLabel("<5%")
            } else {
                hpLabel.visibility = View.INVISIBLE
                setStatus("HP bar not found")
            }
            return
        }
        lastLen = bar.len
        lastBarX = bar.x
        lastBarY = bar.y
        if (fullWidth == 0) {
            hpLabel.visibility = View.INVISIBLE
            setStatus("Tap at full HP")
            return
        }
        val pct = (bar.len * 100 / fullWidth).coerceIn(0, 100)
        if (pct < LOW_HP_PCT) lowHpUntil = SystemClock.uptimeMillis() + LOW_HP_WINDOW_MS
        setStatus("HP $pct% (${bar.len}/$fullWidth)")
        showLabel("$pct%")
    }

    private fun setStatus(text: String) {
        btn.text = "$text | Low-HP kills: $lowKills"
    }

    /** Reads the kills number (about once a second) with on-device text recognition. */
    private fun readKills(img: Image) {
        val now = SystemClock.uptimeMillis()
        if (ocrBusy || now - lastOcrAt < 800) return
        lastOcrAt = now
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val x0 = (img.width * KILL_REGION[0]).toInt()
        val y0 = (img.height * KILL_REGION[1]).toInt()
        val w = (img.width * KILL_REGION[2]).toInt() - x0
        val h = (img.height * KILL_REGION[3]).toInt() - y0
        if (w <= 0 || h <= 0) return
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y0 + y) * rs + (x0 + x) * 4
            px[y * w + x] = Color.rgb(
                buf.get(o).toInt() and 0xFF,
                buf.get(o + 1).toInt() and 0xFF,
                buf.get(o + 2).toInt() and 0xFF)
        }
        val small = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        val big = Bitmap.createScaledBitmap(small, w * 5, h * 5, true)
        ocrBusy = true
        recognizer.process(InputImage.fromBitmap(big, 0))
            .addOnSuccessListener { res ->
                ocrBusy = false
                val n = Regex("\\d+").find(res.text)?.value?.toIntOrNull()
                if (n != null && n in 0..99) onKills(n)
            }
            .addOnFailureListener { ocrBusy = false }
    }

    private fun onKills(n: Int) {
        // accept a number only after two identical readings in a row
        if (n == pendingKills) pendingCount++ else { pendingKills = n; pendingCount = 1 }
        if (pendingCount < 2) return
        if (lastKills < 0) { lastKills = n; return }
        if (n < lastKills) {            // kills dropped = a new match started
            lowKills = 0; lastKills = n; return
        }
        if (n > lastKills) {
            if (SystemClock.uptimeMillis() <= lowHpUntil) lowKills += n - lastKills
            lastKills = n
        }
    }

    /** Finds the longest horizontal run of fill-coloured pixels inside REGION. */
    private fun findBar(img: Image): Bar? {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val x0 = (img.width * REGION[0]).toInt()
        val y0 = (img.height * REGION[1]).toInt()
        val x1 = (img.width * REGION[2]).toInt()
        val y1 = (img.height * REGION[3]).toInt()
        var bestLen = 0; var bestX = 0; var bestY = 0
        for (y in y0 until y1 step 2) {
            var run = 0; var gap = 0; var start = 0
            for (x in x0 until x1) {
                val o = y * rs + x * 4
                val r = buf.get(o).toInt() and 0xFF
                val g = buf.get(o + 1).toInt() and 0xFF
                val b = buf.get(o + 2).toInt() and 0xFF
                if (isFill(r, g, b)) {
                    if (run == 0) start = x
                    run += 1 + gap; gap = 0
                    if (run > bestLen) { bestLen = run; bestX = start; bestY = y }
                } else if (run > 0 && ++gap > 8) { run = 0; gap = 0 }
            }
        }
        return if (bestLen >= 5) Bar(bestLen, bestX, bestY) else null
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        vd?.release(); reader?.close(); projection?.stop()
        if (::hpLabel.isInitialized) wm.removeView(hpLabel)
        if (::btn.isInitialized) wm.removeView(btn)
        super.onDestroy()
    }
}
