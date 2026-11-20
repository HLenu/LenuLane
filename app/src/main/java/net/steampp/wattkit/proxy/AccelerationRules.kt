package net.steampp.wattkit.proxy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class RuleCategory(val displayName: String) {
    ALL("全部"),
    STEAM("Steam 宇宙"),
    PIXIV("Pixiv 二次元"),
    GAMING("游戏平台"),
    MODS("Mod 社区"),
    DEV("开发开源"),
    COMMUNITY("社交直播"),
    TOOLS("系统服务")
}

enum class AccelerationStrategy(val displayName: String, val badgeText: String) {
    DOH_TLS_FRAGMENT("DoH 净网 + TLS SNI 分片直连", "TLS 分片"),
    REVERSE_PROXY_MIRROR("反向代理镜像节点", "镜像节点"),
    CLEAN_DNS_DIRECT("DoH 纯净 DNS 直连", "DoH 直连"),
    UPSTREAM_PROXY("自定义上游代理转发", "上游代理");

    companion object {
        fun fromName(name: String?): AccelerationStrategy? =
            entries.firstOrNull { it.name == name }
    }
}

enum class RoutingMode(val displayName: String, val subtitle: String) {
    SMART_SPLIT("智能规则分流 (Fake-IP)", "仅接管加速域名，国内与日常应用 100% 物理直连不断网"),
    GLOBAL_TUN("全局 TUN 接管", "接管设备全部 IPv4 TCP/UDP 流量并按规则分流");

    companion object {
        fun fromName(name: String?): RoutingMode =
            entries.firstOrNull { it.name == name } ?: SMART_SPLIT
    }
}

enum class AppSplitMode(val displayName: String, val badge: String) {
    ALL_APPS("全部应用", "全局"),
    ONLY_SELECTED("仅加速选中应用", "白名单"),
    BYPASS_SELECTED("绕过选中应用", "黑名单");

    companion object {
        fun fromName(name: String?): AppSplitMode =
            entries.firstOrNull { it.name == name } ?: ALL_APPS
    }
}

data class UpstreamProxyConfig(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 7890,
    val isSocks5: Boolean = true
)

data class AccelerationRule(
    val id: String,
    val name: String,
    val description: String,
    val category: RuleCategory,
    val domains: List<String>,
    val strategy: AccelerationStrategy = AccelerationStrategy.DOH_TLS_FRAGMENT,
    val mirrorHost: String? = null,
    val targetPort: Int = 443,
    val isEnabled: Boolean = true,
    val isCustom: Boolean = false
)

object RuleManager {
    private const val TAG = "RuleManager"
    private const val PREFS_NAME = "wattkit_rules"
    private const val KEY_DISABLED = "disabled_rule_ids"
    private const val KEY_STRATEGIES = "rule_strategies_json"
    private const val KEY_CUSTOM_RULES_JSON = "custom_rules_json"
    private const val KEY_ROUTING_MODE = "routing_mode"
    private const val KEY_DOH_PROVIDER = "doh_provider"
    private const val KEY_TLS_FRAGMENT = "tls_fragment_enabled"
    private const val KEY_TLS_DESYNC_MODE = "tls_desync_mode"
    private const val KEY_APP_SPLIT_MODE = "app_split_mode"
    private const val KEY_APP_SPLIT_PACKAGES = "app_split_packages"
    private const val KEY_UPSTREAM_ENABLED = "upstream_enabled"
    private const val KEY_UPSTREAM_HOST = "upstream_host"
    private const val KEY_UPSTREAM_PORT = "upstream_port"
    private const val KEY_UPSTREAM_SOCKS5 = "upstream_socks5"

    private val lock = Any()
    private var prefs: SharedPreferences? = null

    private val _routingMode = MutableStateFlow(RoutingMode.SMART_SPLIT)
    val routingMode = _routingMode.asStateFlow()

    private val _dohProvider = MutableStateFlow(DoHProvider.DNSPOD)
    val dohProvider = _dohProvider.asStateFlow()

    private val _tlsFragmentEnabled = MutableStateFlow(true)
    val tlsFragmentEnabled = _tlsFragmentEnabled.asStateFlow()

