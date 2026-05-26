package com.totalbattle.chestscanner.engine

import java.security.MessageDigest
import kotlin.math.roundToInt

class DeduplicationEngine {
    
    // Layer 1: Short-term cache (15 seconds) to handle duplicate frames from hovering/scrolling
    private val shortTermCache = mutableMapOf<String, Long>()
    private val SHORT_TERM_WINDOW_MS = 15_000L
    
    // Layer 2: Global hashes seen in this active session to prevent double counting
    // if the user scrolls up and down
    private val sessionCache = mutableSetOf<String>()

    fun isDuplicate(
        chestName: String,
        fromPlayer: String,
        source: String,
        time: String,
        gameDay: String,
        normalizedY: Float
    ): Boolean {
        val now = System.currentTimeMillis()
        
        // Clean up old entries in short-term cache
        shortTermCache.entries.removeIf { now - it.value > SHORT_TERM_WINDOW_MS }
        
        // iconPositionBucket helps distinguish identical chests on the SAME frame
        val iconPositionBucket = (normalizedY * 10).roundToInt()
        
        // Global hash uses all the specified fields to prevent double counting
        val globalHash = hash("$chestName:$fromPlayer:$source:$time:$gameDay")
        
        // Short term hash uses position to avoid double counting same chest during scroll
        val shortTermHash = hash("$chestName:$fromPlayer:$source:$time:$gameDay:$iconPositionBucket")
        
        if (sessionCache.contains(globalHash)) {
            return true
        }
        
        if (shortTermCache.containsKey(shortTermHash)) {
            return true
        }
        
        // Not a duplicate. Add to both caches.
        shortTermCache[shortTermHash] = now
        sessionCache.add(globalHash)
        
        return false
    }
    
    fun clearSession() {
        shortTermCache.clear()
        sessionCache.clear()
    }

    private fun hash(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
