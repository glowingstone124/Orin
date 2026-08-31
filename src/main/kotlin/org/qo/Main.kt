package org.qo

import com.akuleshov7.ktoml.Toml
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.decodeFromString
import org.qo.systemUtils.Util
import java.nio.file.Files
import java.nio.file.Paths
import org.qo.action.DefaultGithubPreprocessor
import org.qo.pipeline.PipelineService
import org.qo.systemUtils.SystemCmdExecutor
import java.time.Duration

private val logger = KotlinLogging.logger("Main")

fun main(args: Array<String>) {
	logger.info { "Project Orin starting..." }
	if (!Util.isPosixLikeOs()) {
		logger.warn { "OS is not supported. Some features may not work properly." }
	}
	if (!Util.hasBash()) {
		logger.warn { "Bash not found. Some features may not work properly." }
	}
	val cfg = Toml.decodeFromString<Config.Cfg>(Files.readString(Paths.get("config.toml")))
	cfg.pipelines
		.filter { it.enabled && it.triggers.contains(PipelineService.MANUAL_TRIGGER) }
		.forEach { pipeline ->
			require(!pipeline.manualToken.isNullOrBlank()) {
				"Manual trigger authentication is not configured for pipeline ${pipeline.name}."
			}
		}
	val pipelineService = PipelineService(
		pipelines = cfg.pipelines,
		systemCmdExecutor = SystemCmdExecutor(
			timeout = Duration.ofSeconds(cfg.runner.actionTimeoutSeconds),
			maxOutputBytes = cfg.runner.maxOutputBytes,
		),
		queueCapacity = cfg.runner.queueCapacity,
	)
	ActionsDiscover.register(GithubPushEvent::class.java, DefaultGithubPreprocessor(pipelineService))
	if (cfg.webhook.enabled) {
		require(cfg.webhook.githubSecret.isNotBlank()) {
			"Webhook is enabled but githubSecret is missing or empty."
		}
		logger.info { "Webhook enabled" }
		WebHook(
			pipelineService = pipelineService,
			githubAuthenticator = GithubWebhookAuthenticator(cfg.webhook.githubSecret),
		).runWebhookEndpoint(cfg.webhook)
	}
}
