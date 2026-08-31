package org.qo

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class GithubWebhookAuthenticator(secret: String) {
	private val secretBytes = secret.toByteArray(StandardCharsets.UTF_8)

	init {
		require(secretBytes.isNotEmpty()) { "Github webhook secret must not be empty." }
	}

	fun verify(payload: String, signatureHeader: String?): Boolean {
		val suppliedHex = signatureHeader
			?.takeIf { it.startsWith(SIGNATURE_PREFIX) }
			?.removePrefix(SIGNATURE_PREFIX)
			?: return false
		val supplied = suppliedHex.decodeHexOrNull() ?: return false
		val mac = Mac.getInstance(HMAC_SHA_256)
		mac.init(SecretKeySpec(secretBytes, HMAC_SHA_256))
		val expected = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
		return MessageDigest.isEqual(expected, supplied)
	}

	private fun String.decodeHexOrNull(): ByteArray? {
		if (length != SHA_256_HEX_LENGTH || any { it.digitToIntOrNull(16) == null }) {
			return null
		}
		return ByteArray(length / 2) { index ->
			val offset = index * 2
			((this[offset].digitToInt(16) shl 4) or this[offset + 1].digitToInt(16)).toByte()
		}
	}

	companion object {
		private const val SIGNATURE_PREFIX = "sha256="
		private const val HMAC_SHA_256 = "HmacSHA256"
		private const val SHA_256_HEX_LENGTH = 64
	}
}

fun secureEquals(expected: String, actual: String?): Boolean {
	if (actual == null) {
		return false
	}
	return MessageDigest.isEqual(
		expected.toByteArray(StandardCharsets.UTF_8),
		actual.toByteArray(StandardCharsets.UTF_8),
	)
}
