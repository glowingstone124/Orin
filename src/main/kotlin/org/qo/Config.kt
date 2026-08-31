package org.qo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class Config {
	@Serializable
	data class Cfg(
		@SerialName("Webhook")
		val webhook: Webhook,
		@SerialName("Pipeline")
		val pipelines: List<Pipeline> = emptyList(),
		@SerialName("Runner")
		val runner: Runner = Runner(),
	)
	@Serializable
	data class Webhook(
		val enabled: Boolean,
		val endpoint: String,
		val port: Int,
		val githubSecret: String,
		val maxRequestBytes: Long = 1_048_576,
	)

	@Serializable
	data class Runner(
		val queueCapacity: Int = 100,
		val actionTimeoutSeconds: Long = 900,
		val maxOutputBytes: Int = 1_048_576,
	)

	@Serializable
	data class Pipeline(
		val name: String,
		val enabled: Boolean = true,
		val repository: String? = null,
		val triggers: List<String> = emptyList(),
		val manualToken: String? = null,
		val actions: List<String> = emptyList(),
	)
}
