package org.qo.cluster

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class JobStatus {
	QUEUED,
	RUNNING,
	SUCCESS,
	FAILED
}

data class JobInfo(
	val id: String,
	val pipelineName: String,
	val trigger: String,
	val inputs: Map<String, String>,
	@Volatile var status: JobStatus = JobStatus.QUEUED,
	@Volatile var message: String? = null,
	val artifacts: ConcurrentHashMap<String, Path> = ConcurrentHashMap(),
	val createdAt: Long = System.currentTimeMillis(),
	@Volatile var completedAt: Long? = null,
	private val logLines: java.util.concurrent.CopyOnWriteArrayList<String> = java.util.concurrent.CopyOnWriteArrayList(),
) {
	fun appendLog(line: String) {
		logLines.add(line)
	}

	fun getLogs(): String = logLines.joinToString("\n")

	fun getLogLines(): List<String> = logLines.toList()
}

class JobRegistry {
	private val jobs = ConcurrentHashMap<String, JobInfo>()

	fun createJob(
		pipelineName: String,
		trigger: String,
		inputs: Map<String, String>,
		id: String = UUID.randomUUID().toString(),
	): JobInfo {
		val job = JobInfo(
			id = id,
			pipelineName = pipelineName,
			trigger = trigger,
			inputs = inputs,
		)
		jobs[id] = job
		return job
	}

	fun getJob(id: String): JobInfo? = jobs[id]

	fun registerArtifact(jobId: String, artifactName: String, path: Path) {
		val job = jobs[jobId]
			?: throw IllegalArgumentException("Job $jobId does not exist in registry.")
		job.artifacts[artifactName] = path
	}

	fun updateStatus(jobId: String, status: JobStatus, message: String? = null) {
		val job = jobs[jobId] ?: return
		job.status = status
		job.message = message
		if (message != null) {
			job.appendLog("[$status] $message")
		}
		if (status == JobStatus.SUCCESS || status == JobStatus.FAILED) {
			job.completedAt = System.currentTimeMillis()
		}
	}

	fun appendLog(jobId: String, line: String) {
		jobs[jobId]?.appendLog(line)
	}

	fun allJobs(): List<JobInfo> = jobs.values.toList()
}
