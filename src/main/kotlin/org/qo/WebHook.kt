package org.qo

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.qo.cluster.JobStatus
import org.qo.pipeline.PipelineService

interface Events

@Serializable
data class GithubPushEvent(
	@SerialName("repository")
	val repository: Repository,
	@SerialName("commits")
	val commits: List<Commit>,
	@SerialName("sender")
	val sender: Sender,
	val ref: String? = null,
	val after: String? = null,
	val deleted: Boolean = false,
) : Events{
	@Serializable
	data class Repository(
		@SerialName("name")
		val name: String,
		@SerialName("full_name")
		val full_name: String? = null,
	)

	@Serializable
	data class Commit(
		@SerialName("message")
		val message: String,
		@SerialName("author")
		val author: Author
	) {
		@Serializable
		data class Author(
			@SerialName("name")
			val name: String
		)
	}

	@Serializable
	data class Sender(
		@SerialName("login")
		val login: String
	)
}

class WebHook(
		private val pipelineService: PipelineService = PipelineService(emptyList()),
		private val githubAuthenticator: GithubWebhookAuthenticator? = null,
		private val clusterConfig: Config.Cluster = Config.Cluster(),
) {
	private val gson = Gson()
	private val logger = KotlinLogging.logger("WebHook")

	fun createServer(cfg: Config.Webhook): io.ktor.server.engine.EmbeddedServer<*, *> {
		require(cfg.maxRequestBytes > 0) { "Webhook maxRequestBytes must be positive." }
		logger.info { "Configuring server on ${cfg.endpoint}:${cfg.port}" }
		return embeddedServer(Netty, port = cfg.port, host = cfg.endpoint) {
			install(CORS) {
				anyHost()
				allowHeader(HttpHeaders.ContentType)
				allowHeader(HttpHeaders.Authorization)
				allowHeader("X-Orin-Token")
				allowMethod(HttpMethod.Options)
				allowMethod(HttpMethod.Get)
				allowMethod(HttpMethod.Post)
				allowMethod(HttpMethod.Put)
				allowMethod(HttpMethod.Delete)
			}
			routing {
				val hasWebUi = Thread.currentThread().contextClassLoader.getResource("web/index.html") != null
				if (hasWebUi) {
					singlePageApplication {
						useResources = true
						filesPath = "web"
						defaultPage = "index.html"
					}
				} else {
					get("/") {
						call.respond(HttpStatusCode.OK, "Orin running")
					}
				}
				get("/api/status") {
					val queue = pipelineService.getQueueStatus()
					val statusMap = mapOf(
						"status" to "UP",
						"version" to "1.0-SNAPSHOT",
						"queue" to mapOf(
							"capacity" to queue.capacity,
							"queuedCount" to queue.queuedCount,
							"activeCount" to queue.activeCount,
							"completedCount" to queue.completedCount,
						),
						"pipelinesCount" to pipelineService.getPipelines().size,
						"remotesCount" to pipelineService.getRemotes().size,
					)
					call.respond(HttpStatusCode.OK, gson.toJson(statusMap))
				}
				get("/api/pipelines") {
					val metrics = pipelineService.evaluateDurationMetrics()
					val pipelinesList = pipelineService.getPipelines().map { p ->
						mapOf(
							"name" to p.name,
							"enabled" to p.enabled,
							"repository" to p.repository,
							"triggers" to p.triggers,
							"hasManualToken" to !p.manualToken.isNullOrBlank(),
							"actions" to p.actions,
							"metrics" to metrics.byPipeline[p.name],
						)
					}
					call.respond(HttpStatusCode.OK, gson.toJson(pipelinesList))
				}
				get("/api/pipelines/{name}") {
					val name = call.parameters["name"] ?: run {
						call.respond(HttpStatusCode.BadRequest, "Missing pipeline name.")
						return@get
					}
					val pipeline = pipelineService.getPipeline(name) ?: run {
						call.respond(HttpStatusCode.NotFound, "Pipeline $name not found.")
						return@get
					}
					val metrics = pipelineService.evaluateDurationMetrics()
					val responseMap = mapOf(
						"name" to pipeline.name,
						"enabled" to pipeline.enabled,
						"repository" to pipeline.repository,
						"triggers" to pipeline.triggers,
						"hasManualToken" to !pipeline.manualToken.isNullOrBlank(),
						"manualToken" to pipeline.manualToken,
						"actions" to pipeline.actions,
						"metrics" to metrics.byPipeline[name],
					)
					call.respond(HttpStatusCode.OK, gson.toJson(responseMap))
				}
				post("/api/pipelines") {
					val receivedBody = call.receiveLimitedText(cfg.maxRequestBytes) ?: ""
					val pipeline = runCatching { gson.fromJson(receivedBody, Config.Pipeline::class.java) }.getOrNull()
					if (pipeline == null || pipeline.name.isBlank()) {
						call.respond(HttpStatusCode.BadRequest, "Invalid pipeline payload.")
						return@post
					}
					val created = pipelineService.addPipeline(pipeline)
					if (!created) {
						call.respond(HttpStatusCode.Conflict, "Pipeline ${pipeline.name} already exists.")
						return@post
					}
					call.respond(HttpStatusCode.Created, gson.toJson(mapOf("message" to "Pipeline ${pipeline.name} created.")))
				}
				put("/api/pipelines/{name}") {
					val name = call.parameters["name"] ?: run {
						call.respond(HttpStatusCode.BadRequest, "Missing pipeline name.")
						return@put
					}
					val receivedBody = call.receiveLimitedText(cfg.maxRequestBytes) ?: ""
					val pipeline = runCatching { gson.fromJson(receivedBody, Config.Pipeline::class.java) }.getOrNull()
					if (pipeline == null || pipeline.name.isBlank()) {
						call.respond(HttpStatusCode.BadRequest, "Invalid pipeline payload.")
						return@put
					}
					val updated = pipelineService.updatePipeline(name, pipeline)
					if (!updated) {
						call.respond(HttpStatusCode.NotFound, "Pipeline $name not found.")
						return@put
					}
					call.respond(HttpStatusCode.OK, gson.toJson(mapOf("message" to "Pipeline ${pipeline.name} updated.")))
				}
				delete("/api/pipelines/{name}") {
					val name = call.parameters["name"] ?: run {
						call.respond(HttpStatusCode.BadRequest, "Missing pipeline name.")
						return@delete
					}
					val deleted = pipelineService.deletePipeline(name)
					if (!deleted) {
						call.respond(HttpStatusCode.NotFound, "Pipeline $name not found.")
						return@delete
					}
					call.respond(HttpStatusCode.OK, gson.toJson(mapOf("message" to "Pipeline $name deleted.")))
				}
				get("/api/metrics/evaluate") {
					val metrics = pipelineService.evaluateDurationMetrics()
					call.respond(HttpStatusCode.OK, gson.toJson(metrics))
				}
				get("/api/actions") {
					val actions = pipelineService.getAvailableActions()
					call.respond(HttpStatusCode.OK, gson.toJson(actions))
				}
				get("/api/jobs") {
					val jobsList = pipelineService.jobRegistry.allJobs()
						.sortedByDescending { it.createdAt }
						.map { job ->
							val duration = job.completedAt?.let { it - job.createdAt }
							mapOf(
								"id" to job.id,
								"pipelineName" to job.pipelineName,
								"trigger" to job.trigger,
								"inputs" to job.inputs,
								"status" to job.status.name,
								"message" to job.message,
								"artifacts" to job.artifacts.keys.toList(),
								"createdAt" to job.createdAt,
								"completedAt" to job.completedAt,
								"durationMs" to duration,
							)
						}
					call.respond(HttpStatusCode.OK, gson.toJson(jobsList))
				}
				get("/api/jobs/{jobId}") {
					val jobId = call.parameters["jobId"]
					if (jobId == null) {
						call.respond(HttpStatusCode.BadRequest, "Missing jobId.")
						return@get
					}
					val job = pipelineService.jobRegistry.getJob(jobId)
					if (job == null) {
						call.respond(HttpStatusCode.NotFound, "Job $jobId not found.")
						return@get
					}
					val duration = job.completedAt?.let { it - job.createdAt }
					val responseMap = mapOf(
						"id" to job.id,
						"pipelineName" to job.pipelineName,
						"trigger" to job.trigger,
						"inputs" to job.inputs,
						"status" to job.status.name,
						"message" to job.message,
						"artifacts" to job.artifacts.keys.toList(),
						"createdAt" to job.createdAt,
						"completedAt" to job.completedAt,
						"durationMs" to duration,
						"logs" to job.getLogs(),
					)
					call.respond(HttpStatusCode.OK, gson.toJson(responseMap))
				}
				get("/api/jobs/{jobId}/logs") {
					val jobId = call.parameters["jobId"]
					if (jobId == null) {
						call.respond(HttpStatusCode.BadRequest, "Missing jobId.")
						return@get
					}
					val job = pipelineService.jobRegistry.getJob(jobId)
					if (job == null) {
						call.respond(HttpStatusCode.NotFound, "Job $jobId not found.")
						return@get
					}
					val responseMap = mapOf(
						"jobId" to job.id,
						"logs" to job.getLogs(),
						"logLines" to job.getLogLines(),
					)
					call.respond(HttpStatusCode.OK, gson.toJson(responseMap))
				}
				post("/api/pipelines/{pipeline}/trigger") {
					val pipelineName = call.parameters["pipeline"]
					if (pipelineName == null) {
						call.respond(HttpStatusCode.BadRequest, "Missing pipeline name.")
						return@post
					}
					val receivedBody = call.receiveLimitedText(cfg.maxRequestBytes) ?: ""
					val request = parseManualTriggerRequest(receivedBody) ?: PipelineService.ManualTriggerRequest()
					val token = request.token ?: extractToken(call)
					val finalRequest = request.copy(token = token)
					val result = pipelineService.triggerPipeline(pipelineName, finalRequest)
					val resp = mapOf(
						"jobId" to result.jobId,
						"status" to result.status.name,
						"message" to result.message,
					)
					when (result.status) {
						PipelineService.ManualTriggerStatus.SUCCESS ->
							call.respond(HttpStatusCode.OK, gson.toJson(resp))
						PipelineService.ManualTriggerStatus.NOT_FOUND ->
							call.respond(HttpStatusCode.NotFound, gson.toJson(resp))
						PipelineService.ManualTriggerStatus.DISABLED ->
							call.respond(HttpStatusCode.Conflict, gson.toJson(resp))
						PipelineService.ManualTriggerStatus.UNAUTHORIZED ->
							call.respond(HttpStatusCode.Unauthorized, gson.toJson(resp))
						PipelineService.ManualTriggerStatus.BUSY ->
							call.respond(HttpStatusCode.TooManyRequests, gson.toJson(resp))
						PipelineService.ManualTriggerStatus.FAILED ->
							call.respond(HttpStatusCode.InternalServerError, gson.toJson(resp))
					}
				}
				get("/api/cluster/nodes") {
					val remotes = pipelineService.getRemotes().map { r ->
						mapOf(
							"name" to r.name,
							"url" to r.url,
							"hasToken" to !r.token.isNullOrBlank(),
						)
					}
					call.respond(HttpStatusCode.OK, gson.toJson(remotes))
				}
					post("/webhook") {
						handleGithubWebhook(call, cfg)
					}
					post("/webhook/github") {
						handleGithubWebhook(call, cfg)
				}
				post("/trigger/{pipeline}") {
					val pipelineName = call.parameters["pipeline"]
					if (pipelineName == null) {
						call.respond(HttpStatusCode.BadRequest, "Missing pipeline name.")
						return@post
					}
					val receivedBody = call.receiveLimitedText(cfg.maxRequestBytes)
					if (receivedBody == null) {
						call.respond(HttpStatusCode.PayloadTooLarge, "Trigger payload is too large.")
						return@post
					}
					val rawBody = receivedBody.trim()
					val request = parseManualTriggerRequest(rawBody) ?: run {
						call.respond(HttpStatusCode.BadRequest, "Invalid trigger payload.")
						return@post
					}
					val result = pipelineService.triggerPipeline(pipelineName, request)
					when (result.status) {
						PipelineService.ManualTriggerStatus.SUCCESS ->
							call.respond(HttpStatusCode.OK, result.message)
						PipelineService.ManualTriggerStatus.NOT_FOUND ->
							call.respond(HttpStatusCode.NotFound, result.message)
						PipelineService.ManualTriggerStatus.DISABLED ->
							call.respond(HttpStatusCode.Conflict, result.message)
						PipelineService.ManualTriggerStatus.UNAUTHORIZED ->
							call.respond(HttpStatusCode.Unauthorized, result.message)
						PipelineService.ManualTriggerStatus.BUSY ->
							call.respond(HttpStatusCode.TooManyRequests, result.message)
						PipelineService.ManualTriggerStatus.FAILED ->
							call.respond(HttpStatusCode.InternalServerError, result.message)
					}
				}
				post("/remote/trigger/{pipeline}") {
					val pipelineName = call.parameters["pipeline"]
					if (pipelineName == null) {
						call.respond(HttpStatusCode.BadRequest, "Missing pipeline name.")
						return@post
					}
					val token = extractToken(call)
					val receivedBody = call.receiveLimitedText(cfg.maxRequestBytes) ?: ""
					val request = parseClusterTriggerRequest(receivedBody)
					val result = pipelineService.triggerClusterPipeline(
						name = pipelineName,
						inputs = request.inputs,
						token = token,
					)
					when (result.status) {
						PipelineService.ClusterTriggerStatus.ACCEPTED -> {
							val responseMap = mapOf(
								"jobId" to result.jobId,
								"status" to "QUEUED",
								"message" to result.message,
							)
							call.respond(HttpStatusCode.Accepted, gson.toJson(responseMap))
						}
						PipelineService.ClusterTriggerStatus.NOT_FOUND ->
							call.respond(HttpStatusCode.NotFound, result.message)
						PipelineService.ClusterTriggerStatus.DISABLED ->
							call.respond(HttpStatusCode.Conflict, result.message)
						PipelineService.ClusterTriggerStatus.UNAUTHORIZED ->
							call.respond(HttpStatusCode.Unauthorized, result.message)
						PipelineService.ClusterTriggerStatus.BUSY ->
							call.respond(HttpStatusCode.TooManyRequests, result.message)
					}
				}
				get("/remote/jobs/{jobId}/status") {
					val jobId = call.parameters["jobId"]
					if (jobId == null) {
						call.respond(HttpStatusCode.BadRequest, "Missing jobId.")
						return@get
					}
					val token = extractToken(call)
					if (!verifyClusterToken(token)) {
						call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
						return@get
					}
					val job = pipelineService.jobRegistry.getJob(jobId)
					if (job == null) {
						call.respond(HttpStatusCode.NotFound, "Job $jobId not found.")
						return@get
					}
					val responseMap = mapOf(
						"id" to job.id,
						"pipeline" to job.pipelineName,
						"status" to job.status.name,
						"message" to job.message,
						"artifacts" to job.artifacts.keys.toList(),
					)
					call.respond(HttpStatusCode.OK, gson.toJson(responseMap))
				}
				get("/remote/jobs/{jobId}/artifacts/{artifactName}") {
					val jobId = call.parameters["jobId"]
					val artifactName = call.parameters["artifactName"]
					if (jobId == null || artifactName == null) {
						call.respond(HttpStatusCode.BadRequest, "Missing jobId or artifactName.")
						return@get
					}
					val token = extractToken(call)
					if (!verifyClusterToken(token)) {
						call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
						return@get
					}
					val job = pipelineService.jobRegistry.getJob(jobId)
					if (job == null) {
						call.respond(HttpStatusCode.NotFound, "Job $jobId not found.")
						return@get
					}
					val artifactPath = job.artifacts[artifactName]
					if (artifactPath == null || !artifactPath.toFile().exists()) {
						call.respond(HttpStatusCode.NotFound, "Artifact '$artifactName' not found for job $jobId.")
						return@get
					}
					call.respondFile(artifactPath.toFile())
				}
			}
		}
	}

	fun runWebhookEndpoint(cfg: Config.Webhook) {
		requireNotNull(githubAuthenticator) {
			"Github webhook authentication must be configured before starting the endpoint."
		}
		createServer(cfg).start(wait = true)
	}

	private suspend fun handleGithubWebhook(call: ApplicationCall, cfg: Config.Webhook) {
		val eventType = call.request.header(GITHUB_EVENT_HEADER)
		if (eventType != GITHUB_PUSH_EVENT && eventType != "ping") {
			call.respond(HttpStatusCode.BadRequest, "Unsupported or missing Github event type.")
			return
		}
		val payload = call.receiveLimitedText(cfg.maxRequestBytes)
		if (payload == null) {
			call.respond(HttpStatusCode.PayloadTooLarge, "Webhook payload is too large.")
			return
		}
		if (!requireNotNull(githubAuthenticator).verify(
				payload = payload,
				signatureHeader = call.request.header(GITHUB_SIGNATURE_HEADER),
			)
		) {
			logger.warn { "Rejected Github webhook with an invalid signature." }
			call.respond(HttpStatusCode.Unauthorized, "Invalid Github webhook signature.")
			return
		}
		if (eventType == "ping") {
			call.respond(HttpStatusCode.OK, "Orin webhook ready")
			return
		}
		val accepted = try {
			parseGithubWebHook(payload)
		} catch (_: PipelineService.PipelineQueueFullException) {
			call.respond(HttpStatusCode.ServiceUnavailable, "Pipeline queue is full. Retry later.")
			return
		}
		if (accepted) {
			call.respond(HttpStatusCode.Accepted)
		} else {
			call.respond(HttpStatusCode.BadRequest, "Invalid Github push payload.")
		}
	}

	private suspend fun ApplicationCall.receiveLimitedText(maxRequestBytes: Long): String? {
		val declaredLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
		if (declaredLength != null && declaredLength > maxRequestBytes) {
			return null
		}
		val payload = receiveText()
		return payload.takeIf { it.toByteArray(Charsets.UTF_8).size <= maxRequestBytes }
	}

	fun parseGithubWebHook(payload: String): Boolean {
		val event = runCatching {
			val event = requireNotNull(gson.fromJson(payload, GithubPushEvent::class.java))
			require(event.repository.name.isNotBlank()) { "Repository name is missing." }
			require(event.sender.login.isNotBlank()) { "Sender login is missing." }
			event.commits.forEach { commit ->
				require(commit.message.isNotBlank()) { "Commit message is missing." }
				require(commit.author.name.isNotBlank()) { "Commit author is missing." }
			}
			event
		}.getOrElse { error ->
			logger.warn(error) { "Rejected invalid Github webhook payload." }
			return false
		}
		logger.info {
			"Received Github PUSH Webhook for repository: ${event.repository.name}"
		}
		logger.debug {
			"Dispatching GithubPushEvent to registered processors"
		}
		val processorCount = ActionsDiscover.dispatch(event)
		if (processorCount == 0) {
			logger.warn { "No processors registered for GithubPushEvent" }
		}
		return true
	}

	fun parseManualTriggerRequest(payload: String): PipelineService.ManualTriggerRequest? {
		if (payload.isBlank()) {
			return PipelineService.ManualTriggerRequest()
		}
		return runCatching {
			gson.fromJson(payload, PipelineService.ManualTriggerRequest::class.java)
		}.onFailure {
			logger.warn(it) { "Rejected invalid manual trigger payload." }
		}.getOrNull()
	}

	data class ClusterTriggerPayload(
		val inputs: Map<String, String> = emptyMap(),
	)

	fun parseClusterTriggerRequest(payload: String): ClusterTriggerPayload {
		if (payload.isBlank()) {
			return ClusterTriggerPayload()
		}
		return runCatching {
			gson.fromJson(payload, ClusterTriggerPayload::class.java)
		}.getOrElse {
			ClusterTriggerPayload()
		}
	}

	private fun extractToken(call: ApplicationCall): String? {
		val authHeader = call.request.header(HttpHeaders.Authorization)
		if (authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true)) {
			return authHeader.substring(7).trim()
		}
		val customHeader = call.request.header("X-Orin-Token")
		if (!customHeader.isNullOrBlank()) {
			return customHeader.trim()
		}
		val queryToken = call.request.queryParameters["token"]
		if (!queryToken.isNullOrBlank()) {
			return queryToken.trim()
		}
		return null
	}

	private fun verifyClusterToken(token: String?): Boolean {
		val expected = clusterConfig.nodeToken?.takeIf(String::isNotBlank) ?: return true
		return secureEquals(expected, token)
	}

	companion object {
		private const val GITHUB_EVENT_HEADER = "X-GitHub-Event"
		private const val GITHUB_SIGNATURE_HEADER = "X-Hub-Signature-256"
		private const val GITHUB_PUSH_EVENT = "push"
	}
}
