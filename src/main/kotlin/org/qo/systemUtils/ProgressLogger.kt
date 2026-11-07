package org.qo.systemUtils

import java.util.Locale
import kotlin.math.roundToInt

class ProgressLogger(
	private val label: String,
	private val log: (String) -> Unit,
	private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
	private val barWidth: Int = DEFAULT_BAR_WIDTH,
) {
	init {
		require(label.isNotBlank()) { "Progress label cannot be blank." }
		require(intervalMillis > 0) { "Progress log interval must be positive." }
		require(barWidth > 0) { "Progress bar width must be positive." }
	}

	private var lastLoggedAt = 0L
	private var lastLoggedBytes = Long.MIN_VALUE

	fun report(completedBytes: Long, totalBytes: Long, force: Boolean = false) {
		require(completedBytes >= 0) { "Completed progress bytes cannot be negative." }
		require(totalBytes >= -1) { "Total progress bytes must be -1 or non-negative." }

		val now = System.currentTimeMillis()
		if (completedBytes == lastLoggedBytes) {
			return
		}
		if (!force && completedBytes != totalBytes && now - lastLoggedAt < intervalMillis) {
			return
		}

		lastLoggedAt = now
		lastLoggedBytes = completedBytes
		log(formatProgress(completedBytes, totalBytes))
	}

	private fun formatProgress(completedBytes: Long, totalBytes: Long): String {
		if (totalBytes < 0) {
			return "$label [unknown total] ${formatBytes(completedBytes)}"
		}

		val completedRatio = if (totalBytes == 0L) {
			1.0
		} else {
			(completedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)
		}
		val completedBarWidth = (completedRatio * barWidth).roundToInt()
		val bar = "#".repeat(completedBarWidth) + "-".repeat(barWidth - completedBarWidth)
		val percent = (completedRatio * 100).roundToInt()
		return "$label [$bar] $percent% (${formatBytes(completedBytes)}/${formatBytes(totalBytes)})"
	}

	private fun formatBytes(bytes: Long): String {
		if (bytes < 1_024) {
			return "$bytes B"
		}
		val kibibytes = bytes / 1_024.0
		if (kibibytes < 1_024) {
			return String.format(Locale.ROOT, "%.1f KiB", kibibytes)
		}
		val mebibytes = kibibytes / 1_024.0
		if (mebibytes < 1_024) {
			return String.format(Locale.ROOT, "%.1f MiB", mebibytes)
		}
		return String.format(Locale.ROOT, "%.1f GiB", mebibytes / 1_024.0)
	}

	companion object {
		private const val DEFAULT_INTERVAL_MILLIS = 500L
		private const val DEFAULT_BAR_WIDTH = 24
	}
}
