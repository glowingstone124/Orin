package org.qo.action

import io.github.oshai.kotlinlogging.KLogger
import org.qo.Config
import org.qo.cluster.JobRegistry
import org.qo.cluster.RemoteNode
import org.qo.pipeline.ActionExecutionContext
import org.qo.systemUtils.ProgressLogger
import org.qo.systemUtils.SystemCmdExecutor
import java.nio.file.Files
import java.nio.file.Path

class KtsActionApi(
	private val logger: KLogger,
	private val executionContext: ActionExecutionContext,
	private val sys: SystemCmdExecutor = SystemCmdExecutor(),
	private val clusterConfig: Config.Cluster = Config.Cluster(),
	private val remotes: List<Config.Remote> = emptyList(),
	private val jobRegistry: JobRegistry? = null,
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
		val timedOut: Boolean,
		val stdoutTruncated: Boolean,
		val stderrTruncated: Boolean,
		val context: CommandContext,
	) {
		val stdoutTrimmed: String
			get() = stdout.trim()

		val stderrTrimmed: String
			get() = stderr.trim()

			fun requireSuccess(): CommandResult {
				if (!success) {
					throw IllegalStateException(
						"Command failed with exitCode=$exitCode timedOut=$timedOut command=$command stderr=${stderrTrimmed.ifBlank { "<empty>" }}"
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
		logger.info { "Starting bash command for action=${executionContext.actionName}" }
		val env = executionContext.environmentVariables(context)
		val result = sys.executeByBash(
			cmd = command,
			environment = env,
			workingDirectory = executionContext.actionDirectory,
			onOutput = { channel, output ->
				if (output.isBlank()) {
					return@executeByBash
				}
				val message = "Action=${executionContext.actionName} bash ${channel.name.lowercase()}: ${output.trimEnd()}"
				if (channel == SystemCmdExecutor.OutputChannel.STDERR) {
					logger.warn { message }
				} else {
					logger.info { message }
				}
			},
		).getOrElse { error ->
			throw IllegalStateException("Failed to start bash command: $command", error)
		}
		return CommandResult(
			success = result.code == 0 && !result.timedOut,
			exitCode = result.code,
			command = command,
			stdout = result.stdout,
			stderr = result.stderr,
			timedOut = result.timedOut,
			stdoutTruncated = result.stdoutTruncated,
			stderrTruncated = result.stderrTruncated,
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
			success = result.code == 0 && !result.timedOut,
			exitCode = result.code,
			command = resolvedScript.toString(),
			stdout = result.stdout,
			stderr = result.stderr,
			timedOut = result.timedOut,
			stdoutTruncated = result.stdoutTruncated,
			stderrTruncated = result.stderrTruncated,
			context = commandContext(context),
		)
	}

	fun remote(nodeName: String): RemoteNode {
		val remoteConfig = remotes.firstOrNull { it.name == nodeName }
			?: throw IllegalArgumentException(
				"Remote node '$nodeName' not found in configuration. Available remotes: ${remotes.map { it.name }}"
			)
		return RemoteNode(
			remote = remoteConfig,
			defaultToken = clusterConfig.nodeToken,
		)
	}

	fun remoteByUrl(name: String, url: String, token: String? = null): RemoteNode {
		return RemoteNode(
			remote = Config.Remote(name = name, url = url, token = token),
			defaultToken = clusterConfig.nodeToken,
		)
	}

	fun publishArtifact(artifactName: String, filePath: String): Path {
		require(artifactName.isNotBlank()) { "Artifact name cannot be blank." }
		require(filePath.isNotBlank()) { "Artifact filePath cannot be blank." }
		val registry = jobRegistry
			?: throw IllegalStateException("JobRegistry is not configured; cannot publish artifact '$artifactName'.")
		val jobId = executionContext.pipelineContext.jobId
			?: throw IllegalStateException("Current execution is not associated with a job ID; cannot publish artifact '$artifactName'.")

		val candidateAction = executionContext.actionDirectory.resolve(filePath).normalize()
		val candidateWorkspace = executionContext.workspaceRoot.resolve(filePath).normalize()
		val resolvedPath = when {
			candidateAction.toFile().exists() -> candidateAction
			candidateWorkspace.toFile().exists() -> candidateWorkspace
			else -> throw IllegalArgumentException(
				"Artifact file not found: $filePath (checked action dir: $candidateAction, workspace: $candidateWorkspace)"
			)
		}

		val artifactSize = Files.size(resolvedPath)
		val progress = ProgressLogger(
			label = "Publishing artifact '$artifactName'",
			log = { message -> logger.info { message } },
		)
		logger.info {
			"Publishing artifact '$artifactName' by registering existing file $resolvedPath (size=$artifactSize bytes)"
		}
		progress.report(0, artifactSize, force = true)
		registry.registerArtifact(jobId, artifactName, resolvedPath)
		progress.report(artifactSize, artifactSize, force = true)
		logger.info { "Published artifact '$artifactName' for job $jobId from $resolvedPath" }
		return resolvedPath
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
