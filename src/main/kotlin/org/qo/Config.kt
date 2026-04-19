package org.qo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class Config {
	@Serializable
	data class Cfg(
		@SerialName("Webhook")
		val webhook: Webhook,
		@SerialName("Pipeline")
		val pipelines: List<Pipeline> = emptyList()
	)
	@Serializable
	data class Webhook(
		val enabled: Boolean,
		val endpoint: String,
		val port: Int,
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
