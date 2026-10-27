package com.openminis.app.hooks

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.ShellExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * [T-hooks] Runs the configured scripts (DESIGN §6).
 *
 * Everything here is deliberately unremarkable: no new execution engine, no
 * pipe protocol. A hook is a command handed to the same PRoot shell the model's
 * own `shell_execute` uses, with stdout/stderr/exit-code redirected to files so
 * the three are separable (ShellExecutor merges the streams).
 *
 * Failure policy: fail-open, always. A hook that crashes, hangs or prints
 * garbage must never wedge a turn — the event simply proceeds.
 */
object Hooks {

    private const val TAG = "Hooks"
    private const val MAX_OUTPUT_BYTES = 8 * 1024

    @Volatile private var appContext: Context? = null
    @Volatile private var cached: HookConfig = HookConfig()

    /** hookId|sessionId → in-flight marker, so an async event never queues up. */
    private val inFlight = ConcurrentHashMap<String, Unit>()
    private val lastFired = ConcurrentHashMap<String, Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** Current config, re-read only when the file fingerprint changed. */
    fun config(): HookConfig = appContext?.let { config(it) } ?: cached

    fun config(context: Context): HookConfig {
        val file = HookConfigLoader.configFile(context)
        val fp = HookConfigLoader.fingerprint(file)
        val current = cached
        if (fp == current.fingerprint) return current
        val fresh = HookConfigLoader.load(context)
        cached = fresh
        if (fresh.error != null) AppLogger.warning(TAG, "hooks.json 不可用，全部停用：${fresh.error}")
        else AppLogger.info(TAG, "hooks.json 已加载：${fresh.runnableCount} 条可用 / ${fresh.entries.size} 条")
        return fresh
    }

    /** Force a re-read (Settings' "重新加载"). */
    fun reload(context: Context): HookConfig {
        cached = HookConfig()
        return config(context)
    }

