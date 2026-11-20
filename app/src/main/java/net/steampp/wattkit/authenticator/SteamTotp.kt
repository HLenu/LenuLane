package net.steampp.wattkit.authenticator

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.steampp.wattkit.proxy.NetworkDiagnostics
import okhttp3.FormBody
import java.nio.ByteBuffer
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Decoupled Steam Guard RFC-6238 TOTP generator and Valve time synchronizer.
 * Persists calibrated clock offset independently of VPN state so tokens remain
 * accurate offline or when the VPN engine is stopped/restarting.
 */
object SteamTotp {
    private const val TAG = "SteamTotp"
    private const val PREFS_NAME = "wattkit_totp_clock"
    private const val KEY_OFFSET = "server_time_offset_sec"
    private const val KEY_LAST_SYNC = "last_sync_epoch_sec"

    private val STEAM_CHARS = charArrayOf(
        '2', '3', '4', '5', '6', '7', '8', '9',
        'B', 'C', 'D', 'F', 'G', 'H', 'J', 'K',
        'M', 'N', 'P', 'Q', 'R', 'T', 'V', 'W', 'X', 'Y'
    )

    private var prefs: SharedPreferences? = null

    @Volatile
    var serverTimeOffset: Long = 0L
        private set

    @Volatile
    var lastSyncEpochSeconds: Long = 0L
        private set

    fun initialize(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        serverTimeOffset = sp.getLong(KEY_OFFSET, 0L)
        lastSyncEpochSeconds = sp.getLong(KEY_LAST_SYNC, 0L)
    }

    fun hasClockCalibration(): Boolean = lastSyncEpochSeconds > 0L

    fun getRemainingSeconds(): Int {
        val currentTime = (System.currentTimeMillis() / 1000L) + serverTimeOffset
        return (30 - (currentTime % 30)).toInt()
    }

    fun getProgress(): Float = getRemainingSeconds() / 30f

    fun isValidBase64Secret(secret: String): Boolean {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val bytes = Base64.decode(trimmed, Base64.DEFAULT)
            bytes.size >= 10
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Synchronize time offset with Valve official TwoFactor query time API using
     * WattKit's DoH + TLS-fragmented pipeline (independent of VPN toggle state).
     */
    suspend fun syncServerTime(): Boolean = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("steamid", "0")
            .build()

        val outcome = NetworkDiagnostics.executeWithPipeline(
            url = "https://api.steampowered.com/ITwoFactorService/QueryTime/v0001/",
            host = "api.steampowered.com",
            postBody = formBody,
            readBody = true
        )

        if (outcome.httpCode in 200..299 && outcome.bodyText.isNotEmpty()) {
            val matcher = Pattern.compile("\"server_time\"\\s*:\\s*\"?(\\d+)\"?").matcher(outcome.bodyText)
            if (matcher.find()) {
                val serverTime = matcher.group(1)?.toLongOrNull()
                if (serverTime != null && serverTime > 0) {
                    val localTime = System.currentTimeMillis() / 1000L
                    serverTimeOffset = serverTime - localTime
                    lastSyncEpochSeconds = localTime
                    prefs?.edit()
                        ?.putLong(KEY_OFFSET, serverTimeOffset)
                        ?.putLong(KEY_LAST_SYNC, lastSyncEpochSeconds)
                        ?.apply()
                    Log.i(TAG, "Valve time synced via ${outcome.resolvedIp}: offset=${serverTimeOffset}s")
                    return@withContext true
                }
            }
        }
        Log.w(TAG, "Valve time sync failed (${outcome.error ?: "HTTP ${outcome.httpCode}"})")
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
        if (secretBytes.isEmpty()) return "ERROR"

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
