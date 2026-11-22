package dev.aegis.browser.privacy

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 规则加载器：优先读取用户手动更新后的规则文件，
 * 否则读取打包在 assets 中的内置规则。
 */
object RuleLoader {

    private const val ASSET_PATH = "filters/default.filter"
    const val UPDATED_NAME = "updated.filter"

    fun load(context: Context): FilterEngine {
        val domains = HashSet<String>(100_000)
        val updated = File(context.filesDir, UPDATED_NAME)

        val stream: InputStream = if (updated.exists()) {
            FileInputStream(updated)
        } else {
            context.assets.open(ASSET_PATH)
        }

        stream.bufferedReader().use { reader ->
            reader.forEachLine { line ->
                val domain = line.trim().lowercase()
                if (domain.isNotEmpty() && !domain.startsWith('#')) {
                    domains.add(domain)
                }
            }
        }
        return FilterEngine(domains)
    }
}
