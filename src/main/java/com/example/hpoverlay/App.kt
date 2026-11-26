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
    private val REGION = floatArrayOf(0.44f, 0.33f, 0.58f, 0.44f) // l, t, r, b
    // Pixel test for the filled (green) part of the bar.
    private fun isFill(r: Int, g: Int, b: Int) =
        g > 140 && g > r + 70 && g > b + 70 && Math.abs(r - b) < 50
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
    private var lowerCount = 0
    private var lastOcrAt = 0L
    private var ocrBusy = false
    private var lastKillsSeenAt = 0L
    private var inMatch = false
    private val MATCH_TIMEOUT_MS = 8000L   // no kills number for this long = match over
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
            checkMatchOver()
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
        // A visible HP bar also proves we are in a match
        if (bar != null && bar.len >= 20) {
            lastKillsSeenAt = SystemClock.uptimeMillis()
            inMatch = true
        }
        if (!inMatch) {                 // lobby / menus: show nothing
            lastLen = 0
            hpLabel.visibility = View.INVISIBLE
            setStatus("Waiting for match")
            return
        }
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

    /** If the kills number has not been readable for a while, the match is over. */
    private fun checkMatchOver() {
        if (inMatch && SystemClock.uptimeMillis() - lastKillsSeenAt > MATCH_TIMEOUT_MS) {
            inMatch = false
            lowKills = 0
            lastKills = -1
            pendingKills = -1
            pendingCount = 0
            lastBarX = -1
            lastBarY = -1
            lowHpUntil = 0L
            lastLen = 0
            hpLabel.visibility = View.INVISIBLE
            setStatus("Waiting for match")
        }
    }

    private fun setStatus(text: String) {
        btn.text = "$text | Low-HP kills: $lowKills | K:$lastKills"
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
        val raw = IntArray(w * h)
        val bin = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val o = (y0 + y) * rs + (x0 + x) * 4
            val r = buf.get(o).toInt() and 0xFF
            val g = buf.get(o + 1).toInt() and 0xFF
            val b = buf.get(o + 2).toInt() and 0xFF
            raw[y * w + x] = Color.rgb(r, g, b)
            // bright (white) digit pixels -> black, everything else -> white
            bin[y * w + x] = if ((r * 3 + g * 6 + b) / 10 > 170) Color.BLACK else Color.WHITE
        }
        val binBmp = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(bin, w, h, Bitmap.Config.ARGB_8888), w * 5, h * 5, true)
        val rawBmp = Bitmap.createScaledBitmap(
            Bitmap.createBitmap(raw, w, h, Bitmap.Config.ARGB_8888), w * 5, h * 5, true)
        ocrBusy = true
        // Try the high-contrast image first; if no number is found, try the original.
        recognizer.process(InputImage.fromBitmap(binBmp, 0))
            .addOnSuccessListener { res ->
                val n = parseKills(res.text)
                if (n != null) { ocrBusy = false; gotKills(n) }
                else recognizer.process(InputImage.fromBitmap(rawBmp, 0))
                    .addOnSuccessListener { r2 ->
                        ocrBusy = false
                        parseKills(r2.text)?.let { gotKills(it) }
                    }
                    .addOnFailureListener { ocrBusy = false }
            }
            .addOnFailureListener { ocrBusy = false }
    }

    /** Turns look-alike letters into digits (a lone "1" is often read as I, l or |). */
    private fun parseKills(text: String): Int? {
        val t = text.map {
            when (it) {
                'I', 'l', '|', '!' -> '1'
                'O', 'o', 'D' -> '0'
                'S' -> '5'
                'B' -> '8'
                'g' -> '9'
                else -> it
            }
        }.joinToString("")
        val n = Regex("\\d+").find(t)?.value?.toIntOrNull() ?: return null
        return if (n in 0..99) n else null
    }

    private fun gotKills(n: Int) {
        lastKillsSeenAt = SystemClock.uptimeMillis()
        inMatch = true
        onKills(n)
    }

    private fun onKills(n: Int) {
        // accept a number only after two identical readings in a row
        if (n == pendingKills) pendingCount++ else { pendingKills = n; pendingCount = 1 }
        if (pendingCount < 2) return
        if (lastKills < 0) { lastKills = n; return }
        if (n < lastKills) {
            // Probably a misread (e.g. 6 read as 0). Never reset the counter here;
            // only accept the lower number if it keeps showing up.
            if (++lowerCount >= 4) { lastKills = n; lowerCount = 0 }
            return
        }
        lowerCount = 0
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
        var bestScore = Int.MIN_VALUE
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
                    // prefer the bar near where we last saw it (ignores grass, minions, monsters)
                    val dist = if (lastBarX >= 0)
                        Math.abs(start - lastBarX) + Math.abs(y - lastBarY) else 0
                    val score = run - 2 * dist
                    if (score > bestScore) {
                        bestScore = score; bestLen = run; bestX = start; bestY = y
                    }
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
