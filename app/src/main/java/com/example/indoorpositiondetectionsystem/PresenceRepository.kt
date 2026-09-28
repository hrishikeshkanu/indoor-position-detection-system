package com.example.indoorpositiondetectionsystem

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

data class PresenceUser(
    val uid: String,
    val name: String,
    val detectedLab: String,
    val signals: Map<String, Int>,
    val distances: Map<String, Double>,
    val timestamp: Long,
    val isSelf: Boolean
)

object PresenceRepository {

    private const val STALE_TIMEOUT_MS = 90_000L
    private const val STALE_REFRESH_MS = 15_000L

    fun interface PresenceListener {
        fun onPresenceChanged(users: List<PresenceUser>)
    }

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference
    private val refreshHandler = Handler(Looper.getMainLooper())

    private var cachedName: String? = null
    private var hasLoadedName = false
    private var disconnectHookRegistered = false

    private var activeListener: PresenceListener? = null
    private var presenceRef: DatabaseReference? = null
    private var presenceListener: ValueEventListener? = null
    private var serverTimeOffsetRef: DatabaseReference? = null
    private var serverTimeOffsetListener: ValueEventListener? = null
    private var serverTimeOffsetMs: Long = 0L
    private var lastRawUsers: List<PresenceUser> = emptyList()

    private val staleRefreshRunnable = object : Runnable {
        override fun run() {
            val listener = activeListener ?: return
            val currentTime = System.currentTimeMillis() + serverTimeOffsetMs
            val filtered = lastRawUsers.filter { currentTime - it.timestamp <= STALE_TIMEOUT_MS }
                .sortedWith(compareBy<PresenceUser> { if (it.isSelf) 0 else 1 }
                    .thenBy { it.name.lowercase() })
            listener.onPresenceChanged(filtered)
            refreshHandler.postDelayed(this, STALE_REFRESH_MS)
        }
    }

