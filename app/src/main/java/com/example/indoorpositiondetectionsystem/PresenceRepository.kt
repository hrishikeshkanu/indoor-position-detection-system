package com.example.indoorpositiondetectionsystem

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

object PresenceRepository {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference

    private var cachedName: String? = null
    private var hasLoadedName = false
    private var disconnectHookRegistered = false

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
        val currentUser = auth.currentUser ?: return
        val uid = currentUser.uid

        database.child("presence").child(uid).removeValue()
        cachedName = null
        hasLoadedName = false
        disconnectHookRegistered = false
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
