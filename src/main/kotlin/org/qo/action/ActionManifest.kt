package org.qo.action

import kotlinx.serialization.Serializable

@Serializable
data class ActionManifest(
	val entry: String,
	val kts: KtsConfig = KtsConfig(),
) {
	@Serializable
	data class KtsConfig(
		val expose: List<String> = listOf("pipeline", "action", "trigger", "inputs", "github", "paths"),
	)
}
