package org.qo.action

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.Path

class ActionLoader(
	private val actionsRoot: Path,
) {
	fun load(name: String): LoadedAction = runCatching {
		val normalizedRoot = actionsRoot.toAbsolutePath().normalize()
		val actionDirectory = normalizedRoot.resolve(name).normalize()
		require(actionDirectory.startsWith(normalizedRoot)) {
			"Action $name resolves outside actions root."
		}
		require(Files.isDirectory(actionDirectory)) {
			"Action directory not found: $actionDirectory"
		}

		val manifestPath = actionDirectory.resolve("entry.toml")
		require(Files.isRegularFile(manifestPath)) {
			"Action manifest not found: $manifestPath"
		}

		val manifest = Toml.decodeFromString<ActionManifest>(Files.readString(manifestPath))
		val entryPath = actionDirectory.resolve(manifest.entry).normalize()
		require(entryPath.startsWith(actionDirectory)) {
			"Action entry for $name resolves outside action directory."
		}
		require(Files.isRegularFile(entryPath)) {
			"Action entry file not found: $entryPath"
		}

		LoadedAction(
			name = name,
			directory = actionDirectory,
			manifest = manifest,
			entryPath = entryPath,
			entryType = entryTypeOf(entryPath),
		)
	}.getOrElse { error ->
		throw IllegalArgumentException("Failed to load action $name", error)
	}

	private fun entryTypeOf(path: Path): LoadedAction.EntryType {
		return when (path.fileName.toString().substringAfterLast('.', "")) {
			"kts" -> LoadedAction.EntryType.KTS
			"sh" -> LoadedAction.EntryType.SHELL
			else -> throw IllegalArgumentException(
				"Unsupported action entry type for $path. Only .kts and .sh are supported."
			)
		}
	}
}
