package org.qo

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
) {
	private val gson = Gson()
	private val logger = KotlinLogging.logger("WebHook")

	fun runWebhookEndpoint(cfg: Config.Webhook) {
		requireNotNull(githubAuthenticator) {
			"Github webhook authentication must be configured before starting the endpoint."
		}
		require(cfg.maxRequestBytes > 0) { "Webhook maxRequestBytes must be positive." }
		logger.info { "Starting webhook on ${cfg.endpoint}:${cfg.port}" }
		embeddedServer(Netty, port = cfg.port, host = cfg.endpoint) {
			routing {
				get("/") {
					call.respond(HttpStatusCode.OK, "Orin running")
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
			}
		}.start(wait = true)
		}

	private suspend fun handleGithubWebhook(call: ApplicationCall, cfg: Config.Webhook) {
		if (call.request.header(GITHUB_EVENT_HEADER) != GITHUB_PUSH_EVENT) {
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

	companion object {
		private const val GITHUB_EVENT_HEADER = "X-GitHub-Event"
		private const val GITHUB_SIGNATURE_HEADER = "X-Hub-Signature-256"
		private const val GITHUB_PUSH_EVENT = "push"
	}
}
