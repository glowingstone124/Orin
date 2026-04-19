package org.qo.action

import io.github.oshai.kotlinlogging.KLogger
import org.qo.pipeline.ActionExecutionContext
import org.qo.systemUtils.SystemCmdExecutor
import java.nio.file.Path

class KtsActionApi(
	private val logger: KLogger,
	private val executionContext: ActionExecutionContext,
	private val sys: SystemCmdExecutor = SystemCmdExecutor(),
) {
	data class CommandContext(
		val pipeline: String,
		val action: String,
		val trigger: String,
		val workspaceDir: String,
		val actionDir: String,
		val entryPath: String,
		val extra: Map<String, String>,
	)

	data class CommandResult(
		val success: Boolean,
		val exitCode: Int,
		val command: String,
		val stdout: String,
		val stderr: String,
		val context: CommandContext,
	) {
		val stdoutTrimmed: String
			get() = stdout.trim()

		val stderrTrimmed: String
			get() = stderr.trim()

		fun requireSuccess(): CommandResult {
			if (!success) {
				throw IllegalStateException(
					"Command failed with exitCode=$exitCode command=$command stderr=${stderrTrimmed.ifBlank { "<empty>" }}"
				)
			}
			return this
		}
	}

	fun log(message: String) {
		logger.info { message }
	}

	fun warn(message: String) {
		logger.warn { message }
	}

	fun fail(message: String): Nothing {
		throw IllegalStateException(message)
	}

	fun executeByBash(
		command: String,
		context: Map<String, String> = emptyMap(),
	): CommandResult {
		val env = executionContext.environmentVariables(context)
		val result = sys.executeByBash(
			cmd = command,
			environment = env,
			workingDirectory = executionContext.actionDirectory,
		).getOrElse { error ->
			throw IllegalStateException("Failed to start bash command: $command", error)
		}
		return CommandResult(
			success = result.code == 0,
			exitCode = result.code,
			command = command,
			stdout = result.stdout,
			stderr = result.stderr,
			context = commandContext(context),
		)
	}

	fun executeSh(
		scriptPath: String,
		context: Map<String, String> = emptyMap(),
	): CommandResult {
		val resolvedScript = resolveScriptPath(scriptPath)
		val env = executionContext.environmentVariables(context)
		val result = sys.executeBinary(
			cmd = arrayOf("bash", resolvedScript.fileName.toString()),
			environment = env,
			workingDirectory = executionContext.actionDirectory,
		).getOrElse { error ->
			throw IllegalStateException("Failed to start shell script: $scriptPath", error)
		}
		return CommandResult(
			success = result.code == 0,
			exitCode = result.code,
			command = resolvedScript.toString(),
			stdout = result.stdout,
			stderr = result.stderr,
			context = commandContext(context),
		)
	}

	private fun resolveScriptPath(scriptPath: String): Path {
		val resolved = executionContext.actionDirectory.resolve(scriptPath).normalize()
		require(resolved.startsWith(executionContext.actionDirectory)) {
			"Script path resolves outside action directory: $scriptPath"
		}
		require(resolved.toFile().isFile) {
			"Shell script not found: $resolved"
		}
		return resolved
	}

	private fun commandContext(extra: Map<String, String>): CommandContext {
		return CommandContext(
			pipeline = executionContext.pipelineContext.pipeline.name,
			action = executionContext.actionName,
			trigger = executionContext.pipelineContext.trigger,
			workspaceDir = executionContext.workspaceRoot.toString(),
			actionDir = executionContext.actionDirectory.toString(),
			entryPath = executionContext.entryPath.toString(),
			extra = extra,
		)
	}
}
