package net.steampp.wattkit.authenticator

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class SteamAccount(
    val id: String,
    val accountName: String,
    val sharedSecret: String,
    val identitySecret: String = ""
)

class AccountStorage(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("wattkit_steam_accounts", Context.MODE_PRIVATE)

    fun getAccounts(): List<SteamAccount> {
        val raw = prefs.getString("accounts_json", "[]") ?: "[]"
        val list = mutableListOf<SteamAccount>()
        try {
            val jsonArray = JSONArray(raw)
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
        } catch (_: Exception) {}
        return list
    }

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
        prefs.edit().putString("accounts_json", jsonArray.toString()).apply()
    }

    fun addAccount(account: SteamAccount) {
        val current = getAccounts().toMutableList()
        current.removeAll { it.accountName.equals(account.accountName, ignoreCase = true) }
        current.add(account)
        saveAccounts(current)
    }

    fun removeAccount(id: String) {
        val current = getAccounts().toMutableList()
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
}
