package com.openminis.app.hooks

/**
 * [T-hooks] Event names and the entries that listen to them.
 *
 * See /var/minis/shared/openminis-hooks/DESIGN.md §3 for the semantics of each
 * event. The [blocking] flag is a property of the EVENT, not of a config entry:
 * a decision event must be awaited (the caller has to know the answer before it
 * continues), a notification event must never be.
 */
enum class HookEvent(val wire: String, val blocking: Boolean) {
    SessionStart("SessionStart", false),
    UserPromptSubmit("UserPromptSubmit", true),
    PreToolUse("PreToolUse", true),
    PostToolUse("PostToolUse", true),
    PreCompact("PreCompact", true),
    PostCompact("PostCompact", false),
    AfterAssistantOutput("AfterAssistantOutput", false),
    Stop("Stop", false);

    companion object {
        fun fromWire(value: String): HookEvent? =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) }
    }
}

/** The events whose matcher is tested against a tool name. */
internal val TOOL_EVENTS = setOf(HookEvent.PreToolUse, HookEvent.PostToolUse)

/**
 * One configured hook.
 *
 * [invalid] is set by the loader when this particular entry cannot be used
 * (bad regex, missing script, duplicate id). A broken entry is disabled on its
 * own — one typo must never take the whole hooks layer down.
 */
data class HookEntry(
    val id: String,
    val event: HookEvent,
    val matcherSource: String?,
    val command: String,
    val timeoutSec: Int,
    val minIntervalSec: Int,
    val enabled: Boolean,
    val origin: String,
    val updatedAt: String? = null,
    val invalid: String? = null,
) {
    val matcher: Regex? by lazy {
        matcherSource?.takeIf { it.isNotBlank() }?.let { runCatching { Regex("^(?:$it)$") }.getOrNull() }
    }

    fun matchesTool(toolName: String): Boolean {
        if (!TOOL_EVENTS.contains(event)) return true
        val re = matcher ?: return true
        return re.matches(toolName)
    }
}

/**
 * Everything loaded from hooks.json plus the state needed to decide whether it
 * has to be re-read. [error] non-null means the FILE was unusable (bad JSON,
 * missing): no hook runs at all — a half-applied config is harder to diagnose
 * than none.
 */
data class HookConfig(
    val entries: List<HookEntry> = emptyList(),
    val error: String? = null,
    val fingerprint: String = "",
) {
    fun forEvent(event: HookEvent): List<HookEntry> =
        entries.filter { it.event == event && it.enabled && it.invalid == null }

    val runnableCount: Int get() = entries.count { it.enabled && it.invalid == null }
}

/**
 * The merged outcome of however many hooks answered one event.
 *
 * Blocking is a one-way door: any hook that says block wins, and later hooks
 * are not run (DESIGN §4). [toolArgsJson] is the rewritten argument object of
 * the last hook that supplied one; [appendContext] is everything appended, in
 * hook order.
 */
data class HookDecision(
    val block: Boolean = false,
    val reason: String = "",
    val toolArgsJson: String? = null,
    val appendContext: String? = null,
    val prompt: String? = null,
) {
    val isNeutral: Boolean get() = !block && appendContext.isNullOrBlank() && toolArgsJson == null && prompt == null
}

/** What one hook process said. Kept separate from [HookDecision] so the merge is testable. */
data class HookOutput(
    val hookId: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean = false,
) {
    /** Exit 2 is the shell-native way to block, and needs no JSON at all. */
    val blocks: Boolean get() = !timedOut && exitCode == 2
}
