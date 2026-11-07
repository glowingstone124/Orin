package org.qo

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigTest {
	@Test
	fun `pipeline config parses from toml`() {
		val cfg = Toml.decodeFromString<Config.Cfg>(
			"""
				[Webhook]
				enabled = true
					endpoint = "127.0.0.1"
					port = 9912
					githubSecret = "github-secret"

					[Runner]
					queueCapacity = 25
					actionTimeoutSeconds = 60
					maxOutputBytes = 4096

					[Cluster]
					nodeToken = "cluster-secret"

					[[Remote]]
					name = "NodeB"
					url = "http://127.0.0.1:9913"
					token = "nodeb-token"

					[[Pipeline]]
				name = "default-github-build"
				enabled = true
				repository = "Orin"
				triggers = ["github.push", "manual"]
					manualToken = "manual-secret"
				actions = ["github-push-summary", "build-shell"]
			""".trimIndent()
		)

		assertEquals("127.0.0.1", cfg.webhook.endpoint)
		assertEquals("github-secret", cfg.webhook.githubSecret)
		assertEquals(25, cfg.runner.queueCapacity)
		assertEquals(60, cfg.runner.actionTimeoutSeconds)
		assertEquals(4096, cfg.runner.maxOutputBytes)
		assertEquals("cluster-secret", cfg.cluster.nodeToken)
		assertEquals(1, cfg.remotes.size)
		assertEquals("NodeB", cfg.remotes.single().name)
		assertEquals("http://127.0.0.1:9913", cfg.remotes.single().url)
		assertEquals("nodeb-token", cfg.remotes.single().token)
		assertEquals(1, cfg.pipelines.size)
		assertEquals("default-github-build", cfg.pipelines.single().name)
		assertEquals(2, cfg.pipelines.single().actions.size)
		assertEquals("github-push-summary", cfg.pipelines.single().actions.first())
		assertEquals("manual-secret", cfg.pipelines.single().manualToken)
	}
}
