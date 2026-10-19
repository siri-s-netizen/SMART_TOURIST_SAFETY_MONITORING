package com.touristsafety.app.authority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address gate: what the Backend settings screen accepts, what it refuses, and what it stores.
 *
 * These are the rules that decide whether a typed address ever reaches a socket. Before this gate
 * existed, the field was a free-text box whose contents were only tested by dialling them, so a typo
 * came back as a socket error and the operator could not tell a wrong address from a server that was
 * off. Each case below therefore asserts either the normalised value that gets stored, or the
 * sentence the operator is shown instead.
 *
 * Plain JVM test: `AuthorityUrls` is deliberately free of Android types, so the rules can be checked
 * without a device.
 */
class AuthorityUrlsTest {

    private fun usable(raw: String): String {
        val inspected = AuthorityUrls.inspect(raw)
        assertTrue(
            "\"$raw\" should be usable, but was rejected: $inspected",
            inspected is AuthorityAddressInspection.Usable,
        )
        return (inspected as AuthorityAddressInspection.Usable).address
    }

    private fun rejection(raw: String): String {
        val inspected = AuthorityUrls.inspect(raw)
        assertTrue(
            "\"$raw\" should be rejected, but was accepted as $inspected",
            inspected is AuthorityAddressInspection.Rejected,
        )
        return (inspected as AuthorityAddressInspection.Rejected).message
    }

    // ------------------------------------------------------------------ accepted

    @Test
    fun `a bare host and port is accepted and given the http scheme`() {
        assertEquals("http://192.168.1.20:8080", usable("192.168.1.20:8080"))
    }

    @Test
    fun `surrounding whitespace is ignored, so a pasted address still works`() {
        assertEquals("http://10.0.2.2:8080", usable("  10.0.2.2:8080  "))
    }

    @Test
    fun `a trailing slash is dropped so the path join cannot double up`() {
        assertEquals("http://192.168.1.20:8080", usable("http://192.168.1.20:8080/"))
        assertEquals(
            "http://192.168.1.20:8080/api/v1/health",
            AuthorityUrls.resolve("192.168.1.20:8080/", AuthorityEndpoints.HEALTH),
        )
    }

    @Test
    fun `a secure origin is accepted as typed, for a hosted authority`() {
        assertEquals("https://safety.example.com", usable("https://safety.example.com"))
    }

    @Test
    fun `resolve joins the normalised address and the path with exactly one slash`() {
        assertEquals(
            "http://10.20.30.40:9000/api/v1/auth/login",
            AuthorityUrls.resolve("10.20.30.40:9000", AuthorityEndpoints.LOGIN),
        )
    }

    // ------------------------------------------------------------------ refused

    @Test
    fun `an empty field is refused with the example address to copy`() {
        assertTrue(rejection("   ").contains("192.168.1.20:8080"))
    }

    @Test
    fun `an address with a space is refused and says why`() {
        assertTrue(rejection("192.168.1.20 8080").contains("spaces"))
    }

    @Test
    fun `a scheme with no host is refused rather than dialled`() {
        assertTrue(rejection("http://").contains("192.168.1.20:8080"))
    }

    @Test
    fun `a port with no host is refused`() {
        assertTrue(rejection("http://:8080").contains("192.168.1.20:8080"))
    }

    @Test
    fun `a path after the address is refused instead of silently making a longer path`() {
        val message = rejection("192.168.1.20:8080/api/v1")
        assertTrue(message.contains("host and port"))
        assertTrue(message.contains("192.168.1.20:8080"))
    }

    @Test
    fun `normalise alone is not a gate, which is why inspect exists`() {
        // The two are different jobs: normalise formats, inspect decides. A blank address is not made
        // callable by either, and "http://:8080" survives formatting but is refused by the gate.
        assertEquals("", AuthorityUrls.normalise("  "))
        assertEquals("http://:8080", AuthorityUrls.normalise("http://:8080"))
        assertTrue(AuthorityUrls.inspect("http://:8080") is AuthorityAddressInspection.Rejected)
    }
}
