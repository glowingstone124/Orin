package org.qo.systemUtils

import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Path

class SystemCmdExecutor {
	private val logger = KotlinLogging.logger("SystemCmdExecutor")

	data class ExecutionResult(
		val code: Int,
		val stdout: String,
		val stderr: String,
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
		val stdout = process.inputStream.bufferedReader().readText()
		val stderr = process.errorStream.bufferedReader().readText()
		val code = process.waitFor()
		ExecutionResult(code, stdout, stderr)
	}

	fun executeByBash(
		cmd: String,
		environment: Map<String, String> = emptyMap(),
		workingDirectory: Path? = null,
	): Result<ExecutionResult> = runCatching {
		val builder = ProcessBuilder("bash", "-c", cmd)
		builder.environment().putAll(environment)
		workingDirectory?.let { builder.directory(it.toFile()) }
		val process = builder.start()
		val stdout = process.inputStream.bufferedReader().readText()
		val stderr = process.errorStream.bufferedReader().readText()
		val code = process.waitFor()
		ExecutionResult(code, stdout, stderr)
	}
}
