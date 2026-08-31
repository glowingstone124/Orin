package org.qo.systemUtils

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.min

class SystemCmdExecutor(
	private val timeout: Duration = Duration.ofMinutes(15),
	private val maxOutputBytes: Int = 1_048_576,
) {
	private val logger = KotlinLogging.logger("SystemCmdExecutor")

	init {
		require(!timeout.isZero && !timeout.isNegative) { "Command timeout must be positive." }
		require(maxOutputBytes > 0) { "Command maxOutputBytes must be positive." }
	}

	data class ExecutionResult(
		val code: Int,
		val stdout: String,
		val stderr: String,
		val timedOut: Boolean = false,
		val stdoutTruncated: Boolean = false,
		val stderrTruncated: Boolean = false,
	)

	fun executeBinary(
		cmd: Array<String>,
		environment: Map<String, String> = emptyMap(),
		workingDirectory: Path? = null,
	): Result<ExecutionResult> = runCatching {
		val builder = ProcessBuilder(*cmd)
		builder.environment().putAll(environment)
		workingDirectory?.let { builder.directory(it.toFile()) }
		val process = builder.start()
		process.outputStream.close()

		val stdout = BoundedStreamCapture(maxOutputBytes)
		val stderr = BoundedStreamCapture(maxOutputBytes)
		val stdoutReader = thread(name = "orin-process-stdout", isDaemon = true) {
			stdout.readFrom(process.inputStream)
		}
		val stderrReader = thread(name = "orin-process-stderr", isDaemon = true) {
			stderr.readFrom(process.errorStream)
		}

		val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
		if (!completed) {
			logger.warn { "Command timed out after ${timeout.toSeconds()}s: ${cmd.joinToString(" ")}" }
			val descendants = process.descendants().toList()
			descendants.forEach { it.destroy() }
			process.destroy()
			process.waitFor(TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS)
			descendants.filter { it.isAlive }.forEach { it.destroyForcibly() }
			if (process.isAlive) {
				process.destroyForcibly()
				process.waitFor(TERMINATION_GRACE_SECONDS, TimeUnit.SECONDS)
			}
		}
		stdoutReader.join(READER_JOIN_MILLIS)
		stderrReader.join(READER_JOIN_MILLIS)
		if (stdoutReader.isAlive) {
			process.inputStream.close()
			stdoutReader.join(READER_JOIN_MILLIS)
		}
		if (stderrReader.isAlive) {
			process.errorStream.close()
			stderrReader.join(READER_JOIN_MILLIS)
		}
		stdout.failure?.takeIf { completed }?.let { throw it }
		stderr.failure?.takeIf { completed }?.let { throw it }

		ExecutionResult(
			code = if (completed) process.exitValue() else TIMEOUT_EXIT_CODE,
			stdout = stdout.content(),
			stderr = stderr.content(),
			timedOut = !completed,
			stdoutTruncated = stdout.truncated,
			stderrTruncated = stderr.truncated,
		)
	}

	fun executeByBash(
		cmd: String,
		environment: Map<String, String> = emptyMap(),
		workingDirectory: Path? = null,
	): Result<ExecutionResult> = executeBinary(
		cmd = arrayOf("bash", "-c", cmd),
		environment = environment,
		workingDirectory = workingDirectory,
	)

	private class BoundedStreamCapture(private val limit: Int) {
		private val output = ByteArrayOutputStream(min(limit, BUFFER_SIZE))
		@Volatile
		var truncated: Boolean = false
			private set
		@Volatile
		var failure: Exception? = null
			private set

		fun readFrom(stream: InputStream) {
			try {
				val buffer = ByteArray(BUFFER_SIZE)
				while (true) {
					val read = stream.read(buffer)
					if (read < 0) {
						break
					}
					val remaining = limit - output.size()
					if (remaining > 0) {
						output.write(buffer, 0, min(read, remaining))
					}
					if (read > remaining) {
						truncated = true
					}
				}
			} catch (error: Exception) {
				failure = error
			}
		}

		fun content(): String = output.toString(Charsets.UTF_8)
	}

	companion object {
		private const val BUFFER_SIZE = 8192
		private const val TERMINATION_GRACE_SECONDS = 2L
		private const val READER_JOIN_MILLIS = 5_000L
		private const val TIMEOUT_EXIT_CODE = -1
	}
}
