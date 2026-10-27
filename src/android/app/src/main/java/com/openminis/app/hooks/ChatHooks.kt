package com.openminis.app.hooks

import android.content.Context
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-hooks] The chat-side bridge: builds the payload for each event and calls
 * [Hooks]. Kept out of ChatViewModel so the call sites stay one-liners and the
 * JSON shape lives in exactly one place (DESIGN §5.1).
 */
object ChatHooks {

    fun init(context: Context) = Hooks.init(context)

    // ── decision events (awaited) ────────────────────────────────────────

    suspend fun beforeTool(
        context: Context,
        sessionId: String,
        turn: Int,
        toolName: String,
        argsJson: String,
    ): HookDecision = Hooks.evaluate(
        HookEvent.PreToolUse,
        sessionId,
        JSONObject().apply {
            put("turn", turn)
            put("toolName", toolName)
            put("toolArgsJson", argsJson)
        },
    )

    suspend fun afterTool(
        context: Context,
        sessionId: String,
        turn: Int,
        toolName: String,
        argsJson: String,
        success: Boolean,
        output: String,
    ): HookDecision = Hooks.evaluate(
        HookEvent.PostToolUse,
        sessionId,
        JSONObject().apply {
            put("turn", turn)
            put("toolName", toolName)
            put("toolArgsJson", argsJson)
            put("toolResult", JSONObject().apply {
                put("success", success)
                put("output", output)
            })
        },
    )

    /**
     * PreCompact may add to the focus string. Returns null when nothing was
     * added, so callers can pass the original through untouched.
     */
    suspend fun preCompact(
        sessionId: String,
        tokens: Int,
        window: Int,
        focus: String?,
        trigger: String,
    ): String? {
        val decision = Hooks.evaluate(
            HookEvent.PreCompact,
            sessionId,
            JSONObject().apply {
                put("tokens", tokens)
                put("window", window)
                put("focus", focus ?: "")
                put("trigger", trigger)
            },
        )
        if (decision.block) return focus // compaction cannot be blocked; log only
        val add = decision.appendContext?.takeIf { it.isNotBlank() } ?: return null
        return listOfNotNull(focus?.takeIf { it.isNotBlank() }, add).joinToString("\n\n")
    }

    // ── notification events (fire and forget) ────────────────────────────

    fun postCompact(
        sessionId: String,
        beforeTokens: Int,
        afterTokens: Int,
        messagesCompacted: Int,
        success: Boolean,
        trigger: String,
    ) = Hooks.fire(
        HookEvent.PostCompact,
        sessionId,
        JSONObject().apply {
            put("beforeTokens", beforeTokens)
            put("afterTokens", afterTokens)
            put("messagesCompacted", messagesCompacted)
            put("success", success)
            put("trigger", trigger)
        },
    )

    /**
     * One tick per agent-loop iteration. Deliberately tiny (DESIGN §12.1): the
     * script fetches the body itself from the turn ledger by `sessionId`, and
     * the app keeps its own throttle so a tick can never become a cost centre.
     */
    fun afterAssistantOutput(
        sessionId: String,
        turn: Int,
        assistantChars: Int,
        messageCount: Int,
        toolCalls: Int,
        textProduced: Boolean,
    ) = Hooks.fire(
        HookEvent.AfterAssistantOutput,
        sessionId,
        JSONObject().apply {
            put("turn", turn)
            put("iteration", turn)
            put("assistantChars", assistantChars)
            put("messageCount", messageCount)
            put("toolCalls", toolCalls)
            put("textProduced", textProduced)
            put("ts", System.currentTimeMillis() / 1000)
        },
    )

    fun stop(sessionId: String, turns: Int, messageCount: Int, cancelled: Boolean) {
        Hooks.fire(
            HookEvent.Stop,
            sessionId,
            JSONObject().apply {
                put("turns", turns)
                put("messageCount", messageCount)
                put("cancelled", cancelled)
                put("ts", System.currentTimeMillis() / 1000)
            },
        )
        Hooks.resetSession(sessionId)
    }

    fun sessionStart(sessionId: String, isDraft: Boolean, model: String?) = Hooks.fire(
        HookEvent.SessionStart,
        sessionId,
        JSONObject().apply {
            put("isDraft", isDraft)
            put("model", model ?: "")
            put("ts", System.currentTimeMillis() / 1000)
        },
    )

    /** Convenience for the Settings screen: run one event's hooks against a hand-made payload. */
    suspend fun dryRun(sessionId: String, payload: JSONObject): HookDecision {
        val event = HookEvent.fromWire(payload.optString("event")) ?: return HookDecision()
        return Hooks.evaluate(event, sessionId, payload)
    }

    internal fun tagsFor(sessionId: String): JSONArray = JSONArray().apply {
        put("session:$sessionId")
        put("date:" + java.time.LocalDate.now().toString())
    }
}
