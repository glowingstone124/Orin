package org.qo.systemUtils

import io.github.oshai.kotlinlogging.KotlinLogging

class SystemCmdExecutor {
	private val logger = KotlinLogging.logger("SystemCmdExecutor")

	data class ExecutionResult(
		val code: Int,
		val stdout: String,
		val stderr: String,
	)

	fun executeBinary(cmd: Array<String>): Result<ExecutionResult> = runCatching {
		val process = ProcessBuilder(*cmd).start()
		val stdout = process.inputStream.bufferedReader().readText()
		val stderr = process.errorStream.bufferedReader().readText()
		val code = process.waitFor()
		ExecutionResult(code, stdout, stderr)
	}

	fun executeByBash(cmd: String): Result<ExecutionResult> = runCatching {
		val process = ProcessBuilder("bash", "-c", cmd).start()
		val stdout = process.inputStream.bufferedReader().readText()
		val stderr = process.errorStream.bufferedReader().readText()
		val code = process.waitFor()
		ExecutionResult(code, stdout, stderr)
	}
}