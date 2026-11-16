package net.steampp.wattkit

import android.app.Application
import net.steampp.wattkit.proxy.RuleManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Load persisted acceleration rule states before the VPN service or
        // any UI might use them (the service can be started before an activity).
        RuleManager.initialize(this)
    }
}
