val githubInfo = context.github
if (githubInfo == null) {
	orin.warn("github-push-summary skipped because github context is unavailable")
} else {
	val pipelineName = context.pipeline?.name ?: "unknown"
	val actionName = context.action?.name ?: "unknown"
	orin.log(
		"pipeline=$pipelineName action=$actionName trigger=${context.trigger} repository=${githubInfo.repository} sender=${githubInfo.sender} commitCount=${githubInfo.commitCount}"
	)
	githubInfo.commits.forEach { commit ->
		orin.log("commit author=${commit.author} message=${commit.message}")
	}
}
