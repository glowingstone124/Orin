package org.qo.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import org.qo.Config
import org.qo.GithubPushEvent
import org.qo.action.ActionLoader
import org.qo.action.KtsActionExecutor
import org.qo.action.LoadedAction
import org.qo.secureEquals
import org.qo.systemUtils.SystemCmdExecutor
import org.qo.cluster.JobRegistry
import org.qo.cluster.JobStatus
import java.nio.file.Files
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
	private val clusterConfig: Config.Cluster = Config.Cluster(),
	private val remotes: List<Config.Remote> = emptyList(),
	val jobRegistry: JobRegistry = JobRegistry(),
	private val ktsActionExecutor: KtsActionExecutor = KtsActionExecutor(
		systemCmdExecutor = systemCmdExecutor,
		clusterConfig = clusterConfig,
		remotes = remotes,
		jobRegistry = jobRegistry,
	),
	val queueCapacity: Int = 100,
) {
	private val logger = KotlinLogging.logger("PipelineService")
	private val actionLoader = ActionLoader(workspaceRoot.resolve("actions"))
	private val submissionSequence = AtomicLong()
	private val runner: ThreadPoolExecutor
	private val pipelineList = java.util.concurrent.CopyOnWriteArrayList<Config.Pipeline>(pipelines)

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

	data class QueueStatus(
		val capacity: Int,
		val queuedCount: Int,
		val activeCount: Int,
		val completedCount: Long,
	)

	fun getQueueStatus(): QueueStatus = QueueStatus(
		capacity = queueCapacity,
		queuedCount = runner.queue.size,
		activeCount = runner.activeCount,
		completedCount = runner.completedTaskCount,
	)

	fun getPipelines(): List<Config.Pipeline> = pipelineList.toList()
	fun getPipeline(name: String): Config.Pipeline? = pipelineList.firstOrNull { it.name == name }
	fun getRemotes(): List<Config.Remote> = remotes
	fun getClusterConfig(): Config.Cluster = clusterConfig

	fun addPipeline(pipeline: Config.Pipeline): Boolean {
		if (pipelineList.any { it.name == pipeline.name }) return false
		pipelineList.add(pipeline)
		persistConfig()
		return true
	}

	fun updatePipeline(name: String, pipeline: Config.Pipeline): Boolean {
		val idx = pipelineList.indexOfFirst { it.name == name }
		if (idx < 0) return false
		pipelineList[idx] = pipeline
		persistConfig()
		return true
	}

	fun deletePipeline(name: String): Boolean {
		val removed = pipelineList.removeIf { it.name == name }
		if (removed) persistConfig()
		return removed
	}

	data class PipelineDurationMetrics(
		val pipelineName: String,
		val runsCount: Int,
		val successfulCount: Int,
		val failedCount: Int,
		val avgDurationMs: Double,
		val minDurationMs: Long?,
		val maxDurationMs: Long?,
		val lastDurationMs: Long?,
		val successRate: Double,
	)

	data class OverallDurationMetrics(
		val totalRuns: Int,
		val avgDurationMs: Double,
		val minDurationMs: Long?,
		val maxDurationMs: Long?,
		val successRate: Double,
		val byPipeline: Map<String, PipelineDurationMetrics>,
	)

	fun evaluateDurationMetrics(): OverallDurationMetrics {
		val completedJobs = jobRegistry.allJobs().filter { it.completedAt != null }
		val totalRuns = completedJobs.size
		val durations = completedJobs.mapNotNull { it.completedAt?.minus(it.createdAt) }
		val overallAvg = if (durations.isNotEmpty()) durations.average() else 0.0
		val overallMin = durations.minOrNull()
		val overallMax = durations.maxOrNull()
		val successCount = completedJobs.count { it.status == JobStatus.SUCCESS }
		val overallSuccessRate = if (totalRuns > 0) successCount.toDouble() / totalRuns else 0.0

		val byPipeline = pipelineList.associate { p ->
			val pJobs = completedJobs.filter { it.pipelineName == p.name }
			val pDurations = pJobs.mapNotNull { it.completedAt?.minus(it.createdAt) }
			val pRuns = pJobs.size
			val pSuccess = pJobs.count { it.status == JobStatus.SUCCESS }
			val pFailed = pJobs.count { it.status == JobStatus.FAILED }
			val pAvg = if (pDurations.isNotEmpty()) pDurations.average() else 0.0
			val pMin = pDurations.minOrNull()
			val pMax = pDurations.maxOrNull()
			val pLast = pJobs.maxByOrNull { it.createdAt }?.let { it.completedAt?.minus(it.createdAt) }
			val pRate = if (pRuns > 0) pSuccess.toDouble() / pRuns else 0.0

			p.name to PipelineDurationMetrics(
				pipelineName = p.name,
				runsCount = pRuns,
				successfulCount = pSuccess,
				failedCount = pFailed,
				avgDurationMs = pAvg,
				minDurationMs = pMin,
				maxDurationMs = pMax,
				lastDurationMs = pLast,
				successRate = pRate,
			)
		}

		return OverallDurationMetrics(
			totalRuns = totalRuns,
			avgDurationMs = overallAvg,
			minDurationMs = overallMin,
			maxDurationMs = overallMax,
			successRate = overallSuccessRate,
			byPipeline = byPipeline,
		)
	}

	data class ActionDetail(
		val name: String,
		val entry: String,
		val entryType: String,
		val content: String,
		val manifestContent: String,
		val lastModified: Long = 0L,
		val size: Long = 0L,
	)

	data class ActionSummary(
		val name: String,
		val entry: String,
		val entryType: String,
		val lastModified: Long = 0L,
		val size: Long = 0L,
	)

	fun getAvailableActions(): List<String> {
		val actionsDir = workspaceRoot.resolve("actions").toFile()
		if (!actionsDir.exists() || !actionsDir.isDirectory) return emptyList()
		return actionsDir.listFiles()
			?.filter { it.isDirectory }
			?.map { it.name }
			?.sorted() ?: emptyList()
	}

	fun getActionSummaries(): List<ActionSummary> {
		val actionsDir = workspaceRoot.resolve("actions").toFile()
		if (!actionsDir.exists() || !actionsDir.isDirectory) return emptyList()
		val dirs = actionsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
		return dirs.mapNotNull { dir ->
			val name = dir.name
			val manifestFile = java.io.File(dir, "entry.toml")
			var entry = "main.kts"
			if (manifestFile.isFile) {
				val content = manifestFile.readText()
				val match = Regex("""entry\s*=\s*"([^"]+)"""").find(content)
				if (match != null) {
					entry = match.groupValues[1]
				}
			}
			val entryFile = java.io.File(dir, entry)
			val fileToStat = if (entryFile.isFile) entryFile else manifestFile
			val entryType = when {
				entry.endsWith(".kts") -> "kts"
				entry.endsWith(".sh") -> "sh"
				else -> "unknown"
			}
			ActionSummary(
				name = name,
				entry = entry,
				entryType = entryType,
				lastModified = if (fileToStat.exists()) fileToStat.lastModified() else 0L,
				size = if (fileToStat.exists()) fileToStat.length() else 0L,
			)
		}.sortedBy { it.name }
	}

	fun getActionDetail(name: String): ActionDetail? {
		if (!name.matches(Regex("^[a-zA-Z0-9_-]+$"))) return null
		val actionsDir = workspaceRoot.resolve("actions").toFile()
		val actionDir = java.io.File(actionsDir, name).canonicalFile
		if (!actionDir.path.startsWith(actionsDir.canonicalPath) || !actionDir.isDirectory) {
			return null
		}
		val manifestFile = java.io.File(actionDir, "entry.toml")
		val manifestContent = if (manifestFile.isFile) manifestFile.readText() else ""
		var entry = "main.kts"
		val match = Regex("""entry\s*=\s*"([^"]+)"""").find(manifestContent)
		if (match != null) {
			entry = match.groupValues[1]
		} else if (java.io.File(actionDir, "main.sh").isFile) {
			entry = "main.sh"
		}
		val entryFile = java.io.File(actionDir, entry).canonicalFile
		val scriptContent = if (entryFile.isFile) entryFile.readText() else ""
		val entryType = when {
			entry.endsWith(".kts") -> "kts"
			entry.endsWith(".sh") -> "sh"
			else -> "unknown"
		}
		val fileToStat = if (entryFile.isFile) entryFile else manifestFile
		return ActionDetail(
			name = name,
			entry = entry,
			entryType = entryType,
			content = scriptContent,
			manifestContent = manifestContent,
			lastModified = if (fileToStat.exists()) fileToStat.lastModified() else 0L,
			size = if (fileToStat.exists()) fileToStat.length() else 0L,
		)
	}

	fun saveAction(name: String, entry: String, scriptContent: String, manifestContent: String? = null): Boolean {
		if (!name.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
			throw IllegalArgumentException("Invalid action name. Use only alphanumeric characters, underscores, and dashes.")
		}
		val safeEntry = if (entry.isBlank()) {
			if (name.endsWith("-sh") || name.endsWith("_sh") || name.contains("shell")) "main.sh" else "main.kts"
		} else {
			entry.trim()
		}
		if (!safeEntry.matches(Regex("^[a-zA-Z0-9_.-]+$")) || (!safeEntry.endsWith(".kts") && !safeEntry.endsWith(".sh"))) {
			throw IllegalArgumentException("Invalid entry file. Must end with .kts or .sh")
		}

		val actionsDir = workspaceRoot.resolve("actions").toFile()
		actionsDir.mkdirs()
		val actionDir = java.io.File(actionsDir, name).canonicalFile
		if (!actionDir.path.startsWith(actionsDir.canonicalPath)) {
			throw IllegalArgumentException("Action path resolves outside actions root.")
		}
		actionDir.mkdirs()

		val manifestFile = java.io.File(actionDir, "entry.toml")
		val effectiveManifest = if (!manifestContent.isNullOrBlank()) {
			manifestContent
		} else if (safeEntry.endsWith(".kts")) {
			"""
			entry = "$safeEntry"

			[kts]
			expose = ["pipeline", "action", "trigger", "inputs", "github", "paths"]
			""".trimIndent() + "\n"
		} else {
			"""
			entry = "$safeEntry"
			""".trimIndent() + "\n"
		}
		manifestFile.writeText(effectiveManifest)

		val scriptFile = java.io.File(actionDir, safeEntry).canonicalFile
		if (!scriptFile.path.startsWith(actionDir.path)) {
			throw IllegalArgumentException("Entry script path resolves outside action directory.")
		}
		scriptFile.writeText(scriptContent)
		if (safeEntry.endsWith(".sh")) {
			try {
				scriptFile.setExecutable(true)
			} catch (_: Exception) {}
		}
		return true
	}

	fun deleteAction(name: String): Boolean {
		if (!name.matches(Regex("^[a-zA-Z0-9_-]+$"))) return false
		val actionsDir = workspaceRoot.resolve("actions").toFile()
		val actionDir = java.io.File(actionsDir, name).canonicalFile
		if (!actionDir.path.startsWith(actionsDir.canonicalPath) || !actionDir.isDirectory) {
			return false
		}
		return actionDir.deleteRecursively()
	}

	private fun persistConfig() {
		val configPath = workspaceRoot.resolve("config.toml")
		if (!Files.exists(configPath)) return
		try {
			val existing = Files.readString(configPath)
			val sb = StringBuilder()
			val parts = existing.split(Regex("(?m)^\\s*\\[\\[Pipeline\\]\\]"))
			sb.append(parts[0].trimEnd())
			sb.append("\n\n")
			pipelineList.forEach { p ->
				sb.append("[[Pipeline]]\n")
				sb.append("name = \"${p.name}\"\n")
				sb.append("enabled = ${p.enabled}\n")
				if (p.repository != null) {
					sb.append("repository = \"${p.repository}\"\n")
				}
				sb.append("triggers = [${p.triggers.joinToString(", ") { "\"$it\"" }}]\n")
				if (p.manualToken != null) {
					sb.append("manualToken = \"${p.manualToken}\"\n")
				}
				sb.append("actions = [${p.actions.joinToString(", ") { "\"$it\"" }}]\n\n")
			}
			Files.writeString(configPath, sb.toString())
			logger.info { "Persisted updated pipelines to config.toml" }
		} catch (e: Exception) {
			logger.error(e) { "Failed to persist updated pipelines to config.toml" }
		}
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
		val jobId: String? = null,
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
		val pipeline = pipelineList.firstOrNull { it.name == name }
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

		val job = jobRegistry.createJob(name, MANUAL_TRIGGER, request.inputs)
		val context = PipelineContext(
			pipeline = pipeline,
			trigger = MANUAL_TRIGGER,
			inputs = request.inputs,
			jobId = job.id,
		)
		val queued = submit(context) ?: run {
			jobRegistry.updateStatus(job.id, JobStatus.FAILED, "Pipeline queue is full.")
			return ManualTriggerResult(
				status = ManualTriggerStatus.BUSY,
				message = "Pipeline queue is full. Try again later.",
				jobId = job.id,
			)
		}
		val execution = queued.result.join()
		return if (execution.succeeded) {
			ManualTriggerResult(
				status = ManualTriggerStatus.SUCCESS,
				message = "Pipeline $name completed.",
				execution = execution,
				jobId = job.id,
			)
		} else {
			ManualTriggerResult(
				status = ManualTriggerStatus.FAILED,
				message = "Pipeline $name failed.",
				execution = execution,
				jobId = job.id,
			)
		}
	}

	enum class ClusterTriggerStatus {
		ACCEPTED,
		NOT_FOUND,
		DISABLED,
		UNAUTHORIZED,
		BUSY,
	}

	data class ClusterTriggerResult(
		val status: ClusterTriggerStatus,
		val message: String,
		val jobId: String? = null,
	)

	fun triggerClusterPipeline(
		name: String,
		inputs: Map<String, String> = emptyMap(),
		token: String? = null,
	): ClusterTriggerResult {
		val pipeline = pipelineList.firstOrNull { it.name == name }
			?: return ClusterTriggerResult(
				status = ClusterTriggerStatus.NOT_FOUND,
				message = "Pipeline $name not found.",
			)
		if (!pipeline.enabled) {
			return ClusterTriggerResult(
				status = ClusterTriggerStatus.DISABLED,
				message = "Pipeline $name is disabled.",
			)
		}
		if (!pipeline.triggers.contains(CLUSTER_TRIGGER) && !pipeline.triggers.contains(MANUAL_TRIGGER)) {
			return ClusterTriggerResult(
				status = ClusterTriggerStatus.DISABLED,
				message = "Pipeline $name does not allow cluster or manual trigger.",
			)
		}

		val expectedClusterToken = clusterConfig.nodeToken?.takeIf(String::isNotBlank)
		val expectedManualToken = pipeline.manualToken?.takeIf(String::isNotBlank)

		val authorized = when {
			expectedClusterToken != null && secureEquals(expectedClusterToken, token) -> true
			expectedManualToken != null && secureEquals(expectedManualToken, token) -> true
			expectedClusterToken == null && expectedManualToken == null -> true
			else -> false
		}

		if (!authorized) {
			return ClusterTriggerResult(
				status = ClusterTriggerStatus.UNAUTHORIZED,
				message = "Cluster trigger authentication failed for pipeline $name.",
			)
		}

		val job = jobRegistry.createJob(name, CLUSTER_TRIGGER, inputs)
		val context = PipelineContext(
			pipeline = pipeline,
			trigger = CLUSTER_TRIGGER,
			inputs = inputs,
			jobId = job.id,
		)

		val queued = submit(context)
		if (queued == null) {
			jobRegistry.updateStatus(job.id, JobStatus.FAILED, "Pipeline queue is full.")
			return ClusterTriggerResult(
				status = ClusterTriggerStatus.BUSY,
				message = "Pipeline queue is full. Try again later.",
			)
		}

		return ClusterTriggerResult(
			status = ClusterTriggerStatus.ACCEPTED,
			message = "Pipeline $name accepted and queued with job ID ${job.id}.",
			jobId = job.id,
		)
	}

	private fun githubPipelineContexts(event: GithubPushEvent): List<PipelineContext> {
		return pipelineList
			.filter { pipeline ->
				pipeline.enabled &&
					pipeline.triggers.contains(GITHUB_PUSH_TRIGGER) &&
					(pipeline.repository == null || pipeline.repository == event.repository.name)
			}
			.map { pipeline ->
				val inputs = linkedMapOf<String, String>()
				event.ref?.let { inputs["ref"] = it }
				event.after?.let { inputs["commit"] = it }
				val job = jobRegistry.createJob(pipeline.name, GITHUB_PUSH_TRIGGER, inputs)
				PipelineContext(
					pipeline = pipeline,
					trigger = GITHUB_PUSH_TRIGGER,
					githubPushEvent = event,
					inputs = inputs,
					jobId = job.id,
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
		context.jobId?.let { jobId ->
			jobRegistry.updateStatus(jobId, JobStatus.RUNNING, "Executing pipeline ${context.pipeline.name}")
		}
		var succeeded = true
		var actionCount = 0
		var failureReason: String? = null
		for ((index, actionName) in context.pipeline.actions.withIndex()) {
			val actionLabel = actionName.ifBlank { "step-$index" }
			actionCount++
			context.jobId?.let { jobId ->
				jobRegistry.appendLog(jobId, "[Action] Starting action $actionLabel ($actionName)")
			}
			val action = try {
				actionLoader.load(actionName)
			} catch (error: Exception) {
				val err = "Failed to load action $actionLabel: ${error.message}"
				logger.error(error) { "Failed to load action=$actionLabel pipeline=${context.pipeline.name}" }
				context.jobId?.let { jobId ->
					jobRegistry.appendLog(jobId, "[Error] $err")
				}
				succeeded = false
				failureReason = err
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
				val err = "Action $actionLabel failed: ${error.message}"
				logger.error(error) { "Action failed action=$actionLabel pipeline=${context.pipeline.name}" }
				context.jobId?.let { jobId ->
					jobRegistry.appendLog(jobId, "[Error] $err")
				}
				succeeded = false
				failureReason = err
				break
			}
		}
		context.jobId?.let { jobId ->
			if (succeeded) {
				jobRegistry.updateStatus(jobId, JobStatus.SUCCESS, "Pipeline ${context.pipeline.name} completed successfully.")
			} else {
				jobRegistry.updateStatus(jobId, JobStatus.FAILED, failureReason ?: "Pipeline ${context.pipeline.name} failed.")
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
		const val CLUSTER_TRIGGER = "cluster"
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
				val err = "Shell action ${action.name} failed to start: ${error.message}"
				context.pipelineContext.jobId?.let { jobId ->
					jobRegistry.appendLog(jobId, "[Error] $err")
				}
				throw IllegalStateException(err, error)
			}.onSuccess { execution ->
				if (execution.stdout.isNotBlank()) {
					logger.info { "Pipeline=${context.pipelineContext.pipeline.name} action=${action.name} stdout=${execution.stdout.trim()}" }
					context.pipelineContext.jobId?.let { jobId ->
						jobRegistry.appendLog(jobId, execution.stdout.trim())
					}
				}
				if (execution.stderr.isNotBlank()) {
					logger.warn { "Pipeline=${context.pipelineContext.pipeline.name} action=${action.name} stderr=${execution.stderr.trim()}" }
					context.pipelineContext.jobId?.let { jobId ->
						jobRegistry.appendLog(jobId, "[STDERR] ${execution.stderr.trim()}")
					}
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
