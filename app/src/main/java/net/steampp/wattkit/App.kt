package net.steampp.wattkit

import android.app.Application
import net.steampp.wattkit.authenticator.SteamTotp
import net.steampp.wattkit.proxy.RuleManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize persisted rule states and decoupled Steam TOTP clock offset before UI/services start.
        RuleManager.initialize(this)
        SteamTotp.initialize(this)
    }
}
