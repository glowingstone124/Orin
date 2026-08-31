package org.qo.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import org.qo.Config
import org.qo.GithubPushEvent
import org.qo.action.ActionLoader
import org.qo.action.KtsActionExecutor
import org.qo.action.LoadedAction
import org.qo.secureEquals
import org.qo.systemUtils.SystemCmdExecutor
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class PipelineService(
	private val pipelines: List<Config.Pipeline>,
	private val workspaceRoot: Path = Paths.get("").toAbsolutePath().normalize(),
	private val systemCmdExecutor: SystemCmdExecutor = SystemCmdExecutor(),
	private val ktsActionExecutor: KtsActionExecutor = KtsActionExecutor(systemCmdExecutor),
	queueCapacity: Int = 100,
) {
	private val logger = KotlinLogging.logger("PipelineService")
	private val actionLoader = ActionLoader(workspaceRoot.resolve("actions"))
	private val submissionSequence = AtomicLong()
	private val runner: ThreadPoolExecutor

	init {
		require(queueCapacity > 0) { "Pipeline queue capacity must be positive." }
		runner = ThreadPoolExecutor(
			1,
			1,
			0L,
			TimeUnit.MILLISECONDS,
			ArrayBlockingQueue(queueCapacity),
			ThreadFactory { runnable ->
				Thread(runnable, "orin-pipeline-runner").apply { isDaemon = true }
			},
			ThreadPoolExecutor.AbortPolicy(),
		)
	}

	data class PipelineExecution(
		val pipelineName: String,
		val succeeded: Boolean,
		val actionCount: Int,
	)

	data class PipelineSubmission(
		val id: Long,
		val pipelineName: String,
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
		BUSY,
		FAILED,
	}

	data class ManualTriggerResult(
		val status: ManualTriggerStatus,
		val message: String,
		val execution: PipelineExecution? = null,
	)

	fun handleGithubPush(event: GithubPushEvent): List<PipelineExecution> {
		val contexts = githubPipelineContexts(event)
		if (contexts.isEmpty()) {
			logger.info { "No pipeline matched github push repository=${event.repository.name}" }
			return emptyList()
		}
		return contexts.map { context ->
			val queued = submit(context) ?: throw PipelineQueueFullException()
			queued.result.join()
		}
	}

	fun enqueueGithubPush(event: GithubPushEvent): List<PipelineSubmission> {
		val contexts = githubPipelineContexts(event)
		if (contexts.isEmpty()) {
			logger.info { "No pipeline matched github push repository=${event.repository.name}" }
			return emptyList()
		}
		return contexts.map { context ->
			val queued = submit(context) ?: throw PipelineQueueFullException()
			logger.info {
				"Queued pipeline=${context.pipeline.name} submission=${queued.submission.id} trigger=${context.trigger}"
			}
			queued.submission
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
		val expectedToken = pipeline.manualToken?.takeIf(String::isNotBlank)
		if (expectedToken == null) {
			logger.error { "Manual trigger authentication is not configured for pipeline=$name" }
			return ManualTriggerResult(
				status = ManualTriggerStatus.UNAUTHORIZED,
				message = "Manual trigger authentication is not configured.",
			)
		}
		if (!secureEquals(expectedToken, request.token)) {
			return ManualTriggerResult(
				status = ManualTriggerStatus.UNAUTHORIZED,
				message = "Manual trigger token is invalid.",
			)
		}

		val context = PipelineContext(
				pipeline = pipeline,
				trigger = MANUAL_TRIGGER,
				inputs = request.inputs,
		)
		val queued = submit(context) ?: return ManualTriggerResult(
			status = ManualTriggerStatus.BUSY,
			message = "Pipeline queue is full. Try again later.",
		)
		val execution = queued.result.join()
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

	private fun githubPipelineContexts(event: GithubPushEvent): List<PipelineContext> {
		return pipelines
			.filter { pipeline ->
				pipeline.enabled &&
					pipeline.triggers.contains(GITHUB_PUSH_TRIGGER) &&
					(pipeline.repository == null || pipeline.repository == event.repository.name)
			}
			.map { pipeline ->
				PipelineContext(
					pipeline = pipeline,
					trigger = GITHUB_PUSH_TRIGGER,
					githubPushEvent = event,
				)
			}
	}

	private data class QueuedExecution(
		val submission: PipelineSubmission,
		val result: CompletableFuture<PipelineExecution>,
	)

	private fun submit(context: PipelineContext): QueuedExecution? {
		val submission = PipelineSubmission(
			id = submissionSequence.incrementAndGet(),
			pipelineName = context.pipeline.name,
		)
		val result = CompletableFuture<PipelineExecution>()
		return try {
			runner.execute {
				runCatching { executePipeline(context) }
					.onSuccess(result::complete)
					.onFailure(result::completeExceptionally)
			}
			QueuedExecution(submission, result)
		} catch (_: RejectedExecutionException) {
			logger.warn { "Pipeline queue is full; rejected pipeline=${context.pipeline.name}" }
			null
		}
	}

	private fun executePipeline(context: PipelineContext): PipelineExecution {
		logger.info { "Executing pipeline=${context.pipeline.name} trigger=${context.trigger}" }
		var succeeded = true
		var actionCount = 0
		for ((index, actionName) in context.pipeline.actions.withIndex()) {
			val actionLabel = actionName.ifBlank { "step-$index" }
			actionCount++
			val action = try {
				actionLoader.load(actionName)
			} catch (error: Exception) {
				logger.error(error) { "Failed to load action=$actionLabel pipeline=${context.pipeline.name}" }
				succeeded = false
				break
			}

			val actionContext = ActionExecutionContext(
				pipelineContext = context,
				actionName = action.name,
				workspaceRoot = workspaceRoot,
				actionDirectory = action.directory,
				entryPath = action.entryPath,
			)
			try {
				when (action.entryType) {
					LoadedAction.EntryType.KTS -> executeKtsAction(actionContext, action)
					LoadedAction.EntryType.SHELL -> executeShellAction(actionContext, action)
				}
			} catch (error: Exception) {
				logger.error(error) { "Action failed action=$actionLabel pipeline=${context.pipeline.name}" }
				succeeded = false
				break
			}
		}
		return PipelineExecution(
			pipelineName = context.pipeline.name,
			succeeded = succeeded,
			actionCount = actionCount,
		)
	}

	class PipelineQueueFullException : IllegalStateException("Pipeline queue is full.")

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
				if (execution.timedOut) {
					throw IllegalStateException("Shell action ${action.name} timed out.")
				}
				if (execution.code != 0) {
				throw IllegalStateException("Shell action ${action.name} exited with code=${execution.code}.")
			}
		}
	}
}
