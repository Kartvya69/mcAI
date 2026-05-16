package dev.mcai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PowerActionsTest {
    @Test
    fun `immediate stop invokes native stop operation`() {
        val executor = RecordingPowerActionExecutor()
        val runner = RecordingPowerActionRunner()
        val powerActions = PowerActions(executor, runner)

        val result = powerActions.perform(action = "stop", reason = null, delaySeconds = 0)

        assertEquals("stop", result.action)
        assertFalse(result.scheduled)
        assertEquals(0, result.delaySeconds)
        assertFalse(result.replacedPendingAction)
        assertNull(result.reason)
        assertEquals(listOf<String?>(null), executor.stopReasons)
        assertEquals(emptyList(), executor.restartReasons)
        assertEquals(1, runner.runNowCount)
    }

    @Test
    fun `immediate restart invokes native restart operation with trimmed reason`() {
        val executor = RecordingPowerActionExecutor()
        val powerActions = PowerActions(executor, RecordingPowerActionRunner())

        val result = powerActions.perform(action = "restart", reason = "  maintenance window  ", delaySeconds = 0)

        assertEquals("restart", result.action)
        assertFalse(result.scheduled)
        assertEquals("maintenance window", result.reason)
        assertEquals(emptyList(), executor.stopReasons)
        assertEquals(listOf<String?>("maintenance window"), executor.restartReasons)
    }

    @Test
    fun `blank or invalid action is rejected`() {
        val powerActions = PowerActions(RecordingPowerActionExecutor(), RecordingPowerActionRunner())

        assertFailsWith<IllegalArgumentException> {
            powerActions.perform(action = "", reason = null, delaySeconds = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            powerActions.perform(action = "reload", reason = null, delaySeconds = 0)
        }
    }

    @Test
    fun `negative and over limit delays are rejected`() {
        val powerActions = PowerActions(RecordingPowerActionExecutor(), RecordingPowerActionRunner())

        assertFailsWith<IllegalArgumentException> {
            powerActions.perform(action = "stop", reason = null, delaySeconds = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            powerActions.perform(action = "restart", reason = null, delaySeconds = 601)
        }
    }

    @Test
    fun `delayed action schedules without immediate execution`() {
        val executor = RecordingPowerActionExecutor()
        val runner = RecordingPowerActionRunner()
        val powerActions = PowerActions(executor, runner)

        val result = powerActions.perform(action = "stop", reason = "deploy", delaySeconds = 30)

        assertEquals("stop", result.action)
        assertTrue(result.scheduled)
        assertEquals(30, result.delaySeconds)
        assertFalse(result.replacedPendingAction)
        assertEquals("deploy", result.reason)
        assertEquals(emptyList(), executor.stopReasons)
        assertEquals(1, runner.delayedTasks.size)
        assertEquals(30, runner.delayedTasks.single().delaySeconds)

        runner.delayedTasks.single().fire()

        assertEquals(listOf<String?>("deploy"), executor.stopReasons)
    }

    @Test
    fun `second delayed action replaces the first pending action`() {
        val executor = RecordingPowerActionExecutor()
        val runner = RecordingPowerActionRunner()
        val powerActions = PowerActions(executor, runner)

        val first = powerActions.perform(action = "stop", reason = "first", delaySeconds = 60)
        val second = powerActions.perform(action = "restart", reason = "second", delaySeconds = 15)

        assertFalse(first.replacedPendingAction)
        assertTrue(second.replacedPendingAction)
        assertTrue(runner.delayedTasks.first().cancelled)

        runner.delayedTasks.first().fire()
        assertEquals(emptyList(), executor.stopReasons)
        assertEquals(emptyList(), executor.restartReasons)

        runner.delayedTasks.last().fire()
        assertEquals(emptyList(), executor.stopReasons)
        assertEquals(listOf<String?>("second"), executor.restartReasons)
    }

    private class RecordingPowerActionExecutor : NativePowerActionExecutor {
        val stopReasons = mutableListOf<String?>()
        val restartReasons = mutableListOf<String?>()

        override fun stop(reason: String?) {
            stopReasons += reason
        }

        override fun restart(reason: String?) {
            restartReasons += reason
        }
    }

    private class RecordingPowerActionRunner : PowerActionRunner {
        val delayedTasks = mutableListOf<RecordingPowerActionTask>()
        var runNowCount = 0

        override fun runNow(action: () -> Unit) {
            runNowCount += 1
            action()
        }

        override fun runLater(delaySeconds: Int, action: () -> Unit): PowerActionTask {
            return RecordingPowerActionTask(delaySeconds, action).also { delayedTasks += it }
        }
    }

    private class RecordingPowerActionTask(
        val delaySeconds: Int,
        private val action: () -> Unit,
    ) : PowerActionTask {
        var cancelled = false
            private set

        override fun cancel() {
            cancelled = true
        }

        fun fire() {
            if (!cancelled) action()
        }
    }
}
