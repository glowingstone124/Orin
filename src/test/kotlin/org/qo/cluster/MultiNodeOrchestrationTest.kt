package org.qo.cluster

import org.qo.Config
import org.qo.GithubPushEvent
import org.qo.WebHook
import org.qo.pipeline.PipelineService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiNodeOrchestrationTest {
	private var serverB: io.ktor.server.engine.EmbeddedServer<*, *>? = null

	@AfterTest
	fun tearDown() {
		serverB?.stop(500, 1000)
	}

	@Test
	fun `node A receives github update, triggers node B compile, downloads binary and deploys`() {
		// 1. Setup Node B (Worker)
		val nodeBWorkspace = Files.createTempDirectory("nodeB-workspace")
		createKtsAction(
			workspaceRoot = nodeBWorkspace,
			actionName = "compile-action",
			expose = listOf("pipeline", "inputs", "paths", "job"),
			script = """
				val branch = context.inputs["branch"] ?: "unknown"
				val binFile = java.io.File(context.paths!!.actionDir, "build-output.bin")
				binFile.writeText("BIN-CONTENT-FOR-${'$'}branch")
				orin.publishArtifact("my-binary", "build-output.bin")
				orin.log("Node B: Published binary for branch ${'$'}branch")
			""".trimIndent()
		)

		val nodeBPipelineService = PipelineService(
			pipelines = listOf(
				Config.Pipeline(
					name = "compile-app",
					triggers = listOf(PipelineService.CLUSTER_TRIGGER),
					actions = listOf("compile-action"),
				)
			),
			workspaceRoot = nodeBWorkspace,
			clusterConfig = Config.Cluster(nodeToken = "cluster-secret-123"),
		)

		val nodeBPort = java.net.ServerSocket(0).use { it.localPort }
		val webhookB = WebHook(
			pipelineService = nodeBPipelineService,
			clusterConfig = Config.Cluster(nodeToken = "cluster-secret-123"),
		)
		val server = webhookB.createServer(
			Config.Webhook(
				enabled = false,
				endpoint = "127.0.0.1",
				port = nodeBPort,
				githubSecret = "unused",
			)
		)
		server.start(wait = false)
		serverB = server

		val nodeBUrl = "http://127.0.0.1:$nodeBPort"

		// 2. Setup Node A (Orchestrator)
		val nodeAWorkspace = Files.createTempDirectory("nodeA-workspace")
		createKtsAction(
			workspaceRoot = nodeAWorkspace,
			actionName = "orchestrate-deploy",
			expose = listOf("pipeline", "github", "paths"),
			script = """
				val commitMsg = context.github?.commits?.firstOrNull()?.message ?: "branch:main"
				val branch = commitMsg.substringAfter("branch:").trim()
				orin.log("Node A: Received update for branch ${'$'}branch. Triggering Node B...")

				val nodeB = orin.remote("NodeB")
				val job = nodeB.trigger("compile-app", mapOf("branch" to branch))
				val result = job.await(timeoutSeconds = 30)
				result.requireSuccess()

				val destFile = java.io.File(context.paths!!.actionDir, "deployed.bin")
				job.downloadArtifact("my-binary", destFile.absolutePath)
				orin.log("Node A: Deployed binary successfully to ${'$'}{destFile.absolutePath}")
			""".trimIndent()
		)

		val nodeAPipelineService = PipelineService(
			pipelines = listOf(
				Config.Pipeline(
					name = "task1-deploy",
					repository = "Orin",
					triggers = listOf(PipelineService.GITHUB_PUSH_TRIGGER),
					actions = listOf("orchestrate-deploy"),
				)
			),
			workspaceRoot = nodeAWorkspace,
			clusterConfig = Config.Cluster(nodeToken = "cluster-secret-123"),
			remotes = listOf(
				Config.Remote(
					name = "NodeB",
					url = nodeBUrl,
					token = "cluster-secret-123",
				)
			),
		)

		// 3. Node A receives Github Push Event
		val event = GithubPushEvent(
			repository = GithubPushEvent.Repository("Orin"),
			commits = listOf(
				GithubPushEvent.Commit(
					message = "Update server config branch:release-2.0",
					author = GithubPushEvent.Commit.Author("developer"),
				)
			),
			sender = GithubPushEvent.Sender("developer"),
		)

		val executions = nodeAPipelineService.handleGithubPush(event)

		// 4. Assertions
		assertEquals(1, executions.size)
		assertTrue(executions.single().succeeded, "Node A pipeline execution should succeed")

		val deployedFile = nodeAWorkspace.resolve("actions/orchestrate-deploy/deployed.bin")
		assertTrue(deployedFile.toFile().exists(), "Deployed file should exist on Node A")
		assertEquals("BIN-CONTENT-FOR-release-2.0", deployedFile.readText().trim())
	}

	private fun createKtsAction(
		workspaceRoot: Path,
		actionName: String,
		expose: List<String>,
		script: String,
	) {
		val actionDir = workspaceRoot.resolve("actions").resolve(actionName)
		actionDir.createDirectories()
		actionDir.resolve("entry.toml").writeText(
			buildString {
				appendLine("""entry = "main.kts"""")
				appendLine()
				appendLine("[kts]")
				append("expose = [")
				append(expose.joinToString(", ") { value -> "\"$value\"" })
				appendLine("]")
			}
		)
		actionDir.resolve("main.kts").writeText(script)
	}
}
