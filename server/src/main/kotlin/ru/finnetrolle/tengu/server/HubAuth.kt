package ru.finnetrolle.tengu.server

/**
 * Auth хаба: bearer-токен → userId. SSO/OIDC заменит реализацию, не протокол:
 * пользователь всегда приходит как userId.
 */
interface HubAuth {
    fun userIdFor(token: String?): String?
}

/** MVP-реализация: статичные bearer-токены из TENGU_HUB_TOKENS="jdoe=h-abc123,...". */
class StaticTokenHubAuth(private val tokens: Map<String, String>) : HubAuth {

    override fun userIdFor(token: String?): String? = token?.let { tokens[it] }

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): StaticTokenHubAuth {
            val raw = env["TENGU_HUB_TOKENS"].orEmpty()
            val map = raw.split(',').mapNotNull { entry ->
                val idx = entry.indexOf('=')
                if (idx <= 0) null
                else entry.substring(idx + 1).trim() to entry.substring(0, idx).trim()
            }.toMap()
            return StaticTokenHubAuth(map)
        }
    }
}
