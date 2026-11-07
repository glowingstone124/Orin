package org.qo

import com.google.gson.Gson
import io.ktor.server.engine.EmbeddedServer
import org.qo.cluster.JobStatus
import org.qo.pipeline.PipelineService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebUiApiTest {
	private var server: EmbeddedServer<*, *>? = null
	private val port = 19923
	private val client = HttpClient.newHttpClient()
	private val gson = Gson()
	private lateinit var pipelineService: PipelineService

	@BeforeTest
	fun setup() {
		pipelineService = PipelineService(
			pipelines = listOf(
				Config.Pipeline(
					name = "test-pipeline",
					triggers = listOf("manual"),
					manualToken = "test-token",
					actions = emptyList(),
				)
			),
			remotes = listOf(
				Config.Remote(name = "NodeTest", url = "http://127.0.0.1:19999", token = "rem-tok")
			),
		)
		val webhook = WebHook(pipelineService = pipelineService)
		server = webhook.createServer(
			Config.Webhook(
				enabled = true,
				endpoint = "127.0.0.1",
				port = port,
				githubSecret = "secret",
			)
		).start(wait = false)
	}

	@AfterTest
	fun tearDown() {
		server?.stop(500, 1000)
	}

	@Test
	fun `get root serves webui single page application`() {
		val request = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/"))
			.GET()
			.build()
		val response = client.send(request, HttpResponse.BodyHandlers.ofString())

		assertEquals(200, response.statusCode())
		assertTrue(response.body().contains("Orin Console") || response.body().contains("Orin running"))

		val assetReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/assets/index-DqjJGyv2.css"))
			.GET()
			.build()
		val assetResp = client.send(assetReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, assetResp.statusCode())
	}

	@Test
	fun `get status returns queue and counts`() {
		val request = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/status"))
			.GET()
			.build()
		val response = client.send(request, HttpResponse.BodyHandlers.ofString())

		assertEquals(200, response.statusCode())
		val body = gson.fromJson(response.body(), Map::class.java)
		assertEquals("UP", body["status"])
		assertEquals(1.0, (body["pipelinesCount"] as Number).toDouble())
		assertEquals(1.0, (body["remotesCount"] as Number).toDouble())
	}

	@Test
	fun `get pipelines returns pipeline metadata`() {
		val request = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/pipelines"))
			.GET()
			.build()
		val response = client.send(request, HttpResponse.BodyHandlers.ofString())

		assertEquals(200, response.statusCode())
		val list = gson.fromJson(response.body(), List::class.java)
		assertEquals(1, list.size)
		val p = list[0] as Map<*, *>
		assertEquals("test-pipeline", p["name"])
		assertEquals(true, p["hasManualToken"])
	}

	@Test
	fun `trigger and fetch jobs with logs`() {
		val triggerReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/pipelines/test-pipeline/trigger"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString("""{"token":"test-token","inputs":{"env":"prod"}}"""))
			.build()
		val triggerResp = client.send(triggerReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, triggerResp.statusCode())
		val triggerMap = gson.fromJson(triggerResp.body(), Map::class.java)
		val jobId = triggerMap["jobId"] as String
		assertNotNull(jobId)

		// Fetch jobs list
		val jobsReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/jobs"))
			.GET()
			.build()
		val jobsResp = client.send(jobsReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, jobsResp.statusCode())
		val jobsList = gson.fromJson(jobsResp.body(), List::class.java)
		assertTrue(jobsList.isNotEmpty())

		// Fetch job details and logs
		val jobDetailReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/jobs/$jobId"))
			.GET()
			.build()
		val jobDetailResp = client.send(jobDetailReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, jobDetailResp.statusCode())
		val jobDetail = gson.fromJson(jobDetailResp.body(), Map::class.java)
		assertEquals(jobId, jobDetail["id"])
		assertEquals("SUCCESS", jobDetail["status"])

		val logsReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/jobs/$jobId/logs"))
			.GET()
			.build()
		val logsResp = client.send(logsReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, logsResp.statusCode())
	}

	@Test
	fun `get cluster nodes returns configured remotes`() {
		val request = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/cluster/nodes"))
			.GET()
			.build()
		val response = client.send(request, HttpResponse.BodyHandlers.ofString())

		assertEquals(200, response.statusCode())
		val list = gson.fromJson(response.body(), List::class.java)
		assertEquals(1, list.size)
		val node = list[0] as Map<*, *>
		assertEquals("NodeTest", node["name"])
	}

	@Test
	fun `pipeline CRUD operations and evaluate duration metrics`() {
		// 1. Create pipeline
		val createBody = """
			{
			  "name": "new-pipeline",
			  "enabled": true,
			  "repository": "NewRepo",
			  "triggers": ["manual"],
			  "manualToken": "new-tok",
			  "actions": []
			}
		""".trimIndent()
		val createReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/pipelines"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(createBody))
			.build()
		val createResp = client.send(createReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(201, createResp.statusCode())

		// 2. View pipeline
		val viewReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/pipelines/new-pipeline"))
			.GET()
			.build()
		val viewResp = client.send(viewReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, viewResp.statusCode())
		val viewMap = gson.fromJson(viewResp.body(), Map::class.java)
		assertEquals("new-pipeline", viewMap["name"])
		assertEquals("NewRepo", viewMap["repository"])

		// 3. Edit pipeline
		val updateBody = """
			{
			  "name": "new-pipeline",
			  "enabled": false,
			  "repository": "UpdatedRepo",
			  "triggers": ["manual", "cluster"],
			  "manualToken": "updated-tok",
			  "actions": ["build-shell"]
			}
		""".trimIndent()
		val updateReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/pipelines/new-pipeline"))
			.header("Content-Type", "application/json")
			.PUT(HttpRequest.BodyPublishers.ofString(updateBody))
			.build()
		val updateResp = client.send(updateReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, updateResp.statusCode())

		// Verify updated
		val verifyReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/pipelines/new-pipeline"))
			.GET()
			.build()
		val verifyResp = client.send(verifyReq, HttpResponse.BodyHandlers.ofString())
		val verifyMap = gson.fromJson(verifyResp.body(), Map::class.java)
		assertEquals(false, verifyMap["enabled"])
		assertEquals("UpdatedRepo", verifyMap["repository"])

		// 4. Evaluate metrics
		val evalReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/metrics/evaluate"))
			.GET()
			.build()
		val evalResp = client.send(evalReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, evalResp.statusCode())
		val evalMap = gson.fromJson(evalResp.body(), Map::class.java)
		assertNotNull(evalMap["avgDurationMs"])

		// 5. Delete pipeline
		val delReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/pipelines/new-pipeline"))
			.DELETE()
			.build()
		val delResp = client.send(delReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, delResp.statusCode())
	}

	@Test
	fun `action script CRUD and editing operations`() {
		// 1. Create a new action script
		val createBody = """
			{
			  "name": "custom-test-action",
			  "entry": "main.kts",
			  "scriptContent": "orin.log(\"Hello from test action\")"
			}
		""".trimIndent()
		val createReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/actions"))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(createBody))
			.build()
		val createResp = client.send(createReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(201, createResp.statusCode())

		// 2. Fetch action detail
		val getReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/actions/custom-test-action"))
			.GET()
			.build()
		val getResp = client.send(getReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, getResp.statusCode())
		val detail = gson.fromJson(getResp.body(), Map::class.java)
		assertEquals("custom-test-action", detail["name"])
		assertEquals("main.kts", detail["entry"])
		assertEquals("kts", detail["entryType"])
		assertTrue((detail["content"] as String).contains("Hello from test action"))

		// 3. Edit / update action script
		val updateBody = """
			{
			  "entry": "main.kts",
			  "scriptContent": "orin.log(\"Updated script execution\")"
			}
		""".trimIndent()
		val updateReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/actions/custom-test-action"))
			.header("Content-Type", "application/json")
			.PUT(HttpRequest.BodyPublishers.ofString(updateBody))
			.build()
		val updateResp = client.send(updateReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, updateResp.statusCode())

		// Verify updated content
		val verifyResp = client.send(getReq, HttpResponse.BodyHandlers.ofString())
		val verifyDetail = gson.fromJson(verifyResp.body(), Map::class.java)
		assertTrue((verifyDetail["content"] as String).contains("Updated script execution"))

		// 4. Fetch list with details
		val listReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/actions?details=true"))
			.GET()
			.build()
		val listResp = client.send(listReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, listResp.statusCode())
		val list = gson.fromJson(listResp.body(), List::class.java)
		assertTrue(list.any { (it as Map<*, *>)["name"] == "custom-test-action" })

		// 5. Delete action script
		val delReq = HttpRequest.newBuilder()
			.uri(URI.create("http://127.0.0.1:$port/api/actions/custom-test-action"))
			.DELETE()
			.build()
		val delResp = client.send(delReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(200, delResp.statusCode())

		// Verify 404 after deletion
		val notFoundResp = client.send(getReq, HttpResponse.BodyHandlers.ofString())
		assertEquals(404, notFoundResp.statusCode())
	}
}
