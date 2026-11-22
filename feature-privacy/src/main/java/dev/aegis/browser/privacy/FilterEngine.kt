package dev.aegis.browser.privacy

/**
 * 域名规则匹配器。规则域名命中自身及其所有子域，
 * 通过逐级回退父域查询实现，无需通配符。
 */
class FilterEngine(domains: Set<String>) {

    private val blocked: HashSet<String> = HashSet<String>(domains.size * 2).apply { addAll(domains) }

    val ruleCount: Int get() = blocked.size

    fun isBlocked(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        if (blocked.contains(normalized)) return true

        var dot = normalized.indexOf('.')
        while (dot >= 0) {
            val parent = normalized.substring(dot + 1)
            if (blocked.contains(parent)) return true
            dot = normalized.indexOf('.', dot + 1)
        }
        return false
    }
}
