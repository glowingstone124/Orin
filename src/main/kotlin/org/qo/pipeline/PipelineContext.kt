package org.qo.pipeline

import org.qo.Config
import org.qo.GithubPushEvent

data class PipelineContext(
	val pipeline: Config.Pipeline,
	val trigger: String,
	val githubPushEvent: GithubPushEvent? = null,
	val inputs: Map<String, String> = emptyMap(),
) {
	fun environmentVariables(): Map<String, String> {
		val values = linkedMapOf(
			"ORIN_PIPELINE" to pipeline.name,
			"ORIN_TRIGGER" to trigger,
		)
		githubPushEvent?.let { event ->
			values["ORIN_REPOSITORY"] = event.repository.name
			values["ORIN_SENDER"] = event.sender.login
			values["ORIN_COMMIT_COUNT"] = event.commits.size.toString()
		}
		inputs.forEach { (key, value) ->
			val envKey = buildString {
				append("ORIN_INPUT_")
				key.uppercase().forEach { ch ->
					append(
						when {
							ch.isLetterOrDigit() -> ch
							else -> '_'
						}
					)
				}
			}
			values[envKey] = value
		}
		return values
	}
}
