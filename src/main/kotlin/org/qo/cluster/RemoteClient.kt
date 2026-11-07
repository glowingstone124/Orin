package org.qo.cluster

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import org.qo.Config
import org.qo.systemUtils.ProgressLogger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Duration

data class TriggerRequest(
	val inputs: Map<String, String> = emptyMap(),
)

data class TriggerResponse(
	val jobId: String,
	val status: String,
	val message: String? = null,
)

data class RemoteJobStatusResponse(
	val id: String,
	val pipeline: String,
	val status: JobStatus,
	val message: String? = null,
	val artifacts: List<String> = emptyList(),
)

data class RemoteJobResult(
	val jobId: String,
	val success: Boolean,
	val message: String,
	val artifacts: List<String> = emptyList(),
) {
	fun requireSuccess(): RemoteJobResult {
		if (!success) {
			throw IllegalStateException("Remote job $jobId failed: $message")
		}
		return this
	}
}

class RemoteNode(
	val remote: Config.Remote,
	val defaultToken: String? = null,
	private val httpClient: HttpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build(),
) {
	private val gson = Gson()
	private val logger = KotlinLogging.logger("RemoteNode[${remote.name}]")
	val baseUrl: String = remote.url.trimEnd('/')
	val token: String? = remote.token?.takeIf(String::isNotBlank) ?: defaultToken?.takeIf(String::isNotBlank)

	fun trigger(pipeline: String, inputs: Map<String, String> = emptyMap()): RemoteJob {
		val uri = URI.create("$baseUrl/remote/trigger/$pipeline")
		val bodyJson = gson.toJson(TriggerRequest(inputs))
		val requestBuilder = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(30))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(bodyJson))

		token?.let {
			requestBuilder.header("Authorization", "Bearer $it")
			requestBuilder.header("X-Orin-Token", it)
		}

		logger.info { "Triggering remote pipeline '$pipeline' at $uri" }
		val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
		if (response.statusCode() !in 200..202) {
			throw IllegalStateException(
				"Failed to trigger remote pipeline '$pipeline' on ${remote.name}: HTTP ${response.statusCode()} - ${response.body()}"
			)
		}

		val triggerResponse = runCatching {
			gson.fromJson(response.body(), TriggerResponse::class.java)
		}.getOrElse { error ->
			throw IllegalStateException("Invalid JSON response from remote node ${remote.name}: ${response.body()}", error)
		}

		logger.info { "Remote pipeline '$pipeline' triggered with jobId=${triggerResponse.jobId}" }
		return RemoteJob(
			jobId = triggerResponse.jobId,
			pipeline = pipeline,
			remoteNode = this,
			httpClient = httpClient,
			gson = gson,
		)
	}

	fun triggerAndWait(
		pipeline: String,
		inputs: Map<String, String> = emptyMap(),
		timeoutSeconds: Long = 900,
	): RemoteJobResult {
		val job = trigger(pipeline, inputs)
		return job.await(timeoutSeconds)
	}
}

class RemoteJob(
	val jobId: String,
	val pipeline: String,
	val remoteNode: RemoteNode,
	private val httpClient: HttpClient,
	private val gson: Gson,
) {
	private val logger = KotlinLogging.logger("RemoteJob[$jobId]")

	fun status(): RemoteJobStatusResponse {
		val uri = URI.create("${remoteNode.baseUrl}/remote/jobs/$jobId/status")
		val requestBuilder = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(15))
			.GET()

		remoteNode.token?.let {
			requestBuilder.header("Authorization", "Bearer $it")
			requestBuilder.header("X-Orin-Token", it)
		}

		val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
		if (response.statusCode() != 200) {
			throw IllegalStateException(
				"Failed to query status of remote job $jobId: HTTP ${response.statusCode()} - ${response.body()}"
			)
		}

		return gson.fromJson(response.body(), RemoteJobStatusResponse::class.java)
	}

	fun await(timeoutSeconds: Long = 900, pollIntervalMs: Long = 1000): RemoteJobResult {
		val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
		logger.info { "Waiting for remote job $jobId on ${remoteNode.remote.name} (timeout: ${timeoutSeconds}s)..." }

		while (System.currentTimeMillis() < deadline) {
			val currentStatus = status()
			when (currentStatus.status) {
				JobStatus.SUCCESS -> {
					logger.info { "Remote job $jobId completed successfully." }
					return RemoteJobResult(
						jobId = jobId,
						success = true,
						message = currentStatus.message ?: "Job completed successfully.",
						artifacts = currentStatus.artifacts,
					)
				}
				JobStatus.FAILED -> {
					logger.warn { "Remote job $jobId failed: ${currentStatus.message}" }
					return RemoteJobResult(
						jobId = jobId,
						success = false,
						message = currentStatus.message ?: "Job failed.",
						artifacts = currentStatus.artifacts,
					)
				}
				JobStatus.QUEUED, JobStatus.RUNNING -> {
					try {
						Thread.sleep(pollIntervalMs)
					} catch (e: InterruptedException) {
						Thread.currentThread().interrupt()
						throw IllegalStateException("Interrupted while waiting for remote job $jobId", e)
					}
				}
			}
		}

		throw IllegalStateException("Timed out waiting for remote job $jobId after ${timeoutSeconds}s")
	}

	fun downloadArtifact(artifactName: String, destinationPath: String): Path {
		return downloadArtifact(artifactName, Paths.get(destinationPath))
	}

	fun downloadArtifact(artifactName: String, destination: Path): Path {
		val uri = URI.create("${remoteNode.baseUrl}/remote/jobs/$jobId/artifacts/$artifactName")
		val requestBuilder = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofSeconds(60))
			.GET()

		remoteNode.token?.let {
			requestBuilder.header("Authorization", "Bearer $it")
			requestBuilder.header("X-Orin-Token", it)
		}

		logger.info { "Downloading artifact '$artifactName' from $uri to $destination" }
		val resolvedDest = destination.toAbsolutePath().normalize()
		resolvedDest.parent?.let { Files.createDirectories(it) }

		val tempFile = Files.createTempFile("orin-artifact-download", ".tmp")
		try {
			val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
			if (response.statusCode() != 200) {
				response.body().use { it.close() }
				throw IllegalStateException(
					"Failed to download artifact '$artifactName' for job $jobId: HTTP ${response.statusCode()}"
				)
			}
			val totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
			val progress = ProgressLogger(
				label = "Downloading artifact '$artifactName'",
				log = { message -> logger.info { message } },
			)
			progress.report(0, totalBytes, force = true)
			response.body().use { input ->
				Files.newOutputStream(tempFile).use { output ->
					val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
					var downloadedBytes = 0L
					while (true) {
						val read = input.read(buffer)
						if (read < 0) {
							break
						}
						if (read == 0) {
							continue
						}
						output.write(buffer, 0, read)
						downloadedBytes += read
						progress.report(downloadedBytes, totalBytes)
					}
					progress.report(downloadedBytes, totalBytes, force = true)
				}
			}
			Files.move(tempFile, resolvedDest, StandardCopyOption.REPLACE_EXISTING)
			logger.info { "Successfully downloaded artifact '$artifactName' ($resolvedDest, size: ${Files.size(resolvedDest)} bytes)" }
			return resolvedDest
		} finally {
			if (Files.exists(tempFile)) {
				Files.deleteIfExists(tempFile)
			}
		}
	}

	companion object {
		private const val DOWNLOAD_BUFFER_SIZE = 64 * 1_024
	}
}
