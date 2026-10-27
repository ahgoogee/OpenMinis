package com.openminis.app.hooks

import org.json.JSONObject

/**
 * [T-hooks] Turning hook process output into a [HookDecision], and merging
 * several of them.
 *
 * Pure functions on purpose: this is the part that decides whether a tool call
 * is blocked, so it has unit tests (src/test/.../hooks/HookDecisionTest.kt) and
 * no Android dependency.
 *
 * Exit-code semantics (DESIGN §5.2):
 *   0  → allow. stdout may be JSON with fields, otherwise it is plain text.
 *   2  → block. stdout (first 2 KB) is the reason handed back to the model.
 *   *  → non-blocking error: fail-open, the event proceeds.
 */
object HookDecisions {

    private const val REASON_LIMIT = 2048

    fun fromOutput(out: HookOutput, appendPlainText: Boolean): HookDecision {
        if (out.timedOut) return hookError("timed out after ${out.durationMs}ms")
        if (out.exitCode == 2) {
            return HookDecision(block = true, reason = out.stdout.trim().take(REASON_LIMIT).ifBlank { "blocked by ${out.hookId}" })
        }
        if (out.exitCode != 0) {
            // Deliberately not a block: a hook that crashed must not wedge the
            // turn. It's logged by the runner; the event proceeds.
            return HookDecision()
        }
        val json = parseJson(out.stdout)
            ?: return if (appendPlainText && out.stdout.isNotBlank()) HookDecision(appendContext = out.stdout.trim()) else HookDecision()

        val decision = json.optString("decision", "").lowercase()
        val reason = json.optString("reason", "")
        val args = json.optJSONObject("toolArgs")?.toString()
        val append = json.optString("appendContext", "").ifBlank { null }
        val prompt = json.optString("prompt", "").ifBlank { null }
        return HookDecision(
            block = decision == "block",
            reason = if (decision == "block") reason.take(REASON_LIMIT).ifBlank { "blocked by ${out.hookId}" } else "",
            toolArgsJson = args,
            appendContext = append,
            prompt = prompt,
        )
    }

    /**
     * Merge in configuration order. Any block short-circuits (the runner stops
     * calling further hooks), so callers only merge what actually ran.
     */
    fun merge(decisions: List<HookDecision>): HookDecision {
        if (decisions.isEmpty()) return HookDecision()
        val blocked = decisions.firstOrNull { it.block }
        if (blocked != null) return blocked
        return HookDecision(
            block = false,
            toolArgsJson = decisions.lastOrNull { it.toolArgsJson != null }?.toolArgsJson,
            appendContext = decisions.mapNotNull { it.appendContext?.takeIf { s -> s.isNotBlank() } }
                .takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
            prompt = decisions.lastOrNull { it.prompt != null }?.prompt,
        )
    }

    /** A hook that fails for infrastructural reasons never blocks. */
    internal fun hookError(message: String): HookDecision {
        AppLog.warn("[Hooks] $message — fail-open")
        return HookDecision()
    }

    private fun parseJson(text: String): JSONObject? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching { JSONObject(trimmed) }.getOrNull()
    }
}

/** Tiny indirection so the pure decision code does not import AppLogger (Android). */
internal object AppLog {
    fun warn(message: String) {
        runCatching { com.openminis.app.logging.AppLogger.warning("Hooks", message) }
    }
}
