package com.totalbattle.chestscanner.ocr

import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

class Normalizer {
    // Matrix-based Levenshtein Distance for fuzzy matching
    fun getLevenshteinDistance(s1: String, s2: String): Int {
        val len1 = s1.length
        val len2 = s2.length
        val dp = Array(len1 + 1) { IntArray(len2 + 1) }

        for (i in 0..len1) dp[i][0] = i
        for (j in 0..len2) dp[0][j] = j

        for (i in 1..len1) {
            for (j in 1..len2) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[len1][len2]
    }

    fun getSimilarity(s1: String, s2: String): Double {
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        return 1.0 - (getLevenshteinDistance(s1, s2).toDouble() / maxLen.toDouble())
    }

    // Swaps common OCR digit/character swaps from Python logic
    fun cleanOcrSwaps(input: String): String {
        var text = input.trim()
        if (text.isEmpty()) return text

        // General OCR error normalization from Python script
        text = text.replace("Dark 0men", "Dark Omen")
            .replace("Dark Ornen", "Dark Omen")
            .replace("l\\/l", "M")
            .replace("l/l", "M")
            .replace("|\\/|", "M")
            .replace("|/|", "M")
            .replace("0", "o")
            .replace("4", "a")
            .replace("1", "i")
            .replace("5", "s")
            .replace("3", "e")

        val replacements = listOf(
            "oi" to "of", "ol" to "of", "af" to "of",
            "ct" to "of", "ot" to "of",
            "ofthe" to "of the", "Chestof" to "Chest of"
        )

        for ((wrong, correct) in replacements) {
            text = text.replace(wrong, correct, ignoreCase = true)
        }

        // Title case logic: "Ancient dragon chest" -> "Ancient Dragon Chest"
        return text.split(" ").joinToString(" ") { word ->
            if (word.lowercase() in listOf("of", "the")) {
                word.lowercase()
            } else {
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
        }
    }

    // Core Player Normalization Flow
    fun normalizePlayer(rawName: String): String {
        val cleanedName = rawName.trim()
        if (cleanedName.isEmpty()) return "UNKNOWN_PLAYER"

        // Local whitelist cache removed as per Phase 5 patch:
        // "The backend handles canonicalization. OCR errors must be resolved to the true canonical player."
        // Just return the raw extracted name, next.js backend handles matching.
        return cleanedName
    }

    // Timer parser logic: parses hours and minutes to compute creation time
    fun parseTimer(timerStr: String): Long {
        val cleaned = cleanOcrSwaps(timerStr).lowercase(Locale.getDefault())
        // Match standard timer like "18h 35m" or "18h35m"
        val pattern = Pattern.compile("(\\d+)\\s*h(?:our)?s?\\s*(\\d+)\\s*m(?:in)?s?")
        val matcher = pattern.matcher(cleaned)

        if (matcher.find()) {
            val hours = matcher.group(1)?.toIntOrNull() ?: 0
            val minutes = matcher.group(2)?.toIntOrNull() ?: 0

            val tz = java.util.TimeZone.getTimeZone("GMT+10")
            val now = Calendar.getInstance(tz)
            val chestTime = Calendar.getInstance(tz).apply {
                set(Calendar.HOUR_OF_DAY, hours)
                set(Calendar.MINUTE, minutes)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            if (chestTime.after(now)) {
                chestTime.add(Calendar.DAY_OF_YEAR, -1)
            }

            return chestTime.timeInMillis
        }

        return System.currentTimeMillis() // Fallback to current time
    }

    // Normalizes source strings using python script patterns
    fun normalizeSource(rawSource: String): String {
        val text = rawSource.trim()
        
        // Manual OCR fixes from python ocr_fixes table
        val ocrFixes = mapOf(
            "Spofls" to "Spoils",
            "Herofc" to "Heroic",
            "Trfumph" to "Triumph",
            "Captatn" to "Captain"
        )
        
        for ((wrong, correct) in ocrFixes) {
            if (text.contains(wrong, ignoreCase = true)) {
                return correct
            }
        }

        val cleaned = text.lowercase()
        return when {
            cleaned.contains("crypt") -> "Crypt"
            cleaned.contains("monst") || cleaned.contains("troll") -> "Monster"
            cleaned.contains("chest") -> "Chest"
            cleaned.contains("clan") -> "Clan"
            cleaned.contains("pvp") || cleaned.contains("player") -> "PvP"
            cleaned.contains("arena") -> "Arena"
            cleaned.contains("tower") -> "Tower"
            else -> text
        }
    }
}
