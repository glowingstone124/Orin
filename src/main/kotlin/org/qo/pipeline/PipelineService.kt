package org.qo.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import org.qo.Config
import org.qo.GithubPushEvent
import org.qo.action.ActionLoader
import org.qo.action.KtsActionExecutor
import org.qo.action.LoadedAction
import org.qo.systemUtils.SystemCmdExecutor
import java.nio.file.Path
import java.nio.file.Paths

class PipelineService(
	private val pipelines: List<Config.Pipeline>,
	private val workspaceRoot: Path = Paths.get("").toAbsolutePath().normalize(),
	private val systemCmdExecutor: SystemCmdExecutor = SystemCmdExecutor(),
	private val ktsActionExecutor: KtsActionExecutor = KtsActionExecutor(),
) {
	private val logger = KotlinLogging.logger("PipelineService")
	private val actionLoader = ActionLoader(workspaceRoot.resolve("actions"))

	data class PipelineExecution(
		val pipelineName: String,
		val succeeded: Boolean,
		val actionCount: Int,
	)

	data class ManualTriggerRequest(
		val token: String? = null,
		val inputs: Map<String, String> = emptyMap(),
	)

	enum class ManualTriggerStatus {
		SUCCESS,
		NOT_FOUND,
		DISABLED,
		UNAUTHORIZED,
		FAILED,
	}

	data class ManualTriggerResult(
		val status: ManualTriggerStatus,
		val message: String,
		val execution: PipelineExecution? = null,
	)

	fun handleGithubPush(event: GithubPushEvent): List<PipelineExecution> {
		val matched = pipelines.filter { pipeline ->
			pipeline.enabled &&
				pipeline.triggers.contains(GITHUB_PUSH_TRIGGER) &&
				(pipeline.repository == null || pipeline.repository == event.repository.name)
		}
		if (matched.isEmpty()) {
			logger.info { "No pipeline matched github push repository=${event.repository.name}" }
			return emptyList()
		}
		return matched.map { pipeline ->
			executePipeline(
				PipelineContext(
					pipeline = pipeline,
					trigger = GITHUB_PUSH_TRIGGER,
					githubPushEvent = event,
				)
			)
		}
	}

	fun triggerPipeline(name: String, request: ManualTriggerRequest): ManualTriggerResult {
		val pipeline = pipelines.firstOrNull { it.name == name }
			?: return ManualTriggerResult(
				status = ManualTriggerStatus.NOT_FOUND,
				message = "Pipeline $name not found.",
			)
		if (!pipeline.enabled) {
			return ManualTriggerResult(
				status = ManualTriggerStatus.DISABLED,
				message = "Pipeline $name is disabled.",
			)
		}
		if (!pipeline.triggers.contains(MANUAL_TRIGGER)) {
			return ManualTriggerResult(
				status = ManualTriggerStatus.FAILED,
				message = "Pipeline $name does not allow manual trigger.",
			)
		}
		if (pipeline.manualToken != null && pipeline.manualToken != request.token) {
			return ManualTriggerResult(
				status = ManualTriggerStatus.UNAUTHORIZED,
				message = "Manual trigger token is invalid.",
			)
		}

		val execution = executePipeline(
			PipelineContext(
				pipeline = pipeline,
				trigger = MANUAL_TRIGGER,
				inputs = request.inputs,
			)
		)
		return if (execution.succeeded) {
			ManualTriggerResult(
				status = ManualTriggerStatus.SUCCESS,
				message = "Pipeline $name completed.",
				execution = execution,
			)
		} else {
			ManualTriggerResult(
				status = ManualTriggerStatus.FAILED,
				message = "Pipeline $name failed.",
				execution = execution,
			)
		}
	}

	private fun executePipeline(context: PipelineContext): PipelineExecution {
		logger.info { "Executing pipeline=${context.pipeline.name} trigger=${context.trigger}" }
		var succeeded = true
		context.pipeline.actions.forEachIndexed { index, actionName ->
			val actionLabel = actionName.ifBlank { "step-$index" }
			val action = runCatching {
				actionLoader.load(actionName)
			}.onFailure { error ->
				logger.error(error) { "Failed to load action=$actionLabel pipeline=${context.pipeline.name}" }
				succeeded = false
			}.getOrNull() ?: return@forEachIndexed

			val actionContext = ActionExecutionContext(
				pipelineContext = context,
				actionName = action.name,
				workspaceRoot = workspaceRoot,
				actionDirectory = action.directory,
				entryPath = action.entryPath,
			)
			runCatching {
				when (action.entryType) {
					LoadedAction.EntryType.KTS -> executeKtsAction(actionContext, action)
					LoadedAction.EntryType.SHELL -> executeShellAction(actionContext, action)
				}
			}.onFailure { error ->
				logger.error(error) { "Action failed action=$actionLabel pipeline=${context.pipeline.name}" }
				succeeded = false
			}
			if (!succeeded) {
				return@forEachIndexed
			}
		}
		return PipelineExecution(
			pipelineName = context.pipeline.name,
			succeeded = succeeded,
			actionCount = context.pipeline.actions.size,
		)
	}

	companion object {
		const val GITHUB_PUSH_TRIGGER = "github.push"
		const val MANUAL_TRIGGER = "manual"
	}

	private fun executeKtsAction(context: ActionExecutionContext, action: LoadedAction) {
		ktsActionExecutor.execute(context, action)
	}

	private fun executeShellAction(context: ActionExecutionContext, action: LoadedAction) {
		val result = systemCmdExecutor.executeBinary(
			cmd = arrayOf("bash", action.entryPath.fileName.toString()),
			environment = context.environmentVariables(),
			workingDirectory = action.directory,
		)
		result.onFailure { error ->
			throw IllegalStateException("Shell action ${action.name} failed to start.", error)
		}.onSuccess { execution ->
			if (execution.stdout.isNotBlank()) {
				logger.info { "Pipeline=${context.pipelineContext.pipeline.name} action=${action.name} stdout=${execution.stdout.trim()}" }
			}
			if (execution.stderr.isNotBlank()) {
				logger.warn { "Pipeline=${context.pipelineContext.pipeline.name} action=${action.name} stderr=${execution.stderr.trim()}" }
			}
			if (execution.code != 0) {
				throw IllegalStateException("Shell action ${action.name} exited with code=${execution.code}.")
			}
		}
	}
}
