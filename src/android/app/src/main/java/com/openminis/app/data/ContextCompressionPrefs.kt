package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-ctx-compression-config] User-facing settings for the context-compaction
 * system, surfaced as Settings > Agent Runtime > Context Compaction.
 *
 * The built-in hard compaction (ContextPolicy's threshold table, and the
 * automatic pass at the compact line) is not configured here and does not
 * change. This object holds the soft-compaction line: a line below the hard
 * threshold whose crossing appends a runtime reminder to the agent's history,
 * inviting the model to call `compact_context`.
 *
 * Stored with the same SharedPreferences + primed-cache arrangement as
 * [AutoCompactPrefs] and [FastModePrefs], so the agent loop can read the
 * values without an Activity context.
 */
object ContextCompressionPrefs {

    private const val PREFS = "minis_context_compression_prefs"
    private const val KEY_SOFT_ENABLED = "softCompactEnabled"
    private const val KEY_SOFT_PERCENT = "softCompactPercent"

    /** Soft reminders are on by default: the whole point is to warn early. */
    const val DEFAULT_SOFT_ENABLED = true

    /**
     * Default soft line, as a percentage of the model's context window.
     *
     * Sits below the hard compact threshold, which for a 128K+ window is
     * `window - 20K` (≈84%): a compaction needs its own model call plus
     * headroom for the re-appended recent turns.
     */
    const val DEFAULT_SOFT_PERCENT = 65

    const val MIN_SOFT_PERCENT = 20
    const val MAX_SOFT_PERCENT = 90

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedSoftEnabled: Boolean = DEFAULT_SOFT_ENABLED

    @Volatile
    private var cachedSoftPercent: Int = DEFAULT_SOFT_PERCENT

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Capture the app context and warm the caches. Called from MinisApp.onCreate. */
    fun prime(context: Context) {
        appContext = context.applicationContext
        val p = prefs(context)
        cachedSoftEnabled = p.getBoolean(KEY_SOFT_ENABLED, DEFAULT_SOFT_ENABLED)
        cachedSoftPercent = p.getInt(KEY_SOFT_PERCENT, DEFAULT_SOFT_PERCENT)
            .coerceIn(MIN_SOFT_PERCENT, MAX_SOFT_PERCENT)
    }

    // ---------------------------------------------------------------- reads

    /** Context-free read; safe before [prime] runs (returns the defaults). */
    fun isSoftCompactEnabled(): Boolean = cachedSoftEnabled

    /** Soft line as a percentage of the context window, clamped to the UI range. */
    fun softCompactPercent(): Int = cachedSoftPercent

    /** The soft line in tokens for a given window; 0 when disabled or unknown. */
    fun softCompactThreshold(contextWindow: Int): Int {
        if (!cachedSoftEnabled || contextWindow <= 0) return 0
        return (contextWindow.toLong() * cachedSoftPercent / 100L).toInt()
    }

    // --------------------------------------------------------------- writes

    fun setSoftCompactEnabled(context: Context, enabled: Boolean) {
        cachedSoftEnabled = enabled
        prefs(context).edit().putBoolean(KEY_SOFT_ENABLED, enabled).apply()
    }

    /** Context-free variant for callers that already have the app context primed. */
    fun setSoftCompactEnabled(enabled: Boolean) {
        cachedSoftEnabled = enabled
        appContext?.let { prefs(it).edit().putBoolean(KEY_SOFT_ENABLED, enabled).apply() }
    }

    fun setSoftCompactPercent(context: Context, percent: Int) {
        val clamped = percent.coerceIn(MIN_SOFT_PERCENT, MAX_SOFT_PERCENT)
        cachedSoftPercent = clamped
        prefs(context).edit().putInt(KEY_SOFT_PERCENT, clamped).apply()
    }
}
