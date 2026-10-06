package com.example.indoorpositiondetectionsystem

object RouterConfig {
    val routerMap: Map<String, String> = mapOf(
        "3C:84:6A:B5:DE:24" to "LAB 1",
        "3C:78:95:31:6C:54" to "LAB 2",
        "20:23:51:77:56:E6" to "LAB 3",
        "40:3F:8C:E0:72:36" to "LAB 4"
    )

    fun calculateDistance(rssi: Int): Double {
        if (rssi == -100) return 99.0
        val txPower = -40
        val n = 3.0
        return Math.pow(10.0, (txPower - rssi) / (10.0 * n))
    }
}
