package net.steampp.wattkit.proxy

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

data class AccelerationRule(
    val id: String,
    val name: String,
    val description: String,
    val category: RuleCategory,
    val domains: List<String>,
    val targetHost: String?,
    val targetPort: Int = 443,
    var isEnabled: Boolean = true
)

object RuleManager {
    val defaultRules = listOf(
        // ==================== 1. Steam 宇宙 ====================
        AccelerationRule(
            id = "steam_community",
            name = "Steam 社区与个人资料",
            description = "加速 steamcommunity.com 社区主页、讨论区、个人资料、好友列表与库存",
            category = RuleCategory.STEAM,
            domains = listOf("steamcommunity.com", "www.steamcommunity.com"),
            targetHost = "steamcommunity.rmbgame.net",
            targetPort = 443,
            isEnabled = true
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
            targetHost = "steamstatic.rmbgame.net",
            targetPort = 443,
            isEnabled = true
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
            targetHost = "steamstore.rmbgame.net",
            targetPort = 443,
            isEnabled = true
        ),
        AccelerationRule(
            id = "steam_workshop",
            name = "Steam 创意工坊与广播",
            description = "加速游戏创意工坊 Mod 订阅检索、官方公告页面与 Steam 游戏直播流",
            category = RuleCategory.STEAM,
            domains = listOf(
                "steamcommunity.com/workshop",
                "steamserver.net",
                "steambroadcast.akamaized.net"
            ),
            targetHost = "steamcommunity.rmbgame.net",
            targetPort = 443,
            isEnabled = true
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
            targetHost = "pixiv.re",
            targetPort = 443,
            isEnabled = true
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
            targetHost = "i.pixiv.re",
            targetPort = 443,
            isEnabled = true
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
            targetHost = "pixiv.re",
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
        ),

        // ==================== 7. 系统服务 ====================
        AccelerationRule(
            id = "google_recaptcha",
            name = "Google reCAPTCHA 验证码",
            description = "将谷歌人机验证码请求自动转发至国内可用通道 (www.recaptcha.net)",
            category = RuleCategory.TOOLS,
            domains = listOf(
                "www.google.com/recaptcha",
                "google.com/recaptcha",
                "recaptcha.net"
            ),
            targetHost = "www.recaptcha.net",
            targetPort = 443,
            isEnabled = true
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
            targetHost = null,
            targetPort = 443,
            isEnabled = true
        )
    )

    val allRules = androidx.compose.runtime.mutableStateListOf<AccelerationRule>().apply {
        addAll(defaultRules)
    }

    fun setRuleEnabled(id: String, enabled: Boolean) {
        val idx = allRules.indexOfFirst { it.id == id }
        if (idx != -1) {
            allRules[idx] = allRules[idx].copy(isEnabled = enabled)
        }
    }

    fun isRuleEnabled(id: String): Boolean {
        return allRules.find { it.id == id }?.isEnabled ?: true
    }

    fun getEnabledCount(): Int = allRules.count { it.isEnabled }

    fun findMatchingTarget(requestedHost: String, requestedPort: Int): Pair<String, Int>? {
        val hostLower = requestedHost.lowercase().trimEnd('.')
        for (rule in allRules) {
            if (!rule.isEnabled) continue
            for (pattern in rule.domains) {
                if (matchesDomain(hostLower, pattern)) {
                    val destHost = rule.targetHost ?: requestedHost
                    val destPort = if (rule.targetHost != null) rule.targetPort else requestedPort
                    return Pair(destHost, destPort)
                }
            }
        }
        return null
    }

    private fun matchesDomain(host: String, pattern: String): Boolean {
        if (pattern.startsWith("*.")) {
            val root = pattern.substring(2)
            return host == root || host.endsWith(".$root")
        }
        return host == pattern || host.endsWith(".$pattern")
    }
}
