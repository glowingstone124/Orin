package org.qo

import com.akuleshov7.ktoml.Toml
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.decodeFromString
import org.qo.systemUtils.Util
import java.nio.file.Files
import java.nio.file.Paths

private val logger = KotlinLogging.logger("Main")

private lateinit var webHook: WebHook
fun main(args: Array<String>) {
	logger.info { "Project Orin starting..." }
	if (!Util.isPosixLikeOs()) {
		logger.warn { "OS is not supported. Some features may not work properly." }
	}
	if (!Util.hasBash()) {
		logger.warn { "Bash not found. Some features may not work properly." }
	}
	val cfg = Toml.decodeFromString<Config.Cfg>(Files.readString(Paths.get("config.toml")))
	if (cfg.webhook.enabled) {
		logger.info { "Webhook enabled" }
		webHook = WebHook()
		webHook.runWebhookEndpoint(cfg.webhook)
	}
}
