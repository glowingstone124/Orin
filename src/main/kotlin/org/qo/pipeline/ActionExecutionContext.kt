package org.qo.pipeline

import org.qo.GithubPushEvent
import org.qo.action.KtsActionContext
import java.nio.file.Path

data class ActionExecutionContext(
	val pipelineContext: PipelineContext,
	val actionName: String,
	val workspaceRoot: Path,
	val actionDirectory: Path,
	val entryPath: Path,
) {
	fun environmentVariables(): Map<String, String> {
		return pipelineContext.environmentVariables() + mapOf(
			"ORIN_ACTION" to actionName,
			"ORIN_WORKSPACE_DIR" to workspaceRoot.toString(),
			"ORIN_ACTION_DIR" to actionDirectory.toString(),
			"ORIN_ACTION_ENTRY" to entryPath.toString(),
		)
	}

	fun environmentVariables(additionalContext: Map<String, String>): Map<String, String> {
		return environmentVariables() + additionalContext.toEnvironmentVariables("ORIN_CONTEXT_")
	}

	fun toKtsActionContext(expose: List<String>): KtsActionContext {
		val exposures = expose.map { Exposure.from(it) }.toSet()
		return KtsActionContext(
			pipeline = if (Exposure.PIPELINE in exposures) {
				KtsActionContext.PipelineInfo(pipelineContext.pipeline.name)
			} else {
				null
			},
			action = if (Exposure.ACTION in exposures) {
				KtsActionContext.ActionInfo(actionName)
			} else {
				null
			},
			trigger = if (Exposure.TRIGGER in exposures) pipelineContext.trigger else null,
			inputs = if (Exposure.INPUTS in exposures) pipelineContext.inputs else emptyMap(),
			github = if (Exposure.GITHUB in exposures) pipelineContext.githubPushEvent?.toKtsGithubInfo() else null,
			paths = if (Exposure.PATHS in exposures) {
				KtsActionContext.PathsInfo(
					workspaceDir = workspaceRoot.toString(),
					actionDir = actionDirectory.toString(),
					entryPath = entryPath.toString(),
				)
			} else {
				null
			},
		)
	}

	private fun GithubPushEvent.toKtsGithubInfo(): KtsActionContext.GithubInfo {
		return KtsActionContext.GithubInfo(
			repository = repository.name,
			sender = sender.login,
			commitCount = commits.size,
			commits = commits.map { commit ->
				KtsActionContext.GithubInfo.CommitInfo(
					message = commit.message,
					author = commit.author.name,
				)
			},
		)
	}

	private enum class Exposure(val key: String) {
		PIPELINE("pipeline"),
		ACTION("action"),
		TRIGGER("trigger"),
		INPUTS("inputs"),
		GITHUB("github"),
		PATHS("paths");

		companion object {
			fun from(value: String): Exposure {
				return entries.firstOrNull { it.key == value.lowercase() }
					?: throw IllegalArgumentException("Unknown kts context exposure: $value")
			}
		}
	}

	private fun Map<String, String>.toEnvironmentVariables(prefix: String): Map<String, String> {
		return entries.associate { (key, value) ->
			val envKey = buildString {
				append(prefix)
				key.uppercase().forEach { ch ->
					append(
						when {
							ch.isLetterOrDigit() -> ch
							else -> '_'
						}
					)
				}
			}
			envKey to value
		}
	}
}
