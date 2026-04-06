package org.qo

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.HttpStatusCode
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface Events

@Serializable
data class GithubPushEvent(
	@SerialName("repository")
	val repository: Repository,
	@SerialName("commits")
	val commits: List<Commit>,
	@SerialName("sender")
	val sender: Sender
) : Events{
	@Serializable
	data class Repository(
		@SerialName("name")
		val name: String
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
class WebHook {
	private val gson = Gson()
	private val logger = KotlinLogging.logger("WebHook")

	fun runWebhookEndpoint(cfg: Config.Webhook) {
		logger.info { "Starting webhook on ${cfg.endpoint}:${cfg.port}" }
		embeddedServer(Netty, port = cfg.port, host = cfg.endpoint) {
			routing {
				get("/") {
					call.respond(HttpStatusCode.OK, "Orin running")
				}
				post("/webhook") {
					if (parseGithubWebHook(call.receiveText())) {
						call.respond(HttpStatusCode.OK)
					} else {
						call.respond(HttpStatusCode.InternalServerError)
					}
				}
			}
		}.start(wait = true)
	}

	fun parseGithubWebHook(payload: String): Boolean {
		val event = runCatching {
			gson.fromJson(payload, GithubPushEvent::class.java)
		}.onFailure {
			logger.error(it) { "Error parsing Github Webhook from payload: $payload" }
			return false
		}.getOrNull() ?: return false
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
}
