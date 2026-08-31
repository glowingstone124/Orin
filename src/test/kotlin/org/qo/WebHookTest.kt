package org.qo

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebHookTest {
	@AfterTest
	fun tearDown() {
		ActionsDiscover.clear()
	}

	@Test
	fun `dispatch runs registered processors for matching event type`() {
		val received = mutableListOf<GithubPushEvent>()
		ActionsDiscover.register(GithubPushEvent::class.java, object : EventProcessor<GithubPushEvent> {
			override fun process(args: GithubPushEvent) {
				received += args
			}
		})
		val event = sampleEvent()

		val processorCount = ActionsDiscover.dispatch(event)

		assertEquals(1, processorCount)
		assertEquals(listOf(event), received)
	}

	@Test
	fun `parseGithubWebHook returns false for invalid payload`() {
		assertFalse(WebHook().parseGithubWebHook("not-json"))
		assertFalse(WebHook().parseGithubWebHook("{}"))
	}

	@Test
	fun `parseGithubWebHook parses payload and dispatches processors`() {
		val received = mutableListOf<GithubPushEvent>()
		ActionsDiscover.register(GithubPushEvent::class.java, object : EventProcessor<GithubPushEvent> {
			override fun process(args: GithubPushEvent) {
				received += args
			}
		})

		val result = WebHook().parseGithubWebHook(samplePayload())

		assertTrue(result)
		assertEquals(1, received.size)
		assertEquals("Orin", received.single().repository.name)
		assertEquals("Initial commit", received.single().commits.single().message)
	}

	private fun sampleEvent(): GithubPushEvent =
		GithubPushEvent(
			repository = GithubPushEvent.Repository("Orin"),
			commits = listOf(
				GithubPushEvent.Commit(
					message = "Initial commit",
					author = GithubPushEvent.Commit.Author("alice")
				)
			),
			sender = GithubPushEvent.Sender("alice")
		)

	private fun samplePayload(): String =
		"""
			{
			  "repository": {
			    "name": "Orin"
			  },
			  "commits": [
			    {
			      "message": "Initial commit",
			      "author": {
			        "name": "alice"
			      }
			    }
			  ],
			  "sender": {
			    "login": "alice"
			  }
			}
		""".trimIndent()
}
