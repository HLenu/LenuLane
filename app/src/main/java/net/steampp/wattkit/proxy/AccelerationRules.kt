package net.steampp.wattkit.proxy

data class AccelerationRule(
    val id: String,
    val name: String,
    val description: String,
    val domains: List<String>,
    val targetHost: String?,
    val targetPort: Int = 443,
    var isEnabled: Boolean = true
)

object RuleManager {
    val defaultRules = listOf(
        AccelerationRule(
            id = "steam_community",
            name = "Steam 社区与个人资料",
            description = "加速 steamcommunity.com 社区、讨论区、库存与创意工坊",
            domains = listOf("steamcommunity.com", "www.steamcommunity.com"),
            targetHost = "steamcommunity.rmbgame.net",
            targetPort = 443,
            isEnabled = true
        ),
        AccelerationRule(
            id = "steam_static",
            name = "Steam 图片与静态资源",
            description = "加速社区头像、游戏图片与好友静态资源",
            domains = listOf(
                "community.steamstatic.com",
                "avatars.steamstatic.com",
                "avatars.akamai.steamstatic.com",
                "community.akamai.steamstatic.com",
                "steamcdn-a.akamaihd.net",
                "steamuserimages-a.akamaihd.net"
            ),
            targetHost = "steamstatic.rmbgame.net",
            targetPort = 443,
            isEnabled = true
        ),
        AccelerationRule(
            id = "steam_store",
            name = "Steam 商店与登录",
            description = "加速 store.steampowered.com 商店页面与登录服务",
            domains = listOf(
                "store.steampowered.com",
                "api.steampowered.com",
                "help.steampowered.com",
                "login.steampowered.com"
            ),
            targetHost = "steamstore.rmbgame.net",
            targetPort = 443,
            isEnabled = true
        ),
        AccelerationRule(
            id = "github",
            name = "GitHub 网页与代码下载",
            description = "加速 github.com、api.github.com 及 Releases 镜像",
            domains = listOf(
                "github.com",
                "api.github.com",
                "raw.githubusercontent.com",
                "codeload.github.com",
                "github.githubassets.com"
            ),
            targetHost = null, // Direct transparent passthrough with optimized DNS
            targetPort = 443,
            isEnabled = true
        )
    )

    fun findMatchingTarget(requestedHost: String, requestedPort: Int): Pair<String, Int>? {
        val hostLower = requestedHost.lowercase().trimEnd('.')
        for (rule in defaultRules) {
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
