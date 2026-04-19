package org.qo.action

import io.github.oshai.kotlinlogging.KotlinLogging
import org.qo.EventProcessor
import org.qo.GithubPushEvent
import org.qo.pipeline.PipelineService

class DefaultGithubPreprocessor(
	private val pipelineService: PipelineService,
) : EventProcessor<GithubPushEvent> {
	private val logger = KotlinLogging.logger("DefaultGithubPreprocessor")
	override fun process(args: GithubPushEvent) {
		val executions = pipelineService.handleGithubPush(args)
		if (executions.isEmpty()) {
			logger.info { "Github push received but no pipeline was executed for repository=${args.repository.name}" }
		}
	}
}
