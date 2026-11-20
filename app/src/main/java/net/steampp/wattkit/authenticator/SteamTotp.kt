package net.steampp.wattkit.authenticator

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.steampp.wattkit.proxy.NetworkDiagnostics
import okhttp3.FormBody
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Decoupled Steam Guard RFC-6238 TOTP generator, otpauth:// URI parser,
 * Valve mobile confirmation hash generator, and time synchronizer.
 * Pure-Kotlin Base32/Base64 codecs keep cryptographic methods JVM-testable on API 24+.
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

    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

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

    fun currentSyncedTimeSeconds(): Long = (System.currentTimeMillis() / 1000L) + serverTimeOffset

    fun getRemainingSeconds(): Int {
        val currentTime = currentSyncedTimeSeconds()
        return (30 - (currentTime % 30)).toInt()
    }

    fun getProgress(): Float = getRemainingSeconds() / 30f

    fun isValidBase64Secret(secret: String): Boolean {
        val bytes = decodeBase64(secret) ?: return false
        return bytes.size >= 10
    }

    /**
     * Parses `otpauth://totp/Issuer:AccountName?secret=BASE32&issuer=...` (and `otpauth://steam/...`)
     * into a SteamAccount whose `sharedSecret` is normalized Base64 bytes.
     */
    fun parseOtpAuthUri(uriString: String): SteamAccount? {
        val trimmed = uriString.trim()
        if (!trimmed.startsWith("otpauth://", ignoreCase = true)) return null
        return try {
            val uri = URI(trimmed)
            val rawQuery = uri.rawQuery ?: return null
            val params = mutableMapOf<String, String>()
            for (pair in rawQuery.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8").lowercase()
                    val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                    params[k] = v
                }
            }
            val rawSecret = params["secret"]?.trim() ?: return null
            val rawBytes = decodeBase32(rawSecret)
                ?: decodeBase64(rawSecret)
                ?: return null
            if (rawBytes.size < 10) return null

            val encodedBase64 = encodeBase64(rawBytes)
            val rawPath = (uri.rawPath ?: "").trimStart('/')
            val decodedLabel = URLDecoder.decode(rawPath, "UTF-8").trim()
            val labelAccount = decodedLabel.substringAfter(':', decodedLabel).trim()
                .ifEmpty { params["issuer"]?.trim() ?: "Steam Guard" }

            SteamAccount(
                id = "otp_${System.currentTimeMillis()}",
                accountName = labelAccount,
                sharedSecret = encodedBase64
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Generates a Valve trade/market confirmation hash (`Base64(HMAC-SHA1(identity_secret, be64(time) || tag))`),
     * matching Valve Mobile Authenticator / steamguard-cli protocol.
     */
    fun generateConfirmationHash(
        identitySecretBase64: String,
        timeSeconds: Long = currentSyncedTimeSeconds(),
        tag: String = "conf"
    ): String? {
        val secretBytes = decodeBase64(identitySecretBase64) ?: return null
        if (secretBytes.isEmpty()) return null
        return try {
            val tagBytes = tag.toByteArray(Charsets.US_ASCII)
            val payload = ByteArray(8 + tagBytes.size)
            ByteBuffer.wrap(payload, 0, 8).putLong(timeSeconds)
            System.arraycopy(tagBytes, 0, payload, 8, tagBytes.size)
            val mac = Mac.getInstance("HmacSHA1").apply {
                init(SecretKeySpec(secretBytes, "HmacSHA1"))
            }
            encodeBase64(mac.doFinal(payload))
        } catch (_: Exception) {
            null
        }
    }

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
        val secretBytes = decodeBase64(sharedSecretBase64) ?: return "ERROR"
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

    internal fun decodeBase32(input: String): ByteArray? {
        val clean = input.uppercase().replace("=", "").replace(" ", "").replace("-", "")
        if (clean.isEmpty()) return null
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val val5 = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in '2'..'7' -> (ch - '2') + 26
                else -> return null
            }
            buffer = (buffer shl 5) or val5
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer ushr bitsLeft) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    internal fun decodeBase64(input: String): ByteArray? {
        val clean = input.trim().filterNot { it.isWhitespace() }
        if (clean.isEmpty()) return null
        val out = ByteArrayOutputStream()
        var acc = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> (ch - 'a') + 26
                in '0'..'9' -> (ch - '0') + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> return null
            }
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((acc ushr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    internal fun encodeBase64(bytes: ByteArray): String {
        val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val has1 = i + 1 < bytes.size
            val has2 = i + 2 < bytes.size
            val b1 = if (has1) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (has2) bytes[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(BASE64_ALPHABET[(triple ushr 18) and 0x3F])
            sb.append(BASE64_ALPHABET[(triple ushr 12) and 0x3F])
            sb.append(if (has1) BASE64_ALPHABET[(triple ushr 6) and 0x3F] else '=')
            sb.append(if (has2) BASE64_ALPHABET[triple and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }
}
