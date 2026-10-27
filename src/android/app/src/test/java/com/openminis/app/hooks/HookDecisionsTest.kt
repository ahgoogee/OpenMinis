package com.openminis.app.hooks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-hooks] The merge rules decide whether a tool call is refused, so they are
 * tested without Android: [HookDecisions] takes plain values and returns plain
 * values, and every case here stays off the org.json paths (those are exercised
 * on device through `minis-hooks test`).
 */
class HookDecisionsTest {

    private fun out(exit: Int, stdout: String = "", timedOut: Boolean = false) =
        HookOutput("h", exit, stdout, "", 5, timedOut)

    @Test
    fun `exit 2 blocks and carries the reason`() {
        val d = HookDecisions.fromOutput(out(2, "/etc 需要确认"), appendPlainText = true)
        assertTrue(d.block)
        assertEquals("/etc 需要确认", d.reason)
    }

    @Test
    fun `exit 2 with no output still blocks`() {
        val d = HookDecisions.fromOutput(out(2, "   "), appendPlainText = true)
        assertTrue(d.block)
        assertTrue(d.reason.contains("h"))
    }

    @Test
    fun `other non-zero exits fail open`() {
        val d = HookDecisions.fromOutput(out(1, "boom"), appendPlainText = true)
        assertFalse(d.block)
        assertNull(d.appendContext)
    }

    @Test
    fun `timeout fails open`() {
        val d = HookDecisions.fromOutput(out(-1, "", timedOut = true), appendPlainText = true)
        assertFalse(d.block)
    }

    @Test
    fun `plain text appends only where the event wants it`() {
        assertEquals("formatted", HookDecisions.fromOutput(out(0, "formatted"), appendPlainText = true).appendContext)
        assertNull(HookDecisions.fromOutput(out(0, "formatted"), appendPlainText = false).appendContext)
    }

    @Test
    fun `block wins over everything else`() {
        val merged = HookDecisions.merge(
            listOf(
                HookDecision(appendContext = "a"),
                HookDecision(block = true, reason = "no"),
                HookDecision(appendContext = "b"),
            ),
        )
        assertTrue(merged.block)
        assertEquals("no", merged.reason)
    }

    @Test
    fun `append contexts join in order and last toolArgs wins`() {
        val merged = HookDecisions.merge(
            listOf(
                HookDecision(appendContext = "first"),
                HookDecision(toolArgsJson = """{"a":1}"""),
                HookDecision(appendContext = "second", toolArgsJson = """{"a":2}"""),
            ),
        )
        assertFalse(merged.block)
        assertEquals("first\n\nsecond", merged.appendContext)
        assertEquals("""{"a":2}""", merged.toolArgsJson)
    }

    @Test
    fun `empty input is neutral`() {
        assertTrue(HookDecisions.merge(emptyList()).isNeutral)
    }

    @Test
    fun `matcher defaults to everything and anchors`() {
        val plain = HookEntry("id", HookEvent.PreToolUse, null, "/x.sh", 10, 0, true, "user")
        assertTrue(plain.matchesTool("any_tool"))

        val narrow = HookEntry("id", HookEvent.PreToolUse, "file_(write|edit)", "/x.sh", 10, 0, true, "user")
        assertTrue(narrow.matchesTool("file_write"))
        assertFalse(narrow.matchesTool("file_write_extra")) // anchored, not substring
        assertFalse(narrow.matchesTool("shell_execute"))

        // matcher on a non-tool event is ignored, not an error.
        val stop = HookEntry("id", HookEvent.Stop, "file_write", "/x.sh", 10, 0, true, "user")
        assertTrue(stop.matchesTool("anything"))
    }

    @Test
    fun `channel events are non blocking and decision events are`() {
        assertTrue(HookEvent.PreToolUse.blocking)
        assertTrue(HookEvent.PostToolUse.blocking)
        assertFalse(HookEvent.AfterAssistantOutput.blocking)
        assertFalse(HookEvent.PostCompact.blocking)
    }
}
