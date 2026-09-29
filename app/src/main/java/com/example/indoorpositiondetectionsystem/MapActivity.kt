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
import com.google.firebase.auth.FirebaseAuth

class MapActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private var wifiReceiver: BroadcastReceiver? = null
    private var localSelfMarker: MapView.UserMarker? = null
    private var presenceMarkers: List<MapView.UserMarker>? = null

    private lateinit var mapView: MapView
    private lateinit var mapDetectedLab: TextView

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
        initialDistances?.let { passedDistances ->
            val labDistances = RouterConfig.routerMap.values.distinct().associateWith { lab ->
                passedDistances[lab] ?: 99.0
            }
            localSelfMarker = MapView.UserMarker(selfDisplayName(), labDistances, true)
            updateDisplayedUsers()
        }

        // Then start live scanning on this screen too
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startScan()
        }

        PresenceRepository.startListening { users ->
            presenceMarkers = users.map { user ->
                MapView.UserMarker(user.name, user.distances, user.isSelf)
            }
            updateDisplayedUsers()
        }
    }

    private fun selfDisplayName(): String {
        val currentUser = FirebaseAuth.getInstance().currentUser
        return currentUser?.displayName?.takeIf { it.isNotBlank() }
            ?: currentUser?.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
            ?: "Me"
    }

    private fun updateDisplayedUsers() {
        val presenceUsers = presenceMarkers
        val presenceSelf = presenceUsers?.firstOrNull { it.isSelf }
        val selfMarker = localSelfMarker?.let { marker ->
            marker.copy(name = presenceSelf?.name ?: marker.name)
        } ?: presenceSelf
        val otherUsers = presenceUsers.orEmpty().filterNot { it.isSelf }
        mapView.updateUsers(listOfNotNull(selfMarker) + otherUsers)
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
                    when (RouterConfig.routerMap[bssid]) {
                        "LAB 1" -> r1 = maxOf(r1, r.level)
                        "LAB 2" -> r2 = maxOf(r2, r.level)
                        "LAB 3" -> r3 = maxOf(r3, r.level)
                        "LAB 4" -> r4 = maxOf(r4, r.level)
                    }
                }

                val distances = mapOf(
                    "LAB 1" to RouterConfig.calculateDistance(r1),
                    "LAB 2" to RouterConfig.calculateDistance(r2),
                    "LAB 3" to RouterConfig.calculateDistance(r3),
                    "LAB 4" to RouterConfig.calculateDistance(r4)
                )

                localSelfMarker = MapView.UserMarker(
                    presenceMarkers?.firstOrNull { it.isSelf }?.name
                        ?: localSelfMarker?.name
                        ?: selfDisplayName(),
                    distances,
                    true
                )
                updateDisplayedUsers()

                val best = listOf("LAB 1" to r1, "LAB 2" to r2, "LAB 3" to r3, "LAB 4" to r4)
                    .filter { it.second > -100 }
                    .maxByOrNull { it.second }

                mapDetectedLab.text = if (best != null) "Near: ${best.first}" else "Scanning..."

                val rssiMap = mapOf(
                    "LAB 1" to r1,
                    "LAB 2" to r2,
                    "LAB 3" to r3,
                    "LAB 4" to r4
                )
                PresenceRepository.publish(best?.first ?: "Unknown", rssiMap, distances)

                @Suppress("DEPRECATION")
                wifiManager.startScan()
            }
        }

        registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        @Suppress("DEPRECATION")
        wifiManager.startScan()
    }

    override fun onStop() {
        PresenceRepository.stopListening()
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            wifiReceiver = null
        }
    }
}