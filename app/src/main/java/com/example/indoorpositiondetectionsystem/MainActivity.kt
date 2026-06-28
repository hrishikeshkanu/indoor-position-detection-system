package com.example.indoorpositiondetectionsystem

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.max
import kotlin.math.min
class MainActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private var wifiReceiver: BroadcastReceiver? = null

    // Lab row views
    private lateinit var signalText1: TextView
    private lateinit var signalText2: TextView
    private lateinit var signalText3: TextView
    private lateinit var signalText4: TextView
    private lateinit var commentText1: TextView
    private lateinit var commentText2: TextView
    private lateinit var commentText3: TextView
    private lateinit var commentText4: TextView

    private lateinit var detectedLabText: TextView
    private lateinit var btnRefresh: Button
    private lateinit var btnViewMap: Button

    // Graph
    private lateinit var signalGraph: SignalGraphView

    // Detection Time TextViews
    private lateinit var txtCurrentTime: TextView
    private lateinit var txtAverageTime: TextView
    private lateinit var txtFastestTime: TextView
    private lateinit var txtSlowestTime: TextView

    // Detection Time Variables
    private var scanStartTime = 0L
    private var totalDetectionTime = 0L
    private var detectionCount = 0

    private var fastestDetection = Long.MAX_VALUE
    private var slowestDetection = 0L

    // Kept as class property so View Map button can pass it to MapActivity
    private var currentDistances: Map<String, Double> = emptyMap()
    private var currentRssi: Map<String, Int> = emptyMap()

    private val routerMap = mapOf(
        "8C:86:DD:41:F3:73" to "LAB 1",
        "EC:75:0C:15:0F:40" to "LAB 2",
        "40:3F:8C:E0:72:36" to "LAB 3",
        "CC:2D:21:1F:4F:DO" to "LAB 4"
    )

    // Auto-refresh every 25 seconds
    private val autoRefreshHandler = Handler(Looper.getMainLooper())
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            @Suppress("DEPRECATION")
            wifiManager.startScan()
            autoRefreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        signalText1  = findViewById(R.id.signalText1)
        signalText2  = findViewById(R.id.signalText2)
        signalText3  = findViewById(R.id.signalText3)
        signalText4  = findViewById(R.id.signalText4)
        commentText1 = findViewById(R.id.commentText1)
        commentText2 = findViewById(R.id.commentText2)
        commentText3 = findViewById(R.id.commentText3)
        commentText4 = findViewById(R.id.commentText4)
        detectedLabText = findViewById(R.id.detectedLabText)
        btnRefresh   = findViewById(R.id.btnRefresh)
        btnViewMap   = findViewById(R.id.btnViewMap)

        signalGraph = findViewById(R.id.signalGraph)

        txtCurrentTime = findViewById(R.id.txtCurrentTime)
        txtAverageTime = findViewById(R.id.txtAverageTime)
        txtFastestTime = findViewById(R.id.txtFastestTime)
        txtSlowestTime = findViewById(R.id.txtSlowestTime)

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        btnRefresh.setOnClickListener {
            // Cancel current auto-refresh cycle, trigger scan immediately, restart timer
            autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
            @Suppress("DEPRECATION")
            scanStartTime = System.currentTimeMillis()

            @Suppress("DEPRECATION")
            wifiManager.startScan()
            autoRefreshHandler.postDelayed(autoRefreshRunnable, REFRESH_INTERVAL_MS)
            Toast.makeText(this, "Refreshing signals...", Toast.LENGTH_SHORT).show()
        }

        btnViewMap.setOnClickListener {
            val intent = Intent(this, MapActivity::class.java)
            // Pass current distances so map shows immediately on open
            intent.putExtra("distances", HashMap(currentDistances))
            startActivity(intent)
        }

        checkPermission()
    }

    override fun onResume() {
        super.onResume()
        // Restart auto-refresh when coming back from map page
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        autoRefreshHandler.postDelayed(autoRefreshRunnable, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        super.onPause()
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            wifiReceiver = null
        }
    }

    // ── Distance & quality helpers ──────────────────────────────────────────

    private fun calculateDistance(rssi: Int): Double {
        if (rssi == -100) return 99.0
        val txPower = -40
        val n = 3.0
        return Math.pow(10.0, (txPower - rssi) / (10.0 * n))
    }

    /** Returns label text and color for a given RSSI value */
    private fun signalQuality(rssi: Int): Pair<String, Int> = when {
        rssi == -100      -> Pair("Out of Range",  Color.parseColor("#FF4444"))
        rssi >= -60       -> Pair("Strong",        Color.parseColor("#00FF9C"))
        rssi >= -70       -> Pair("Good",          Color.parseColor("#00E5FF"))
        rssi >= -80       -> Pair("Weak",          Color.parseColor("#FFB300"))
        else              -> Pair("Very Weak",     Color.parseColor("#FF6B35"))
    }

    // ── WiFi Scanning ────────────────────────────────────────────────────────

    private fun startScan() {
        wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {

                @Suppress("DEPRECATION")
                val results = wifiManager.scanResults

                var r1 = -100; var r2 = -100; var r3 = -100; var r4 = -100

                for (r in results) {
                    val bssid = r.BSSID.uppercase()
                    when (routerMap[bssid]) {
                        "LAB 1" -> r1 = maxOf(r1, r.level)
                        "LAB 2" -> r2 = maxOf(r2, r.level)
                        "LAB 3" -> r3 = maxOf(r3, r.level)
                        "LAB 4" -> r4 = maxOf(r4, r.level)
                    }
                }

                // Update signal text
                signalText1.text = if (r1 > -100) "$r1 dBm" else "– dBm"
                signalText2.text = if (r2 > -100) "$r2 dBm" else "– dBm"
                signalText3.text = if (r3 > -100) "$r3 dBm" else "– dBm"
                signalText4.text = if (r4 > -100) "$r4 dBm" else "– dBm"

                // Update quality comments
                fun applyQuality(tv: TextView, rssi: Int) {
                    val (label, color) = signalQuality(rssi)
                    tv.text = label
                    tv.setTextColor(color)
                }
                applyQuality(commentText1, r1)
                applyQuality(commentText2, r2)
                applyQuality(commentText3, r3)
                applyQuality(commentText4, r4)

                // Store for map navigation
                currentDistances = mapOf(
                    "LAB 1" to calculateDistance(r1),
                    "LAB 2" to calculateDistance(r2),
                    "LAB 3" to calculateDistance(r3),
                    "LAB 4" to calculateDistance(r4)
                )
                currentRssi = mapOf(
                    "LAB 1" to r1, "LAB 2" to r2,
                    "LAB 3" to r3, "LAB 4" to r4
                )
                signalGraph.updateSignals(currentRssi)

                // Detected lab = strongest signal
                val best = listOf("LAB 1" to r1, "LAB 2" to r2, "LAB 3" to r3, "LAB 4" to r4)
                    .filter { it.second > -100 }
                    .maxByOrNull { it.second }

                detectedLabText.text = if (best != null) best.first else "Unknown"

                // ---------- Detection Time Statistics ----------

// Current scan completed
                val detectionTime =
                    System.currentTimeMillis() - scanStartTime

// Add to total
                totalDetectionTime += detectionTime

// Number of scans
                detectionCount++

// Update fastest
                if (detectionTime < fastestDetection)
                    fastestDetection = detectionTime

// Update slowest
                if (detectionTime > slowestDetection)
                    slowestDetection = detectionTime

// Calculate average
                val averageDetection =
                    totalDetectionTime / detectionCount

// Update UI
                txtCurrentTime.text =
                    "$detectionTime ms"

                txtAverageTime.text =
                    "$averageDetection ms"

                txtFastestTime.text =
                    "$fastestDetection ms"

                txtSlowestTime.text =
                    "$slowestDetection ms"
            }
        }

        registerReceiver(
            wifiReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )

        // Save scan start time
        scanStartTime = System.currentTimeMillis()

        @Suppress("DEPRECATION")
        wifiManager.startScan()

// Start auto-refresh loop
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable)
        autoRefreshHandler.postDelayed(autoRefreshRunnable, REFRESH_INTERVAL_MS)
    }

    // ── Permission ──────────────────────────────────────────────────────────

    private fun checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                PERMISSION_REQUEST_CODE
            )
        } else {
            startScan()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScan()
            } else {
                Toast.makeText(this, "Location permission required for WiFi scanning.", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1
        private const val REFRESH_INTERVAL_MS     = 25_000L
    }
}