    private val _tlsDesyncMode = MutableStateFlow(TlsDesyncMode.ADAPTIVE)
    val tlsDesyncMode = _tlsDesyncMode.asStateFlow()

    private val _appSplitMode = MutableStateFlow(AppSplitMode.ALL_APPS)
    val appSplitMode = _appSplitMode.asStateFlow()

    private val _selectedAppPackages = MutableStateFlow<Set<String>>(emptySet())
    val selectedAppPackages = _selectedAppPackages.asStateFlow()

    private val _upstreamProxy = MutableStateFlow(UpstreamProxyConfig())
    val upstreamProxy = _upstreamProxy.asStateFlow()

    // NOTE: domain patterns must be bare hostnames (optionally "*.example.com").
    // Every rule uses verified DoH anti-pollution + RFC TLS record fragmentation by default,
    // avoiding broken third-party certificates while remaining switchable per rule.
    val defaultRules = listOf(
        // ==================== 1. Steam 宇宙 ====================
        AccelerationRule(
            id = "steam_community",
            name = "Steam 社区与个人资料",
            description = "加速 steamcommunity.com 社区主页、讨论区、个人资料、好友列表与库存",
            category = RuleCategory.STEAM,
            domains = listOf("steamcommunity.com", "www.steamcommunity.com"),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT,
            mirrorHost = "steamcommunity.rmbgame.net"
        ),
        AccelerationRule(
            id = "steam_static",
            name = "Steam 图片与多媒体资源",
            description = "加速头像、游戏宣传截图、徽章、好友动态与创意工坊预览图加载",
            category = RuleCategory.STEAM,
            domains = listOf(
                "community.steamstatic.com",
                "avatars.steamstatic.com",
                "avatars.akamai.steamstatic.com",
                "community.akamai.steamstatic.com",
                "steamcdn-a.akamaihd.net",
                "steamuserimages-a.akamaihd.net",
                "clan.cloudflare.steamstatic.com",
                "shared.cloudflare.steamstatic.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT,
            mirrorHost = "steamstatic.rmbgame.net"
        ),
        AccelerationRule(
            id = "steam_store",
            name = "Steam 商店与登录服务",
            description = "加速 store.steampowered.com 商店推荐页、购物车、特惠专区与 Steam Guard 登录",
            category = RuleCategory.STEAM,
            domains = listOf(
                "store.steampowered.com",
                "api.steampowered.com",
                "help.steampowered.com",
                "login.steampowered.com",
                "checkout.steampowered.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT,
            mirrorHost = "steamstore.rmbgame.net"
        ),
        AccelerationRule(
            id = "steam_workshop",
            name = "Steam 创意工坊与广播",
            description = "加速游戏创意工坊资源、游戏服务器状态与 Steam 游戏直播流",
            category = RuleCategory.STEAM,
            domains = listOf(
                "steamserver.net",
                "steambroadcast.akamaized.net"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT,
            mirrorHost = "steamcommunity.rmbgame.net"
        ),

        // ==================== 2. Pixiv 二次元插画 ====================
        AccelerationRule(
            id = "pixiv_web",
            name = "Pixiv 网页与榜单主站",
            description = "加速 pixiv.net 网页浏览、每日插画排行榜、触屏版主站及登录认证",
            category = RuleCategory.PIXIV,
            domains = listOf(
                "pixiv.net",
                "www.pixiv.net",
                "touch.pixiv.net",
                "accounts.pixiv.net",
                "source.pixiv.net",
                "comic.pixiv.net"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT,
            mirrorHost = "pixiv.re"
        ),
        AccelerationRule(
            id = "pixiv_images",
            name = "Pixiv 高清插画原图 (pximg)",
            description = "加速 i.pximg.net 高清无损原画、漫画多图与动图包分镜加载，告别裂图",
            category = RuleCategory.PIXIV,
            domains = listOf(
                "i.pximg.net",
                "s.pximg.net",
                "source.pximg.net"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT,
            mirrorHost = "i.pixiv.re"
        ),
        AccelerationRule(
            id = "pixiv_fanbox",
            name = "Pixiv FANBOX & BOOTH",
            description = "加速 fanbox.cc 创作者赞助主站与 booth.pm 同人作品商店",
            category = RuleCategory.PIXIV,
            domains = listOf(
                "fanbox.cc",
                "www.fanbox.cc",
                "booth.pm"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT,
            mirrorHost = "pixiv.re"
        ),

        // ==================== 3. 游戏平台 ====================
        AccelerationRule(
            id = "epic_games",
            name = "Epic Games 游戏商城",
            description = "加速 Epic Games 客户端商店展示、周免喜加一领取与虚幻引擎资产库",
            category = RuleCategory.GAMING,
            domains = listOf(
                "epicgames.com",
                "store.epicgames.com",
                "launcher.epicgames.com",
                "accounts.epicgames.com",
                "tracking.epicgames.com",
                "unrealengine.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),
        AccelerationRule(
            id = "ea_origin",
            name = "EA / Origin 客户端",
            description = "加速 EA App、Origin 商城、战地与 Apex 英雄在线联机信令服务",
            category = RuleCategory.GAMING,
            domains = listOf(
                "ea.com",
                "origin.com",
                "api1.origin.com",
                "api2.origin.com",
                "signin.ea.com",
                "accounts.ea.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),
        AccelerationRule(
            id = "ubisoft_connect",
            name = "Ubisoft Connect 育碧云服务",
            description = "加速 Ubisoft Connect 育碧商城、彩六联机、Uplay 云存档同步",
            category = RuleCategory.GAMING,
            domains = listOf(
                "ubisoft.com",
                "uplay.com",
                "connect.ubisoft.com",
                "ubisoftconnect.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),
        AccelerationRule(
            id = "rockstar_games",
            name = "Rockstar Games (R星)",
            description = "加速 R星启动器 (Rockstar Games Launcher) 登录与 GTA 在线模式连接",
            category = RuleCategory.GAMING,
            domains = listOf(
                "rockstargames.com",
                "socialclub.rockstargames.com",
                "prod.telemetry.ros.rockstargames.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),
        AccelerationRule(
            id = "riot_games",
            name = "Riot Games 拳头游戏",
            description = "加速英雄联盟、无畏契约 (VALORANT) Riot Client 登录与更新检测",
            category = RuleCategory.GAMING,
            domains = listOf(
                "riotgames.com",
                "auth.riotgames.com",
                "clientconfig.rpg.riotgames.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),
        AccelerationRule(
            id = "gog_galaxy",
            name = "GOG Galaxy 平台",
            description = "加速 GOG 游戏商城与 GOG Galaxy 跨平台成就同步",
            category = RuleCategory.GAMING,
            domains = listOf(
                "gog.com",
                "api.gog.com",
                "auth.gog.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),

        // ==================== 4. Mod 社区 ====================
        AccelerationRule(
            id = "nexus_mods",
            name = "Nexus Mods (N网)",
            description = "加速 nexusmods.com 全球顶级游戏 Mod 网站页面与 Mod 文件下载通道",
            category = RuleCategory.MODS,
            domains = listOf(
                "nexusmods.com",
                "www.nexusmods.com",
                "images.nexusmods.com",
                "files.nexusmods.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),
        AccelerationRule(
            id = "curseforge",
            name = "CurseForge Mod 社区",
            description = "加速 Minecraft 我的世界、魔兽世界等 Mod/整合包一键下载与 API",
            category = RuleCategory.MODS,
            domains = listOf(
                "curseforge.com",
                "api.curseforge.com",
                "media.forgecdn.net"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),

        // ==================== 5. 开发开源 ====================
        AccelerationRule(
            id = "github",
            name = "GitHub 网页与代码下载",
            description = "加速 github.com 仓库浏览、Git Clone、API 及 Releases 发布包下载",
            category = RuleCategory.DEV,
            domains = listOf(
                "github.com",
                "api.github.com",
                "raw.githubusercontent.com",
                "codeload.github.com",
                "github.githubassets.com",
                "objects.githubusercontent.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),
        AccelerationRule(
            id = "gitlab",
            name = "GitLab 开发者协作",
            description = "加速 gitlab.com 官方代码托管平台与 CI/CD 界面访问",
            category = RuleCategory.DEV,
            domains = listOf(
                "gitlab.com",
                "about.gitlab.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),

        // ==================== 6. 社交直播 ====================
        AccelerationRule(
            id = "discord",
            name = "Discord 游戏语音与社区",
            description = "加速 Discord 频道连接、Gateway 网关信令与静态多媒体资源",
            category = RuleCategory.COMMUNITY,
            domains = listOf(
                "discord.com",
                "discord.gg",
                "gateway.discord.gg",
                "cdn.discordapp.com",
                "status.discord.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),
        AccelerationRule(
            id = "twitch",
            name = "Twitch 游戏直播平台",
            description = "加速 Twitch 赛事直播流分发、主播频道与互动弹幕",
            category = RuleCategory.COMMUNITY,
            domains = listOf(
                "twitch.tv",
                "api.twitch.tv",
                "passport.twitch.tv",
                "usher.ttvnw.net"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),
        AccelerationRule(
            id = "spotify",
            name = "Spotify 音乐播放器",
            description = "优化 Spotify 客户端登录连接与音乐专辑封面多媒体加载",
            category = RuleCategory.COMMUNITY,
            domains = listOf(
                "spotify.com",
                "api.spotify.com",
                "i.scdn.co",
                "audio-ak-spotify-com.akamaized.net"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        ),

        // ==================== 7. 系统服务 ====================
        AccelerationRule(
            id = "google_recaptcha",
            name = "Google reCAPTCHA 验证码",
            description = "使用 DoH + TLS 分片或镜像节点加速 reCAPTCHA 验证码加载（仅匹配 recaptcha 域名）",
            category = RuleCategory.TOOLS,
            domains = listOf(
                "recaptcha.net",
                "www.recaptcha.net"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT,
            mirrorHost = "www.gstatic.cn"
        ),
        AccelerationRule(
            id = "onedrive",
            name = "Microsoft OneDrive 网盘",
            description = "优化微软 OneDrive 个人云盘同步连接与照片原图下载速度",
            category = RuleCategory.TOOLS,
            domains = listOf(
                "onedrive.live.com",
                "api.onedrive.com",
                "skyapi.onedrive.live.com"
            ),
            strategy = AccelerationStrategy.DOH_TLS_FRAGMENT
        )
    )

    val allRules = androidx.compose.runtime.mutableStateListOf<AccelerationRule>().apply {
        addAll(defaultRules)
    }

    /**
     * Load persisted rule and engine configurations. Must be called once from Application.onCreate().
     */
    fun initialize(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        val disabled = sp.getStringSet(KEY_DISABLED, emptySet()) ?: emptySet()
        val strategyMap = parseStrategyMap(sp.getString(KEY_STRATEGIES, null))
        val savedCustomRules = parseCustomRules(sp.getString(KEY_CUSTOM_RULES_JSON, null))

        synchronized(lock) {
            allRules.clear()
            allRules.addAll(savedCustomRules)
            allRules.addAll(defaultRules)
            for (i in allRules.indices) {
                val r = allRules[i]
                val enabled = r.id !in disabled
                val customStrat = strategyMap[r.id]
                val resolvedStrat = if (customStrat != null) {
                    if (customStrat == AccelerationStrategy.REVERSE_PROXY_MIRROR && r.mirrorHost == null) {
                        AccelerationStrategy.DOH_TLS_FRAGMENT
                    } else {
                        customStrat
                    }
                } else {
                    r.strategy
                }
                allRules[i] = r.copy(isEnabled = enabled, strategy = resolvedStrat)
            }
        }

        _routingMode.value = RoutingMode.fromName(sp.getString(KEY_ROUTING_MODE, null))
        val provider = DoHProvider.fromId(sp.getString(KEY_DOH_PROVIDER, null))
        _dohProvider.value = provider
        DoHResolver.activeProvider = provider
        _tlsFragmentEnabled.value = sp.getBoolean(KEY_TLS_FRAGMENT, true)
        _tlsDesyncMode.value = TlsDesyncMode.fromName(sp.getString(KEY_TLS_DESYNC_MODE, null))
        _appSplitMode.value = AppSplitMode.fromName(sp.getString(KEY_APP_SPLIT_MODE, null))
        _selectedAppPackages.value = (sp.getStringSet(KEY_APP_SPLIT_PACKAGES, emptySet()) ?: emptySet()).toSet()
        _upstreamProxy.value = UpstreamProxyConfig(
            enabled = sp.getBoolean(KEY_UPSTREAM_ENABLED, false),
            host = sp.getString(KEY_UPSTREAM_HOST, "127.0.0.1") ?: "127.0.0.1",
            port = sp.getInt(KEY_UPSTREAM_PORT, 7890),
            isSocks5 = sp.getBoolean(KEY_UPSTREAM_SOCKS5, true)
        )

        Log.i(
            TAG,
            "Initialized: ${allRules.count { it.isEnabled }}/${allRules.size} rules enabled, mode=${_routingMode.value}, doh=${provider.id}, appSplit=${_appSplitMode.value}"
        )
    }

    fun addCustomRule(
        name: String,
        domainInput: String,
        category: RuleCategory = RuleCategory.TOOLS,
        strategy: AccelerationStrategy = AccelerationStrategy.DOH_TLS_FRAGMENT
    ): Boolean {
        val cleanName = name.trim()
        val domains = domainInput
            .split(',', ';', '\n', ' ')
            .map { it.trim().lowercase().trimEnd('.') }
            .filter { it.isNotEmpty() && it.contains('.') }
            .distinct()
        if (cleanName.isEmpty() || domains.isEmpty()) return false

        val safeStrategy = if (strategy == AccelerationStrategy.REVERSE_PROXY_MIRROR) {
            AccelerationStrategy.DOH_TLS_FRAGMENT
        } else {
            strategy
        }
        val customRule = AccelerationRule(
            id = "custom_${System.currentTimeMillis()}",
            name = cleanName,
            description = "用户自定义规则 · ${domains.joinToString(", ")}",
            category = category,
            domains = domains,
            strategy = safeStrategy,
            isEnabled = true,
            isCustom = true
        )
        synchronized(lock) {
            allRules.add(0, customRule)
        }
        persistCustomRules()
        return true
    }

    fun removeCustomRule(id: String) {
        synchronized(lock) {
            allRules.removeAll { it.id == id && it.isCustom }
        }
        persistCustomRules()
        persistDisabledIds()
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        synchronized(lock) {
            val idx = allRules.indexOfFirst { it.id == id }
            if (idx != -1) {
                allRules[idx] = allRules[idx].copy(isEnabled = enabled)
            }
        }
        persistDisabledIds()
    }

    fun setRuleStrategy(id: String, strategy: AccelerationStrategy) {
        synchronized(lock) {
            val idx = allRules.indexOfFirst { it.id == id }
            if (idx != -1) {
                val rule = allRules[idx]
                val safeStrategy = if (strategy == AccelerationStrategy.REVERSE_PROXY_MIRROR && rule.mirrorHost == null) {
                    AccelerationStrategy.DOH_TLS_FRAGMENT
                } else {
                    strategy
                }
                allRules[idx] = rule.copy(strategy = safeStrategy)
            }
        }
        persistStrategies()
    }

    fun setRoutingMode(mode: RoutingMode) {
        _routingMode.value = mode
        prefs?.edit()?.putString(KEY_ROUTING_MODE, mode.name)?.apply()
    }

    fun setDoHProvider(provider: DoHProvider) {
        _dohProvider.value = provider
        DoHResolver.activeProvider = provider
        DoHResolver.clearCache()
        prefs?.edit()?.putString(KEY_DOH_PROVIDER, provider.id)?.apply()
    }

    fun setTlsFragmentEnabled(enabled: Boolean) {
        _tlsFragmentEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_TLS_FRAGMENT, enabled)?.apply()
    }

    fun setTlsDesyncMode(mode: TlsDesyncMode) {
        _tlsDesyncMode.value = mode
        prefs?.edit()?.putString(KEY_TLS_DESYNC_MODE, mode.name)?.apply()
    }

    fun setAppSplitConfig(mode: AppSplitMode, packages: Set<String>) {
        val clean = packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        _appSplitMode.value = mode
        _selectedAppPackages.value = clean
        prefs?.edit()
            ?.putString(KEY_APP_SPLIT_MODE, mode.name)
            ?.putStringSet(KEY_APP_SPLIT_PACKAGES, clean)
            ?.apply()
    }

    fun setUpstreamProxy(config: UpstreamProxyConfig) {
        val cleanHost = config.host.trim().ifEmpty { "127.0.0.1" }
        val cleanPort = config.port.coerceIn(1, 65535)
        val normalized = config.copy(host = cleanHost, port = cleanPort)
        _upstreamProxy.value = normalized
        prefs?.edit()
            ?.putBoolean(KEY_UPSTREAM_ENABLED, normalized.enabled)
            ?.putString(KEY_UPSTREAM_HOST, normalized.host)
            ?.putInt(KEY_UPSTREAM_PORT, normalized.port)
            ?.putBoolean(KEY_UPSTREAM_SOCKS5, normalized.isSocks5)
            ?.apply()
    }

    fun isRuleEnabled(id: String): Boolean {
        synchronized(lock) {
            return allRules.find { it.id == id }?.isEnabled ?: true
        }
    }

    fun getEnabledCount(): Int {
        synchronized(lock) {
            return allRules.count { it.isEnabled }
        }
    }

    /**
     * Thread-safe snapshot used by packet/proxy IO threads.
     */
    fun snapshotRules(): List<AccelerationRule> = synchronized(lock) {
        allRules.toList()
    }

    fun findMatchingRule(requestedHost: String): AccelerationRule? {
        val hostLower = requestedHost.lowercase().trim().trimEnd('.')
        if (hostLower.isEmpty()) return null
        val rules = snapshotRules()
        for (rule in rules) {
            if (!rule.isEnabled) continue
            for (pattern in rule.domains) {
                if (matchesDomain(hostLower, pattern)) {
                    return rule
                }
            }
        }
        return null
    }

    private fun matchesDomain(host: String, pattern: String): Boolean {
        val pat = pattern.lowercase().trimEnd('.')
        if (pat.startsWith("*.")) {
            val root = pat.substring(2)
            return host == root || host.endsWith(".$root")
        }
        return host == pat || host.endsWith(".$pat")
    }

    private fun persistDisabledIds() {
        val disabledIds = synchronized(lock) {
            allRules.filter { !it.isEnabled }.map { it.id }.toSet()
        }
        prefs?.edit()?.putStringSet(KEY_DISABLED, disabledIds)?.apply()
    }

    private fun persistStrategies() {
        val json = JSONObject()
        synchronized(lock) {
            for (rule in allRules) {
                json.put(rule.id, rule.strategy.name)
            }
        }
        prefs?.edit()?.putString(KEY_STRATEGIES, json.toString())?.apply()
    }

    private fun persistCustomRules() {
        val arr = JSONArray()
        synchronized(lock) {
            for (rule in allRules) {
                if (!rule.isCustom) continue
                val obj = JSONObject().apply {
                    put("id", rule.id)
                    put("name", rule.name)
                    put("category", rule.category.name)
                    put("strategy", rule.strategy.name)
                    val domArr = JSONArray()
                    rule.domains.forEach { domArr.put(it) }
                    put("domains", domArr)
                }
                arr.put(obj)
            }
        }
        prefs?.edit()?.putString(KEY_CUSTOM_RULES_JSON, arr.toString())?.apply()
    }

    private fun parseCustomRules(raw: String?): List<AccelerationRule> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<AccelerationRule>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val name = obj.optString("name")
                val domArr = obj.optJSONArray("domains") ?: continue
                val domains = mutableListOf<String>()
                for (j in 0 until domArr.length()) {
                    val d = domArr.optString(j).trim().lowercase()
                    if (d.isNotEmpty()) domains += d
                }
                if (id.isBlank() || name.isBlank() || domains.isEmpty()) continue
                val cat = RuleCategory.entries.firstOrNull { it.name == obj.optString("category") }
                    ?: RuleCategory.TOOLS
                val strat = AccelerationStrategy.fromName(obj.optString("strategy"))
                    ?: AccelerationStrategy.DOH_TLS_FRAGMENT
                list += AccelerationRule(
                    id = id,
                    name = name,
                    description = "用户自定义规则 · ${domains.joinToString(", ")}",
                    category = cat,
                    domains = domains,
                    strategy = strat,
                    isCustom = true
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseStrategyMap(raw: String?): Map<String, AccelerationStrategy> {
        if (raw.isNullOrBlank()) return emptyMap()
        return try {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, AccelerationStrategy>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                AccelerationStrategy.fromName(json.optString(id))?.let {
                    result[id] = it
                }
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
