package com.openminis.app.hooks

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-hooks] Reads `hooks.json` and decides which entries are usable.
 *
 * The file is the single source of truth (DESIGN §4.0): there is no cached copy
 * in SharedPreferences and no in-app second version. Reading is therefore
 * cheap-by-fingerprint — `mtime:size` unchanged means the previously compiled
 * [HookConfig] is still valid — but it is always re-derived from the file, never
 * from app state.
 *
 * Validation happens here, once per reload, and only individual entries are
 * disabled for their own mistakes. A file that cannot be parsed at all yields
 * [HookConfig.error] and NO runnable hooks: half-applying a broken config is
 * harder to diagnose than not running anything.
 */
object HookConfigLoader {

    private const val TAG = "Hooks"

    const val DEFAULT_TIMEOUT_SEC = 10
    const val DEFAULT_ASYNC_INTERVAL_SEC = 5
    private const val MAX_TIMEOUT_SEC = 300
    private const val MAX_INTERVAL_SEC = 3600

    /** `/var/minis/shared/hooks/hooks.json`, reached through the global mount. */
    fun configFile(context: Context): File =
        File(context.filesDir, "minis-global/shared/hooks/hooks.json")

    /** Hook payload scratch space; inside `/var/minis/shared` so the sandbox sees it too. */
    fun tmpDir(context: Context): File =
        File(context.filesDir, "minis-global/shared/.hooks").also { it.mkdirs() }

    fun fingerprint(file: File): String =
        if (file.isFile) "${file.lastModified()}:${file.length()}" else "absent"

    /**
     * Map a guest (`/var/minis/...`) path to its host path so the loader can
     * check the script really exists. Returns null when the path is outside the
     * two mounts we can see from the app — those cannot be verified and are
     * therefore accepted as-is.
     */
    fun guestToHost(context: Context, guestPath: String): File? {
        val globalBase = File(context.filesDir, "minis-global")
        val map = listOf(
            "/var/minis/shared" to File(globalBase, "shared"),
            "/var/minis/memory" to File(globalBase, "memory"),
            "/var/minis/skills" to File(globalBase, "skills"),
            "/var/minis/mcp-servers" to File(globalBase, "mcp-servers"),
            "/var/minis/workspace" to File(context.filesDir, "minis-sessions/__hook_workspace__"),
        )
        for ((guest, host) in map) {
            if (guestPath == guest) return host
            if (guestPath.startsWith("$guest/")) return File(host, guestPath.removePrefix("$guest/"))
        }
        return null
    }

    fun load(context: Context): HookConfig {
        val file = configFile(context)
        if (!file.isFile) {
            return HookConfig(error = null, fingerprint = "absent") // no config = no hooks, not an error
        }
        val text = runCatching { file.readText() }.getOrElse {
            return HookConfig(error = "读取失败：${it.message}", fingerprint = fingerprint(file))
        }
        val fp = fingerprint(file)
        return runCatching { parse(context, text, fp) }
            .getOrElse { HookConfig(error = "JSON 解析失败：${it.message}", fingerprint = fp) }
    }

    private fun parse(context: Context, text: String, fp: String): HookConfig {
        val root = JSONObject(text)
        val hooksNode = root.optJSONObject("hooks") ?: root // tolerate a bare event map
        val entries = mutableListOf<HookEntry>()
        val seenIds = mutableSetOf<String>()

        for (eventName in hooksNode.keys()) {
            val event = HookEvent.fromWire(eventName)
            if (event == null) {
                AppLog.warn("$TAG: 未知事件 \"$eventName\"，整组忽略")
                continue
            }
            val arr: JSONArray = hooksNode.optJSONArray(eventName) ?: continue
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id", "").ifBlank { "${eventName}#$i" }
                var invalid: String? = null
                if (!seenIds.add(id)) invalid = "id 重复（先出现的生效）"

                val command = obj.optString("command", "").trim()
                if (command.isEmpty()) invalid = invalid ?: "缺少 command"
                if (command.startsWith("/var/minis/")) {
                    val host = guestToHost(context, command)
                    if (host != null && !host.isFile) invalid = invalid ?: "脚本不存在：$command"
                }

                val matcherSource = obj.optString("matcher", "").takeIf { it.isNotBlank() }
                if (matcherSource != null && runCatching { Regex("^(?:$matcherSource)$") }.isFailure) {
                    invalid = invalid ?: "matcher 正则无法编译"
                }

                val isAsync = !event.blocking
                val timeout = obj.optInt("timeoutSec", if (event == HookEvent.PostToolUse) 30 else DEFAULT_TIMEOUT_SEC)
                    .coerceIn(1, MAX_TIMEOUT_SEC)
                val interval = obj.optInt("minIntervalSec", if (isAsync) DEFAULT_ASYNC_INTERVAL_SEC else 0)
                    .coerceIn(0, MAX_INTERVAL_SEC)

                entries += HookEntry(
                    id = id,
                    event = event,
                    matcherSource = matcherSource,
                    command = command,
                    timeoutSec = timeout,
                    minIntervalSec = interval,
                    enabled = obj.optBoolean("enabled", true),
                    origin = obj.optString("origin", "user"),
                    updatedAt = obj.optString("updatedAt", "").ifBlank { null },
                    invalid = invalid,
                )
            }
        }
        return HookConfig(entries = entries, error = null, fingerprint = fp)
    }

    /** Serialize back to the on-disk shape. Used by the Settings screen / CLI writer. */
    fun toJson(config: HookConfig): String {
        val hooks = JSONObject()
        HookEvent.entries.forEach { event ->
            val list = config.entries.filter { it.event == event }
            if (list.isEmpty()) return@forEach
            hooks.put(event.wire, JSONArray().apply {
                list.forEach { e ->
                    put(JSONObject().apply {
                        put("id", e.id)
                        e.matcherSource?.let { put("matcher", it) }
                        put("command", e.command)
                        put("timeoutSec", e.timeoutSec)
                        if (e.minIntervalSec > 0) put("minIntervalSec", e.minIntervalSec)
                        put("enabled", e.enabled)
                        put("origin", e.origin)
                        e.updatedAt?.let { put("updatedAt", it) }
                    })
                }
            })
        }
        return JSONObject().put("version", 1).put("hooks", hooks).toString(2)
    }
}
