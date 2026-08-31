package org.qo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookSecurityTest {
	@Test
	fun `github signature matches known hmac vector`() {
		val authenticator = GithubWebhookAuthenticator("It's a Secret to Everybody")

		assertTrue(
			authenticator.verify(
				payload = "Hello, World!",
				signatureHeader = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17",
			)
		)
	}

	@Test
	fun `github signature rejects missing malformed and modified signatures`() {
		val authenticator = GithubWebhookAuthenticator("secret")

		assertFalse(authenticator.verify("payload", null))
		assertFalse(authenticator.verify("payload", "sha1=invalid"))
		assertFalse(authenticator.verify("payload", "sha256=invalid"))
		assertFalse(
			authenticator.verify(
				payload = "modified",
				signatureHeader = "sha256=239f59ed55e737c77147cf55ad0c1b030b6d7ee748a7426952f9b852d5a935e5",
			)
		)
	}

	@Test
	fun `secure string comparison rejects missing and different token`() {
		assertTrue(secureEquals("secret", "secret"))
		assertFalse(secureEquals("secret", "different"))
		assertFalse(secureEquals("secret", null))
	}
}