    /**
     * Run every hook for a blocking event and merge the answers. Returns a
     * neutral decision when nothing is configured, when the sandbox is not up,
     * or when everything failed.
     */
    suspend fun evaluate(
        event: HookEvent,
        sessionId: String,
        payload: JSONObject,
    ): HookDecision = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext HookDecision()
        val entries = config(ctx).forEvent(event).filter { it.matchesTool(payload.optString("toolName")) }
        if (entries.isEmpty()) return@withContext HookDecision()
        if (!PRootKernel.isBooted) {
            AppLogger.debug(TAG, "$event skipped: sandbox not booted")
            return@withContext HookDecision()
        }
        val outputs = mutableListOf<HookDecision>()
        for (entry in entries) {
            val out = runOne(ctx, entry, sessionId, payload) ?: continue
            val decision = HookDecisions.fromOutput(out, appendPlainText = event == HookEvent.PostToolUse)
            outputs += decision
            if (decision.block) {
                AppLogger.info(TAG, "$event blocked by ${entry.id}: ${decision.reason.take(120)}")
                return@withContext HookDecisions.merge(outputs)
            }
        }
        HookDecisions.merge(outputs)
    }

    /**
     * Fire a notification event. Never awaited by the caller, never queued:
     * `minIntervalSec` throttles the process spawn, and single-flight drops a
     * tick while the previous one for the same hook+session is still running
     * (DESIGN §12.2). Both are protective, not semantic — what "should" happen
     * is decided inside the script.
     */
    fun fire(event: HookEvent, sessionId: String, payload: JSONObject) {
        val ctx = appContext ?: return
        val entries = config(ctx).forEvent(event)
        if (entries.isEmpty()) return
        if (!PRootKernel.isBooted) return
        for (entry in entries) {
            val key = "${entry.id}|$sessionId"
            val now = System.currentTimeMillis()
            val last = lastFired[key] ?: 0L
            if (entry.minIntervalSec > 0 && now - last < entry.minIntervalSec * 1000L) continue
            if (inFlight.putIfAbsent(key, Unit) != null) continue
            lastFired[key] = now
            scope.launch {
                try {
                    runOne(ctx, entry, sessionId, payload)
                } catch (t: Throwable) {
                    AppLogger.warning(TAG, "${entry.id} 失败：${t.message}")
                } finally {
                    inFlight.remove(key)
                }
            }
        }
    }

    /** Forget per-session throttle/single-flight state (called when a turn ends). */
    fun resetSession(sessionId: String) {
        lastFired.keys.removeAll { it.endsWith("|$sessionId") }
    }

    private suspend fun runOne(
        context: Context,
        entry: HookEntry,
        sessionId: String,
        payload: JSONObject,
    ): HookOutput? {
        val tmp = HookConfigLoader.tmpDir(context)
        val token = UUID.randomUUID().toString().take(8)
        val hostPayload = File(tmp, "p-$token.json")
        val hostOut = File(tmp, "o-$token.txt")
        val hostErr = File(tmp, "e-$token.txt")
        val hostCode = File(tmp, "c-$token.txt")
        return try {
            val body = JSONObject(payload.toString()).apply {
                put("hookId", entry.id)
                put("event", entry.event.wire)
                put("sessionId", sessionId)
            }
            hostPayload.writeText(body.toString())

            val guestPayload = "/var/minis/shared/.hooks/${hostPayload.name}"
            val guestOut = "/var/minis/shared/.hooks/${hostOut.name}"
            val guestErr = "/var/minis/shared/.hooks/${hostErr.name}"
            val guestCode = "/var/minis/shared/.hooks/${hostCode.name}"

            // Redirect to files instead of parsing ShellExecutor's merged stream:
            // a script that writes one line to stderr must not corrupt the JSON
            // contract on stdout.
            val command = buildString {
                append("cd /var/minis/workspace 2>/dev/null || true; ")
                append(entry.command)
                append(" < ").append(guestPayload)
                append(" > ").append(guestOut)
                append(" 2> ").append(guestErr)
                append("; echo $? > ").append(guestCode)
            }
            val started = System.currentTimeMillis()
            val result = ShellExecutor.execute(
                context = context,
                command = command,
                timeout = entry.timeoutSec * 1000L,
                environment = mapOf(
                    "MINIS_HOOK_EVENT" to entry.event.wire,
                    "MINIS_HOOK_ID" to entry.id,
                    "MINIS_HOOK_PAYLOAD" to guestPayload,
                    "MINIS_SESSION_ID" to sessionId,
                    "MINIS_IN_HOOK" to "1",
                ),
            )
            val elapsed = System.currentTimeMillis() - started
            val codeText = hostCode.takeIf { it.isFile }?.readText()?.trim()
            val timedOut = codeText.isNullOrBlank() && elapsed >= entry.timeoutSec * 1000L - 250
            val stdout = hostOut.takeIf { it.isFile }?.let { readCapped(it) } ?: ""
            val stderr = hostErr.takeIf { it.isFile }?.let { readCapped(it) } ?: result.output.take(MAX_OUTPUT_BYTES)
            val exit = codeText?.toIntOrNull() ?: if (timedOut) -1 else result.exitCode

            if (entry.event.blocking) {
                AppLogger.debug(TAG, "${entry.id} ${entry.event.wire} exit=$exit ${elapsed}ms stdout=${stdout.take(80)}")
            }
            HookOutput(entry.id, exit, stdout, stderr, elapsed, timedOut)
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "${entry.id} 执行异常：${t.message}")
            null
        } finally {
            listOf(hostPayload, hostOut, hostErr, hostCode).forEach { runCatching { it.delete() } }
        }
    }

    private fun readCapped(file: File): String {
        val text = runCatching { file.readText() }.getOrDefault("")
        return if (text.length > MAX_OUTPUT_BYTES) text.take(MAX_OUTPUT_BYTES) else text
    }
}
