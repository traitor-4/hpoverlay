
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import android.view.WindowManager
import android.widget.TextView

/** Open once: grants permissions and starts. Open again while running: stops. */
class MainActivity : Activity() {
    private val REQ = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        if (req == REQ && res == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, HpOverlayService::class.java)
                    .putExtra("code", res).putExtra("data", data))
        }
        finish()
    }
}

class HpOverlayService : Service() {

    companion object { var running = false }

    // ---- TUNE THESE ----------------------------------------------------
    // Search box (fractions of screen) where YOUR hero's HP bar appears.
    private val REGION = floatArrayOf(0.35f, 0.25f, 0.65f, 0.55f) // l, t, r, b
    // Pixel test for the filled (green) part of the bar.
    private fun isFill(r: Int, g: Int, b: Int) = g > 180 && r < 130 && b < 130
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
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                reader?.acquireLatestImage()?.use { img -> update(findBar(img)) }
            } catch (e: Exception) { /* skip this frame */ }
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    private fun update(bar: Bar?) {
        if (bar == null) {
            lastLen = 0
            hpLabel.visibility = View.INVISIBLE
            btn.text = "HP bar not found"
            return
        }
        lastLen = bar.len
        if (fullWidth in 1 until bar.len) fullWidth = bar.len  // bar longer than calibrated
        if (fullWidth == 0) {
            hpLabel.visibility = View.INVISIBLE
            btn.text = "Tap at full HP"
            return
        }
        val pct = (bar.len * 100 / fullWidth).coerceIn(0, 100)
        btn.text = "HP $pct%"
        hpLabel.text = "$pct%"
        hpLabel.visibility = View.VISIBLE
        // Centre the text on the bar (bar's left edge is fixed, so use full width)
        hpLp.x = bar.x + fullWidth / 2 - hpLabel.width / 2
        hpLp.y = bar.y - hpLabel.height / 2
        wm.updateViewLayout(hpLabel, hpLp)
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
                } else if (run > 0 && ++gap > 2) { run = 0; gap = 0 }
            }
        }
        return if (bestLen >= 20) Bar(bestLen, bestX, bestY) else null
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
