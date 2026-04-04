package org.qo

import kotlinx.serialization.Serializable

class Config {
	@Serializable
	data class Cfg(
		val webhook: Webhook
	)
	@Serializable
	data class Webhook(
		val enabled: Boolean,
		val endpoint: String,
		val port: Int,
	)
}