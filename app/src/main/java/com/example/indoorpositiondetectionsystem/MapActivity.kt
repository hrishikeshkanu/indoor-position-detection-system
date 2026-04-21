package com.example.indoorpositiondetectionsystem

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MapActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private var wifiReceiver: BroadcastReceiver? = null

    private lateinit var mapView: MapView
    private lateinit var mapDetectedLab: TextView

    private val routerMap = mapOf(
        "00:0A:EB:13:09:69" to "LAB 1",
        "EC:75:0C:15:0F:40" to "LAB 2",
        "40:3F:8C:E0:72:36" to "LAB 3",
        "CC:2D:21:57:F5:48" to "LAB 4"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map)

        mapView        = findViewById(R.id.mapView)
        mapDetectedLab = findViewById(R.id.mapDetectedLab)

        // Back button
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        // Show distances passed from MainActivity immediately
        @Suppress("UNCHECKED_CAST")
        val initialDistances = intent.getSerializableExtra("distances") as? HashMap<String, Double>
        initialDistances?.let { mapView.updateDistances(it) }

        // Then start live scanning on this screen too
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startScan()
        }
    }

    private fun calculateDistance(rssi: Int): Double {
        if (rssi == -100) return 99.0
        val txPower = -40
        val n = 3.0
        return Math.pow(10.0, (txPower - rssi) / (10.0 * n))
    }

    private fun startScan() {
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

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

                val distances = mapOf(
                    "LAB 1" to calculateDistance(r1),
                    "LAB 2" to calculateDistance(r2),
                    "LAB 3" to calculateDistance(r3),
                    "LAB 4" to calculateDistance(r4)
                )

                mapView.updateDistances(distances)

                val best = listOf("LAB 1" to r1, "LAB 2" to r2, "LAB 3" to r3, "LAB 4" to r4)
                    .filter { it.second > -100 }
                    .maxByOrNull { it.second }

                mapDetectedLab.text = if (best != null) "Near: ${best.first}" else "Scanning..."

                @Suppress("DEPRECATION")
                wifiManager.startScan()
            }
        }

        registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        @Suppress("DEPRECATION")
        wifiManager.startScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            wifiReceiver = null
        }
    }
}