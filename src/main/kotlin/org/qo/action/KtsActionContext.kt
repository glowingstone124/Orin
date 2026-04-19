package org.qo.action

data class KtsActionContext(
	val pipeline: PipelineInfo? = null,
	val action: ActionInfo? = null,
	val trigger: String? = null,
	val inputs: Map<String, String> = emptyMap(),
	val github: GithubInfo? = null,
	val paths: PathsInfo? = null,
) {
	data class PipelineInfo(
		val name: String,
	)

	data class ActionInfo(
		val name: String,
	)

	data class GithubInfo(
		val repository: String,
		val sender: String,
		val commitCount: Int,
		val commits: List<CommitInfo>,
	) {
		data class CommitInfo(
			val message: String,
			val author: String,
		)
	}

	data class PathsInfo(
		val workspaceDir: String,
		val actionDir: String,
		val entryPath: String,
	)
}
