package org.qo.systemUtils

object Util {
	fun isPosixLikeOs(): Boolean {
		val os = System.getProperty("os.name")?.lowercase() ?: return false
		return os.contains("linux") ||
				os.contains("mac") ||
				os.contains("darwin") ||
				os.contains("nix") ||
				os.contains("nux") ||
				os.contains("aix") ||
				os.contains("bsd") ||
				os.contains("sunos")
	}
	fun hasBash(): Boolean {
		return try {
			val process = ProcessBuilder("bash", "--version")
				.redirectErrorStream(true)
				.start()
			process.waitFor() == 0
		} catch (e: Exception) {
			false
		}
	}
}