package com.anizium

import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

object AniziumToken {
    private const val TOKEN_KEY = "hlxjl1c2w281ax473rt1ofgrvhyjvi"
    private const val CLIENT_KEY = "16ghkdz5qnwinkyebwopbd94b49xhs"

    fun xorEncrypt(str: String, key: String = CLIENT_KEY): String {
        try {
            val textBytes = str.toByteArray(StandardCharsets.UTF_8)
            val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
            val result = StringBuilder(textBytes.size * 2)

            for (i in textBytes.indices) {
                val b = (textBytes[i].toInt() xor keyBytes[i % keyBytes.size].toInt()) and 0xFF
                result.append(String.format("%02x", b))
            }
            return result.toString()
        } catch (e: Exception) {
            return ""
        }
    }

    fun generateCfControl(): String {
        return try {
            val istanbulZone = ZoneId.of("Europe/Istanbul")
            val now = ZonedDateTime.now(istanbulZone)
            val weekday = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH).lowercase(Locale.ENGLISH)

            val key = "${TOKEN_KEY}_$weekday"
            val randomKey = (1..6).map {
                val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
                chars[Random.nextInt(chars.length)]
            }.joinToString("")

            val timestamp = System.currentTimeMillis()
            val payload = "{\"$randomKey\":$timestamp}"

            xorEncrypt(payload, key)
        } catch (e: Exception) {
            ""
        }
    }

    fun getApiHeaders(): Map<String, String> {
        return mapOf(
            "Cf-Control" to generateCfControl(),
            "device" to "browser",
            "user-session" to "",
            "user-profile" to "",
            "language" to "tr",
            "site" to "main",
            "Content-Type" to "application/json",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
    }
}
