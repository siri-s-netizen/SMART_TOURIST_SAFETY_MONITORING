package com.touristsafety.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Guards the one Android setting that decides whether the app can reach its own authority at all.
 *
 * The authority is the project's own plain-HTTP server on the local network (`http://<host>:8080`).
 * From API 28 onward Android refuses cleartext HTTP unless the app opts in, and this app targets
 * API 35 — so a build that loses this policy fails *every* call with
 * `Cleartext HTTP traffic to <host> not permitted` before a single byte reaches the network.
 *
 * That failure looks exactly like "the server is down" on the sign-in screens, which makes it easy to
 * misdiagnose; a test is the honest way to stop it coming back.
 */
class NetworkSecurityConfigTest {

    private val manifest: File = findInModule("src/main/AndroidManifest.xml")
    private val config: File = findInModule("src/main/res/xml/network_security_config.xml")

    @Test
    fun `manifest points at the network security config`() {
        val application = firstElement(manifest, "application")
        assertEquals(
            "@xml/network_security_config",
            application.getAttribute("android:networkSecurityConfig"),
        )
    }

    @Test
    fun `the config permits cleartext so the plain-HTTP authority is reachable`() {
        val baseConfig = firstElement(config, "base-config")
        assertEquals("true", baseConfig.getAttribute("cleartextTrafficPermitted"))
    }

    @Test
    fun `the manifest does not forbid cleartext globally`() {
        val application = firstElement(manifest, "application")
        val value = application.getAttribute("android:usesCleartextTraffic")
        assertTrue(
            "android:usesCleartextTraffic must not be set to false; it would re-block the authority",
            value.isEmpty() || value == "true",
        )
    }

    private fun firstElement(file: File, tag: String): Element {
        assertTrue("${file.absolutePath} should exist", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = false }
        val doc = factory.newDocumentBuilder().parse(file)
        val element = doc.getElementsByTagName(tag).item(0) as? Element
        assertNotNull("${file.name} should contain a <$tag> element", element)
        return element!!
    }

    /** Locates a module-relative file from wherever the test JVM was started. */
    private fun findInModule(relative: String): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        repeat(6) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir?.parentFile
        }
        error("Could not find $relative starting from $start")
    }
}
