package org.qo

import org.qo.pipeline.PipelineService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PipelineServiceTest {
	@Test
	fun `manual trigger executes shell action from action directory`() {
		val workspaceRoot = Files.createTempDirectory("orin-shell-workspace")
		createShellAction(workspaceRoot, "manual-build") {
			"""
				printf '%s' "${'$'}ORIN_PIPELINE|${'$'}ORIN_ACTION|${'$'}ORIN_TRIGGER|${'$'}ORIN_INPUT_BRANCH" > shell-output.txt
			""".trimIndent()
		}
		val service = PipelineService(
			pipelines = listOf(
				Config.Pipeline(
					name = "manual-build",
					triggers = listOf(PipelineService.MANUAL_TRIGGER),
					manualToken = "secret",
					actions = listOf("manual-build"),
				)
			),
			workspaceRoot = workspaceRoot,
		)

		val result = service.triggerPipeline(
			name = "manual-build",
			request = PipelineService.ManualTriggerRequest(
				token = "secret",
				inputs = mapOf("branch" to "main"),
			)
		)

		assertEquals(PipelineService.ManualTriggerStatus.SUCCESS, result.status)
		assertNotNull(result.execution)
		assertEquals(
			"manual-build|manual-build|manual|main",
			workspaceRoot.resolve("actions/manual-build/shell-output.txt").readText()
		)
	}

	@Test
	fun `github push executes kts action and exposes requested context`() {
		val workspaceRoot = Files.createTempDirectory("orin-kts-workspace")
		createKtsAction(
			workspaceRoot = workspaceRoot,
			actionName = "github-summary",
			expose = listOf("pipeline", "trigger", "github", "paths"),
			script = """
				val github = context.github ?: orin.fail("github context missing")
				val pipeline = context.pipeline ?: orin.fail("pipeline context missing")
				val paths = context.paths ?: orin.fail("paths context missing")
				val output = java.io.File(paths.actionDir, "kts-output.txt")
				output.writeText("${'$'}{pipeline.name}|${'$'}{context.trigger}|${'$'}{github.repository}|${'$'}{github.sender}|${'$'}{github.commitCount}|${'$'}{context.inputs.isEmpty()}")
			""".trimIndent()
		)
		val service = PipelineService(
			pipelines = listOf(
				Config.Pipeline(
					name = "github-build",
					repository = "Orin",
					triggers = listOf(PipelineService.GITHUB_PUSH_TRIGGER),
					actions = listOf("github-summary"),
				)
			),
			workspaceRoot = workspaceRoot,
		)

		val executions = service.handleGithubPush(sampleEvent())

		assertEquals(1, executions.size)
		assertTrue(executions.single().succeeded)
		assertEquals(
			"github-build|github.push|Orin|alice|1|true",
			workspaceRoot.resolve("actions/github-summary/kts-output.txt").readText()
		)
	}

	@Test
	fun `kts action only receives fields declared in expose`() {
		val workspaceRoot = Files.createTempDirectory("orin-kts-expose")
		createKtsAction(
			workspaceRoot = workspaceRoot,
			actionName = "limited-kts",
			expose = listOf("pipeline", "paths"),
			script = """
				val paths = context.paths ?: orin.fail("paths context missing")
				val output = java.io.File(paths.actionDir, "limited-output.txt")
				output.writeText("${'$'}{context.pipeline?.name}|${'$'}{context.trigger}|${'$'}{context.github}|${'$'}{context.inputs.size}")
			""".trimIndent()
		)
		val service = PipelineService(
			pipelines = listOf(
				Config.Pipeline(
					name = "manual-build",
					triggers = listOf(PipelineService.MANUAL_TRIGGER),
					manualToken = "secret",
					actions = listOf("limited-kts"),
				)
			),
			workspaceRoot = workspaceRoot,
		)

		val result = service.triggerPipeline(
			name = "manual-build",
			request = PipelineService.ManualTriggerRequest(
				token = "secret",
				inputs = mapOf("branch" to "main"),
			)
		)

		assertEquals(PipelineService.ManualTriggerStatus.SUCCESS, result.status)
		assertEquals(
			"manual-build|null|null|0",
			workspaceRoot.resolve("actions/limited-kts/limited-output.txt").readText()
		)
	}

	@Test
	fun `kts action api execute methods include command context and structured result`() {
		val workspaceRoot = Files.createTempDirectory("orin-kts-api")
		val actionDir = workspaceRoot.resolve("actions/command-api")
		actionDir.createDirectories()
		actionDir.resolve("entry.toml").writeText(
			"""
				entry = "main.kts"

				[kts]
				expose = ["pipeline", "action", "trigger", "paths"]
			""".trimIndent()
		)
		actionDir.resolve("helper.sh").writeText(
			"""printf '%s' "${'$'}ORIN_ACTION|${'$'}ORIN_CONTEXT_STAGE|${'$'}ORIN_CONTEXT_BUILD_ID""""
		)
		actionDir.resolve("main.kts").writeText(
			"""
				val paths = context.paths ?: orin.fail("paths context missing")
				val bashResult = orin.executeByBash(
					command = "printf '%s' \"\${'$'}ORIN_PIPELINE|\${'$'}ORIN_TRIGGER|\${'$'}ORIN_CONTEXT_BRANCH\"",
					context = mapOf("branch" to "main")
				).requireSuccess()
				val shResult = orin.executeSh(
					scriptPath = "helper.sh",
					context = mapOf("stage" to "release", "build-id" to "42")
				).requireSuccess()
				java.io.File(paths.actionDir, "command-result.txt").writeText(
					buildString {
						appendLine("bash.success=${'$'}{bashResult.success}")
						appendLine("bash.exitCode=${'$'}{bashResult.exitCode}")
						appendLine("bash.stdout=${'$'}{bashResult.stdoutTrimmed}")
						appendLine("bash.context.pipeline=${'$'}{bashResult.context.pipeline}")
						appendLine("bash.context.action=${'$'}{bashResult.context.action}")
						appendLine("bash.context.trigger=${'$'}{bashResult.context.trigger}")
						appendLine("bash.context.extra.branch=${'$'}{bashResult.context.extra["branch"]}")
						appendLine("sh.stdout=${'$'}{shResult.stdoutTrimmed}")
						appendLine("sh.context.extra.stage=${'$'}{shResult.context.extra["stage"]}")
						append("sh.context.extra.buildId=${'$'}{shResult.context.extra["build-id"]}")
					}
				)
			""".trimIndent()
		)
		val service = PipelineService(
			pipelines = listOf(
				Config.Pipeline(
					name = "manual-build",
					triggers = listOf(PipelineService.MANUAL_TRIGGER),
					manualToken = "secret",
					actions = listOf("command-api"),
				)
			),
			workspaceRoot = workspaceRoot,
		)

		val result = service.triggerPipeline(
			name = "manual-build",
			request = PipelineService.ManualTriggerRequest(
				token = "secret",
			)
		)

		assertEquals(PipelineService.ManualTriggerStatus.SUCCESS, result.status)
		val actual = actionDir.resolve("command-result.txt").readText().lines()
		assertEquals(
			listOf(
				"bash.success=true",
				"bash.exitCode=0",
				"bash.stdout=manual-build|manual|main",
				"bash.context.pipeline=manual-build",
				"bash.context.action=command-api",
				"bash.context.trigger=manual",
				"bash.context.extra.branch=main",
				"sh.stdout=command-api|release|42",
				"sh.context.extra.stage=release",
				"sh.context.extra.buildId=42",
			),
			actual
		)
	}

	@Test
	fun `manual trigger rejects invalid token`() {
		val workspaceRoot = Files.createTempDirectory("orin-invalid-token")
		createShellAction(workspaceRoot, "manual-build") {
			"echo should-not-run"
		}
		val service = PipelineService(
			pipelines = listOf(
				Config.Pipeline(
					name = "manual-build",
					triggers = listOf(PipelineService.MANUAL_TRIGGER),
					manualToken = "secret",
					actions = listOf("manual-build"),
				)
			),
			workspaceRoot = workspaceRoot,
		)

		val result = service.triggerPipeline(
			name = "manual-build",
			request = PipelineService.ManualTriggerRequest(token = "wrong"),
		)

		assertEquals(PipelineService.ManualTriggerStatus.UNAUTHORIZED, result.status)
	}

	@Test
	fun `pipeline stops after first failed action`() {
		val workspaceRoot = Files.createTempDirectory("orin-fail-fast")
		createShellAction(workspaceRoot, "fail") { "exit 7" }
		createShellAction(workspaceRoot, "must-not-run") { "touch should-not-exist" }
		val service = PipelineService(
			pipelines = listOf(
				Config.Pipeline(
					name = "fail-fast",
					triggers = listOf(PipelineService.MANUAL_TRIGGER),
					manualToken = "secret",
					actions = listOf("fail", "must-not-run"),
				)
			),
			workspaceRoot = workspaceRoot,
		)

		val result = service.triggerPipeline(
			name = "fail-fast",
			request = PipelineService.ManualTriggerRequest(token = "secret"),
		)

		assertEquals(PipelineService.ManualTriggerStatus.FAILED, result.status)
		assertEquals(1, result.execution?.actionCount)
		assertFalse(workspaceRoot.resolve("actions/must-not-run/should-not-exist").toFile().exists())
	}

	@Test
	fun `bounded runner rejects github delivery when queue is full`() {
		val workspaceRoot = Files.createTempDirectory("orin-bounded-queue")
		createShellAction(workspaceRoot, "slow") {
			"""
				touch started
				while [ ! -f release ]; do sleep 0.01; done
				printf 'done\n' >> executions
			""".trimIndent()
		}
		val service = PipelineService(
			pipelines = listOf(
				Config.Pipeline(
					name = "bounded",
					repository = "Orin",
					triggers = listOf(PipelineService.GITHUB_PUSH_TRIGGER),
					actions = listOf("slow"),
				)
			),
			workspaceRoot = workspaceRoot,
			queueCapacity = 1,
		)
		val actionDirectory = workspaceRoot.resolve("actions/slow")

		service.enqueueGithubPush(sampleEvent())
		waitUntil { actionDirectory.resolve("started").toFile().exists() }
		service.enqueueGithubPush(sampleEvent())

		assertFailsWith<PipelineService.PipelineQueueFullException> {
			service.enqueueGithubPush(sampleEvent())
		}

		actionDirectory.resolve("release").writeText("")
		waitUntil {
			actionDirectory.resolve("executions").toFile().takeIf { it.exists() }
				?.readLines()?.size == 2
		}
	}

	private fun waitUntil(condition: () -> Boolean) {
		val deadline = System.nanoTime() + 5_000_000_000L
		while (!condition()) {
			check(System.nanoTime() < deadline) { "Timed out waiting for test condition." }
			Thread.sleep(10)
		}
	}

	private fun createShellAction(
		workspaceRoot: Path,
		actionName: String,
		scriptFactory: () -> String,
	) {
		val actionDir = workspaceRoot.resolve("actions").resolve(actionName)
		actionDir.createDirectories()
		actionDir.resolve("entry.toml").writeText("""entry = "main.sh"""")
		actionDir.resolve("main.sh").writeText(scriptFactory())
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

	private fun sampleEvent(): GithubPushEvent =
		GithubPushEvent(
			repository = GithubPushEvent.Repository("Orin"),
			commits = listOf(
				GithubPushEvent.Commit(
					message = "Initial commit",
					author = GithubPushEvent.Commit.Author("alice")
				)
			),
			sender = GithubPushEvent.Sender("alice")
		)
}
