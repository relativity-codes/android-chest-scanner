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
        var cleanedName = rawName.trim()
        if (cleanedName.isEmpty()) return "UNKNOWN_PLAYER"

        // Strip residual "From:" prefix in case the label leaked into the captured group
        cleanedName = cleanedName.replace(Regex("(?i)^From\\s*:\\s*"), "").trim()

        if (cleanedName.isEmpty() || cleanedName.length < 2) return "UNKNOWN_PLAYER"

        // NOTE: intentionally NOT running cleanOcrSwaps() here — player names may
        // legitimately contain digits (e.g. "K4zimi", "L0rd99") and the global
        // digit→letter swaps would corrupt them. The backend whitelist handles fuzzy matching.
        return cleanedName
    }

    // Timer parser logic: parses "19h 46m" → clock time in UTC+10.
    // The game shows the time the chest was GIVEN (clock time), not a countdown.
    // NOTE: deliberately does NOT call cleanOcrSwaps — that function replaces digits
    // with letters ("1"→"i", "4"→"a", etc.) which would corrupt the hour/minute values.
    fun parseTimer(timerStr: String): Long {
        val tz = java.util.TimeZone.getTimeZone("GMT+10")
        val now = Calendar.getInstance(tz)

        if (timerStr.isBlank()) return now.timeInMillis

        // Match "19h 46m", "19h46m", "9h 5m" — same pattern as calculateActualTimestamp
        val pattern = Pattern.compile("(\\d{1,2})\\s*h\\s*(\\d{1,2})\\s*m", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(timerStr.trim())

        if (matcher.find()) {
            val hours   = matcher.group(1)?.toIntOrNull() ?: return now.timeInMillis
            val minutes = matcher.group(2)?.toIntOrNull() ?: return now.timeInMillis

            // Sanity check — game timers are 0h–23h 59m
            if (hours > 23 || minutes > 59) return now.timeInMillis

            val chestTime = Calendar.getInstance(tz).apply {
                set(Calendar.HOUR_OF_DAY, hours)
                set(Calendar.MINUTE, minutes)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // If the resolved time is still in the future, the chest was sent yesterday
            // (e.g. it's 01:00 now and the timer reads 23:50)
            if (chestTime.after(now)) {
                chestTime.add(Calendar.DAY_OF_YEAR, -1)
            }

            return chestTime.timeInMillis
        }

        return now.timeInMillis // Fallback: use current time
    }

    private fun cleanSourceText(input: String): String {
        var text = input.trim()
        if (text.isEmpty()) return text

        // 1. Strip "Source:" prefix (case-insensitive) using regex
        val sourcePrefixRegex = Regex("(?i)^s[a-z]{3,5}e?\\s*:\\s*")
        val match = sourcePrefixRegex.find(text)
        if (match != null) {
            text = text.substring(match.range.last + 1).trim()
        }

        // 2. Fix common OCR typos in the word "Level" (like "Leve1", "Leve|", "Lvl", "lvl")
        text = text.replace(Regex("(?i)Leve[1|liI]"), "Level")
                   .replace(Regex("(?i)\\blvl\\b"), "Level")

        // 3. Fix manual OCR words
        val ocrFixes = mapOf(
            "Spofls" to "Spoils",
            "Herofc" to "Heroic",
            "Trfumph" to "Triumph",
            "Captatn" to "Captain"
        )
        for ((wrong, correct) in ocrFixes) {
            text = text.replace(wrong, correct, ignoreCase = true)
        }

        // 4. Title case the words, keeping digits as is
        return text.split(" ").joinToString(" ") { word ->
            if (word.lowercase() in listOf("of", "the")) {
                word.lowercase()
            } else {
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
        }
    }

    // Normalizes source strings using python script patterns
    fun normalizeSource(rawSource: String): String {
        val text = rawSource.trim()
        if (text.isEmpty()) return "Unknown"
        
        val cleaned = cleanSourceText(text)
        val cleanedLower = cleaned.lowercase(Locale.getDefault())

        return when {
            cleanedLower.contains("triumph") -> "Triumphal Gifts"
            cleanedLower.contains("gifts") -> "Gifts"
            cleanedLower.contains("clan") -> "Clan"
            cleanedLower.contains("pvp") || cleanedLower.contains("player") -> "PvP"
            cleanedLower.contains("arena") -> "Arena"
            cleanedLower.contains("tower") -> "Tower"
            cleanedLower.contains("captain") -> "Captain"
            // For crypts, monsters, spoils, etc., keep the full text details
            cleanedLower.contains("crypt") || cleanedLower.contains("troll") || 
            cleanedLower.contains("monst") || cleanedLower.contains("sphinx") || 
            cleanedLower.contains("gorgon") || cleanedLower.contains("beholder") || 
            cleanedLower.contains("dragon") || cleanedLower.contains("giant") || 
            cleanedLower.contains("beast") || cleanedLower.contains("spoils") -> cleaned
            else -> "Unknown"
        }
    }
}
