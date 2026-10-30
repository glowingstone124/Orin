package org.qo

import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals

class GithubPingTest {
    @Test
    fun `webhook ping verifies its signature without dispatching a push`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = WebHook(githubAuthenticator=GithubWebhookAuthenticator("test-secret"))
            .createServer(Config.Webhook(true, "127.0.0.1", port, "test-secret"))
        server.start(wait=false)
        try {
            val body = "{\"zen\":\"ping\"}"
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("test-secret".toByteArray(), "HmacSHA256")) }
            val signature = "sha256=" + mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
            fun send(sig: String) = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/webhook/github"))
                .header("X-GitHub-Event", "ping").header("X-Hub-Signature-256", sig)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, send(signature).statusCode())
            assertEquals(401, send("sha256=" + "0".repeat(64)).statusCode())
        } finally { server.stop(100, 1000) }
    }
}
