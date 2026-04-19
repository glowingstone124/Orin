package org.qo.action

import java.nio.file.Path

data class LoadedAction(
	val name: String,
	val directory: Path,
	val manifest: ActionManifest,
	val entryPath: Path,
	val entryType: EntryType,
) {
	enum class EntryType {
		KTS,
		SHELL,
	}
}
