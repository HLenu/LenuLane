# LenuLane ProGuard/R8 rules.
# Minification is currently disabled (see app/build.gradle.kts); these rules
# are provided so R8 can be enabled safely in the future.

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicinterface.PublicInterface.** { *; }

# --- Keep VPN service entry points ---
-keep class net.steampp.wattkit.vpn.WattVpnService { *; }

# --- Kotlin coroutines ---
-dontwarn kotlinx.coroutines.**
