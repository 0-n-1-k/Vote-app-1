package com.example.security

import android.util.Base64
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object JwtHelper {
    private const val HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}"

    // Generate JWT token for user roll
    fun createToken(roll: String, isManagement: Boolean, expirySeconds: Long, secret: String): String {
        try {
            val headerBase64 = base64UrlEncode(HEADER.toByteArray(Charsets.UTF_8))
            val payload = JSONObject().apply {
                put("roll", roll)
                put("is_management", isManagement)
                put("exp", expirySeconds)
            }.toString()
            val payloadBase64 = base64UrlEncode(payload.toByteArray(Charsets.UTF_8))
            val dataToSign = "$headerBase64.$payloadBase64"
            val signature = signHmacSha256(dataToSign, secret)
            return "$dataToSign.$signature"
        } catch (e: Exception) {
            return ""
        }
    }

    // Verify JWT token and return parsed Claims if valid
    fun verifyToken(token: String, secret: String): Claims? {
        try {
            val parts = token.split(".")
            if (parts.size != 3) return null

            val headerBase64 = parts[0]
            val payloadBase64 = parts[1]
            val signature = parts[2]

            // Verify signature
            val expectedSignature = signHmacSha256("$headerBase64.$payloadBase64", secret)
            if (signature != expectedSignature) {
                return null
            }

            // Parse payload
            val payloadJson = String(base64UrlDecode(payloadBase64), Charsets.UTF_8)
            val json = JSONObject(payloadJson)
            val exp = json.getLong("exp")
            val currentSec = System.currentTimeMillis() / 1000

            if (currentSec >= exp) {
                return null // Expired
            }

            val roll = json.optString("roll", "")
            val isManagement = json.optBoolean("is_management", false)
            return Claims(roll, isManagement, exp)
        } catch (e: Exception) {
            return null
        }
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE).trim()
    }

    private fun base64UrlDecode(str: String): ByteArray {
        return Base64.decode(str, Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun signHmacSha256(data: String, secret: String): String {
        val sha256HMAC = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        sha256HMAC.init(secretKey)
        val hash = sha256HMAC.doFinal(data.toByteArray(Charsets.UTF_8))
        return base64UrlEncode(hash)
    }

    data class Claims(
        val roll: String,
        val isManagement: Boolean,
        val exp: Long
    )
}
