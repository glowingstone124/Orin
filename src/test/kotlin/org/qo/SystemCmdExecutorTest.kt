package org.qo

import org.qo.systemUtils.SystemCmdExecutor
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemCmdExecutorTest {
	@Test
	fun `captures stdout and stderr concurrently with bounded output`() {
		val executor = SystemCmdExecutor(
			timeout = Duration.ofSeconds(5),
			maxOutputBytes = 1_024,
		)

		val result = executor.executeByBash(
			"for i in {1..20000}; do printf 'stdout-line-%s\\n' \"\$i\"; printf 'stderr-line-%s\\n' \"\$i\" >&2; done"
		).getOrThrow()

		assertEquals(0, result.code)
		assertFalse(result.timedOut)
		assertTrue(result.stdoutTruncated)
		assertTrue(result.stderrTruncated)
		assertTrue(result.stdout.toByteArray().size <= 1_024)
		assertTrue(result.stderr.toByteArray().size <= 1_024)
	}

	@Test
	fun `terminates command after configured timeout`() {
		val executor = SystemCmdExecutor(
			timeout = Duration.ofMillis(100),
			maxOutputBytes = 1_024,
		)

		val result = executor.executeByBash("sleep 5").getOrThrow()

		assertTrue(result.timedOut)
		assertEquals(-1, result.code)
	}
}
