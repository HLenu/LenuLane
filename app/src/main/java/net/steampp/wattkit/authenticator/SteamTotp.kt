package net.steampp.wattkit.authenticator

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object SteamTotp {
    private const val TAG = "SteamTotp"
    private val STEAM_CHARS = charArrayOf(
        '2', '3', '4', '5', '6', '7', '8', '9',
        'B', 'C', 'D', 'F', 'G', 'H', 'J', 'K',
        'M', 'N', 'P', 'Q', 'R', 'T', 'V', 'W', 'X', 'Y'
    )

    var serverTimeOffset: Long = 0L
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun getRemainingSeconds(): Int {
        val currentTime = (System.currentTimeMillis() / 1000L) + serverTimeOffset
        return (30 - (currentTime % 30)).toInt()
    }

    fun getProgress(): Float {
        val remaining = getRemainingSeconds()
        return remaining / 30f
    }

    /**
     * Synchronize time offset with Steam official Two-Factor query time API.
     */
    suspend fun syncServerTime(): Boolean = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("steamid", "0")
            .build()

        val request = Request.Builder()
            .url("https://api.steampowered.com/ITwoFactorService/QueryTime/v0001/")
            .post(formBody)
            .header("User-Agent", "WattKit/1.0.0")
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val matcher = Pattern.compile("\"server_time\"\\s*:\\s*\"?(\\d+)\"?").matcher(body)
                if (matcher.find()) {
                    val serverTime = matcher.group(1)?.toLongOrNull()
                    if (serverTime != null && serverTime > 0) {
                        val localTime = System.currentTimeMillis() / 1000L
                        serverTimeOffset = serverTime - localTime
                        Log.i(TAG, "Steam server time synced: server=$serverTime, offset=${serverTimeOffset}s")
                        return@withContext true
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync Steam server time, falling back to local time: ${e.message}")
        }
        return@withContext false
    }

    /**
     * Generates a 5-character Steam Guard code from a base64-encoded shared_secret.
     */
    fun generateSteamGuardCode(sharedSecretBase64: String, timeOffset: Long = serverTimeOffset): String {
        val secretBytes = try {
            Base64.decode(sharedSecretBase64.trim(), Base64.DEFAULT)
        } catch (_: Exception) {
            return "ERROR"
        }

        val currentTimeSeconds = (System.currentTimeMillis() / 1000L) + timeOffset
        val timeSlice = currentTimeSeconds / 30L

        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(timeSlice)
        val timeBytes = buffer.array()

        val mac = try {
            Mac.getInstance("HmacSHA1").apply {
                init(SecretKeySpec(secretBytes, "HmacSHA1"))
            }
        } catch (_: Exception) {
            return "ERROR"
        }

        val hmac = mac.doFinal(timeBytes)
        val offset = hmac[hmac.size - 1].toInt() and 0x0F

        val fullCode = ((hmac[offset].toInt() and 0x7F) shl 24) or
                ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                (hmac[offset + 3].toInt() and 0xFF)

        val codeChars = CharArray(5)
        var tempCode = fullCode
        for (i in 0 until 5) {
            codeChars[i] = STEAM_CHARS[tempCode % STEAM_CHARS.size]
            tempCode /= STEAM_CHARS.size
        }

        return String(codeChars)
    }
}
