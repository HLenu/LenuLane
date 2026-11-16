package net.steampp.wattkit.authenticator

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SteamAccount(
    val id: String,
    val accountName: String,
    val sharedSecret: String,
    val identitySecret: String = ""
)

/**
 * Encrypts/decrypts the Steam account secrets with a hardware-backed
 * AES-256-GCM key held in the Android Keystore. The key never leaves
 * secure hardware / TEE, so a rooted device or backup extraction alone
 * is not enough to recover the shared_secret values.
 */
object KeystoreCrypto {
    private const val TAG = "KeystoreCrypto"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "wattkit_master_key"
    private const val GCM_TAG_BITS = 128
    private const val IV_SIZE = 12

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain)
        // iv (12 bytes) || ciphertext+tag, base64 encoded for storage
        val blob = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, blob, 0, iv.size)
        System.arraycopy(encrypted, 0, blob, iv.size, encrypted.size)
        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): ByteArray? {
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            if (blob.size <= IV_SIZE) return null
            val iv = blob.copyOfRange(0, IV_SIZE)
            val encrypted = blob.copyOfRange(IV_SIZE, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(encrypted)
        } catch (e: Exception) {
            // GCM tag failure (tampered data or changed key) or keystore issue
            Log.w(TAG, "Failed to decrypt blob: ${e.message}")
            null
        }
    }
}

/**
 * Encrypted at-rest storage for Steam Guard accounts.
 *
 * Secrets are serialized to JSON and sealed with KeystoreCrypto before
 * touching SharedPreferences. Legacy plaintext data (written by earlier
 * versions under "accounts_json") is transparently migrated on first read.
 */
class AccountStorage private constructor(context: Context) {
    companion object {
        private const val PREFS_NAME = "wattkit_steam_accounts"
        private const val KEY_BLOB = "accounts_blob"
        private const val KEY_LEGACY_JSON = "accounts_json"

        @Volatile
        private var instance: AccountStorage? = null

        fun get(context: Context): AccountStorage =
            instance ?: synchronized(this) {
                instance ?: AccountStorage(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getAccounts(): List<SteamAccount> {
        return readAccounts()
    }

    @Synchronized
    fun saveAccounts(accounts: List<SteamAccount>) {
        val jsonArray = JSONArray()
        for (acc in accounts) {
            val obj = JSONObject().apply {
                put("id", acc.id)
                put("account_name", acc.accountName)
                put("shared_secret", acc.sharedSecret)
                put("identity_secret", acc.identitySecret)
            }
            jsonArray.put(obj)
        }
        prefs.edit()
            .putString(KEY_BLOB, KeystoreCrypto.encrypt(jsonArray.toString().toByteArray(Charsets.UTF_8)))
            .remove(KEY_LEGACY_JSON) // drop any leftover plaintext
            .apply()
    }

    @Synchronized
    fun addAccount(account: SteamAccount) {
        val current = readAccounts().toMutableList()
        current.removeAll { it.accountName.equals(account.accountName, ignoreCase = true) }
        current.add(account)
        saveAccounts(current)
    }

    @Synchronized
    fun removeAccount(id: String) {
        val current = readAccounts().toMutableList()
        current.removeAll { it.id == id }
        saveAccounts(current)
    }

    fun parseMaFile(jsonStr: String): SteamAccount? {
        return try {
            val obj = JSONObject(jsonStr)
            val sharedSecret = obj.optString("shared_secret")
            if (sharedSecret.isEmpty()) return null
            val accountName = obj.optString("account_name", "Steam Account")
            val identitySecret = obj.optString("identity_secret", "")
            SteamAccount(
                id = System.currentTimeMillis().toString(),
                accountName = accountName,
                sharedSecret = sharedSecret,
                identitySecret = identitySecret
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readAccounts(): List<SteamAccount> {
        val list = mutableListOf<SteamAccount>()

        // 1. Encrypted blob (current format)
        val blob = prefs.getString(KEY_BLOB, null)
        if (blob != null) {
            val plain = KeystoreCrypto.decrypt(blob)
            if (plain != null) {
                parseAccounts(String(plain, Charsets.UTF_8))?.let { return it }
            }
            // fall through to legacy on corruption so users don't lose accounts
        }

        // 2. Legacy plaintext JSON (pre-encryption format), migrate if found
        val legacy = prefs.getString(KEY_LEGACY_JSON, null)
        if (legacy != null) {
            val parsed = parseAccounts(legacy) ?: emptyList()
            if (parsed.isNotEmpty()) {
                saveAccounts(parsed) // rewrites encrypted, removes legacy key
                return parsed
            }
            prefs.edit().remove(KEY_LEGACY_JSON).apply()
        }
        return list
    }

    private fun parseAccounts(raw: String): List<SteamAccount>? {
        return try {
            val jsonArray = JSONArray(raw)
            val list = mutableListOf<SteamAccount>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SteamAccount(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        accountName = obj.optString("account_name", "Steam User"),
                        sharedSecret = obj.optString("shared_secret", ""),
                        identitySecret = obj.optString("identity_secret", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            null
        }
    }
}
