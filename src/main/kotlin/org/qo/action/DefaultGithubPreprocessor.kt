package org.qo.action

import io.github.oshai.kotlinlogging.KotlinLogging
import org.qo.EventProcessor
import org.qo.GithubPushEvent

class DefaultGithubPreprocessor : EventProcessor<GithubPushEvent> {
	private val logger = KotlinLogging.logger("DefaultGithubPreprocessor")
	override fun process(args: GithubPushEvent) {
		args.commits.forEach { commit ->
			val author = commit.author.name
			logger.info{
				"""
					--------------------------------------------
					Author: $author
					Description: ${commit.message}
				""".trimIndent()
			}
		}
	}
}