    fun startListening(listener: PresenceListener) {
        stopListening()
        activeListener = listener

        presenceRef = database.child("presence")
        val newPresenceListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastRawUsers = parsePresenceSnapshot(snapshot)
                emitFilteredUsers()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w("PresenceRepository", "Presence listener cancelled", error.toException())
            }
        }
        presenceListener = newPresenceListener
        presenceRef?.addValueEventListener(newPresenceListener)

        serverTimeOffsetRef = database.child(".info").child("serverTimeOffset")
        val newServerTimeOffsetListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val offset = snapshot.getValue(Long::class.java)
                serverTimeOffsetMs = offset ?: 0L
                emitFilteredUsers()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w("PresenceRepository", "Server time offset listener cancelled", error.toException())
            }
        }
        serverTimeOffsetListener = newServerTimeOffsetListener
        serverTimeOffsetRef?.addValueEventListener(newServerTimeOffsetListener)

        refreshHandler.removeCallbacks(staleRefreshRunnable)
        refreshHandler.postDelayed(staleRefreshRunnable, STALE_REFRESH_MS)
    }

    fun stopListening() {
        activeListener = null
        refreshHandler.removeCallbacks(staleRefreshRunnable)

        presenceListener?.let { presenceRef?.removeEventListener(it) }
        serverTimeOffsetListener?.let { serverTimeOffsetRef?.removeEventListener(it) }

        presenceRef = null
        presenceListener = null
        serverTimeOffsetRef = null
        serverTimeOffsetListener = null
        lastRawUsers = emptyList()
    }

    fun publish(detectedLab: String, rssi: Map<String, Int>, distances: Map<String, Double>) {
        val currentUser = auth.currentUser ?: return
        val uid = currentUser.uid
        val userName = resolveName(uid, currentUser.email)

        val presenceRef = database.child("presence").child(uid)
        if (!disconnectHookRegistered) {
            presenceRef.onDisconnect().removeValue()
            disconnectHookRegistered = true
        }

        val presenceData = mapOf(
            "uid" to uid,
            "name" to userName,
            "detectedLab" to detectedLab.ifBlank { "Unknown" },
            "signals" to rssi,
            "distances" to distances,
            "timestamp" to ServerValue.TIMESTAMP
        )

        presenceRef.setValue(presenceData)
            .addOnFailureListener { error ->
                Log.w("PresenceRepository", "Failed to publish presence for $uid", error)
            }
    }

    fun clear() {
        stopListening()

        val currentUser = auth.currentUser ?: return
        val uid = currentUser.uid

        database.child("presence").child(uid).removeValue()
        cachedName = null
        hasLoadedName = false
        disconnectHookRegistered = false
    }

    private fun emitFilteredUsers() {
        val listener = activeListener ?: return
        val currentTime = System.currentTimeMillis() + serverTimeOffsetMs
        val filtered = lastRawUsers.filter { currentTime - it.timestamp <= STALE_TIMEOUT_MS }
            .sortedWith(compareBy<PresenceUser> { if (it.isSelf) 0 else 1 }
                .thenBy { it.name.lowercase() })
        listener.onPresenceChanged(filtered)
    }

    private fun parsePresenceSnapshot(snapshot: DataSnapshot): List<PresenceUser> {
        if (!snapshot.exists()) return emptyList()

        val currentUserId = auth.currentUser?.uid
        val entries = mutableListOf<PresenceUser>()

        for (child in snapshot.children) {
            val rawEntry = child.value as? Map<*, *> ?: continue

            val uid = rawEntry["uid"] as? String
            if (uid.isNullOrBlank()) {
                Log.w("PresenceRepository", "Skipping presence entry without uid: ${child.key}")
                continue
            }

            val detectedLab = rawEntry["detectedLab"] as? String
            if (detectedLab.isNullOrBlank()) {
                Log.w("PresenceRepository", "Skipping presence entry without detectedLab for uid=$uid")
                continue
            }

            val timestamp = when (val rawTimestamp = rawEntry["timestamp"]) {
                is Number -> rawTimestamp.toLong()
                is String -> rawTimestamp.toLongOrNull()
                else -> null
            }
            if (timestamp == null) {
                Log.w("PresenceRepository", "Skipping presence entry without valid timestamp for uid=$uid")
                continue
            }

            val name = (rawEntry["name"] as? String)?.takeIf { it.isNotBlank() } ?: "User"
            val signalsMap = rawEntry["signals"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val distancesMap = rawEntry["distances"] as? Map<*, *> ?: emptyMap<Any?, Any?>()

            val signals = signalsMap.entries.associate { (label, value) ->
                label.toString() to ((value as? Number)?.toInt() ?: -100)
            }
            val distances = distancesMap.entries.associate { (label, value) ->
                label.toString() to ((value as? Number)?.toDouble() ?: 99.0)
            }

            entries.add(
                PresenceUser(
                    uid = uid,
                    name = name,
                    detectedLab = detectedLab.ifBlank { "Unknown" },
                    signals = signals,
                    distances = distances,
                    timestamp = timestamp,
                    isSelf = uid == currentUserId
                )
            )
        }

        return entries
    }

    private fun resolveName(uid: String, email: String?): String {
        if (hasLoadedName && !cachedName.isNullOrBlank()) {
            return cachedName!!
        }

        val fallbackName = when {
            !email.isNullOrBlank() && email.contains("@") -> email.substringBefore("@").ifBlank { "User" }
            !email.isNullOrBlank() -> email.ifBlank { "User" }
            else -> "User"
        }

        if (!hasLoadedName) {
            loadUserNameOnce(uid, fallbackName)
            return fallbackName
        }

        return cachedName ?: fallbackName
    }

    private fun loadUserNameOnce(uid: String, fallbackName: String) {
        if (hasLoadedName) return

        database.child("users").child(uid).child("name")
            .get()
            .addOnSuccessListener { snapshot ->
                val dbName = snapshot.getValue(String::class.java)
                cachedName = if (!dbName.isNullOrBlank()) dbName else fallbackName
                hasLoadedName = true
            }
            .addOnFailureListener {
                cachedName = fallbackName
                hasLoadedName = true
            }
    }
}
