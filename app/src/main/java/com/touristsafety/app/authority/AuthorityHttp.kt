package com.touristsafety.app.authority

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP exchange, described without reference to any particular client library. */
data class AuthorityHttpRequest(
    val method: String,
    val url: String,
    val body: String? = null,
    val bearerToken: String? = null,
)

data class AuthorityHttpResponse(
    val status: Int,
    val body: String,
)

/**
 * The socket seam.
 *
 * Split out from [AuthorityClient] so the *protocol* logic (which status means retry, which means
 * reject) can be tested against a fake, while the *sending* logic is tested against a real HTTP
 * server. A client that could only be tested with a mock would not prove that a request is ever
 * actually put on a wire.
 */
interface AuthorityHttp {
    suspend fun execute(request: AuthorityHttpRequest): AuthorityHttpResponse
}

/**
 * The production sender: `HttpURLConnection` from the JDK.
 *
 * No third-party client is added for four verbs and a bearer header — the app already talks to tile
 * servers with `HttpURLConnection`, and a new dependency here would have to be justified to the
 * review that reads `app/build.gradle.kts`.
 *
 * Every call is on [Dispatchers.IO]; on Android an un-dispatched request throws
 * `NetworkOnMainThreadException` rather than blocking.
 */
class HttpUrlConnectionAuthorityHttp(
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : AuthorityHttp {

    override suspend fun execute(request: AuthorityHttpRequest): AuthorityHttpResponse =
        withContext(Dispatchers.IO) {
            // `as?` rather than `as`: a stored address can only be http/https (the settings screen
            // refuses anything else), but a value written by an older build is not checked here, and a
            // non-HTTP scheme would otherwise throw `ClassCastException` out of the client. A failed
            // cast becomes an ordinary IOException, which the client already reports as "cannot reach".
            val connection = (URL(request.url).openConnection() as? HttpURLConnection)
                ?: throw IOException(
                    "Only http:// and https:// addresses can be dialled (asked for ${request.url}).",
                )
            try {
                connection.requestMethod = request.method
                connection.connectTimeout = connectTimeoutMillis
                connection.readTimeout = readTimeoutMillis
                // The app follows no redirects: a 3xx from an authority is a misconfiguration, and
                // silently POSTing elsewhere is worse than reporting it.
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", userAgent)
                request.bearerToken?.takeIf { it.isNotBlank() }?.let {
                    connection.setRequestProperty("Authorization", "Bearer $it")
                }
                if (request.body != null) {
                    val bytes = request.body.toByteArray(Charsets.UTF_8)
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                // Non-2xx bodies arrive on the error stream; reading the wrong one yields an empty
                // message and turns "invalid credentials" into "HTTP 401".
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                AuthorityHttpResponse(status = status, body = body)
            } finally {
                connection.disconnect()
            }
        }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Int = 8_000
        const val DEFAULT_READ_TIMEOUT_MILLIS: Int = 12_000
        const val DEFAULT_USER_AGENT: String = "TouristSafety-Android/1.0 (authority sync)"
    }
}
