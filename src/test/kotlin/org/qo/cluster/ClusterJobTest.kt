package org.qo.cluster

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClusterJobTest {
	@Test
	fun `job registry creates, updates and tracks artifacts for jobs`() {
		val registry = JobRegistry()
		val job = registry.createJob(
			pipelineName = "test-pipeline",
			trigger = "cluster",
			inputs = mapOf("branch" to "main"),
		)

		assertEquals("test-pipeline", job.pipelineName)
		assertEquals("cluster", job.trigger)
		assertEquals("main", job.inputs["branch"])
		assertEquals(JobStatus.QUEUED, job.status)
		assertNull(job.completedAt)

		registry.updateStatus(job.id, JobStatus.RUNNING, "Starting...")
		val running = registry.getJob(job.id)
		assertNotNull(running)
		assertEquals(JobStatus.RUNNING, running.status)
		assertEquals("Starting...", running.message)

		val tempArtifact = Files.createTempFile("test-artifact", ".bin")
		registry.registerArtifact(job.id, "my-artifact", tempArtifact)
		assertEquals(tempArtifact, running.artifacts["my-artifact"])

		registry.updateStatus(job.id, JobStatus.SUCCESS, "Done")
		val finished = registry.getJob(job.id)
		assertNotNull(finished)
		assertEquals(JobStatus.SUCCESS, finished.status)
		assertNotNull(finished.completedAt)
	}
}
