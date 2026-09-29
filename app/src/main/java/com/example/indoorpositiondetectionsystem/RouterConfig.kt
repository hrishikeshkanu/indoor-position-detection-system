package com.example.indoorpositiondetectionsystem

object RouterConfig {
    val routerMap: Map<String, String> = mapOf(
        "54:AF:97:28:6B:79" to "LAB 1",
        "54:AF:97:92:94:37" to "LAB 2",
        "54:AF:97:92:20:5A" to "LAB 3",
        "54:AF:97:28:6B:78" to "LAB 4"
    )

    fun calculateDistance(rssi: Int): Double {
        if (rssi == -100) return 99.0
        val txPower = -40
        val n = 3.0
        return Math.pow(10.0, (txPower - rssi) / (10.0 * n))
    }
}
