package cc.hafa.reader.core

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpsRedirectTest {
    private val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val secure = MockWebServer()
    private val plain = MockWebServer()
    private val fetcher = HttpFeedFetcher(
        OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build(),
    )

    @BeforeTest
    fun start() {
        secure.useHttps(serverCertificates.sslSocketFactory())
        secure.start()
        plain.start()
    }

    @AfterTest
    fun stop() {
        secure.close()
        plain.close()
    }

    @Test
    fun `a redirect from https to plain http is followed at https, never at http`() = runTest {
        val upgraded = secure.url("/moved")
        val target = upgraded.newBuilder().scheme("http").build()
        secure.enqueue(MockResponse.Builder().code(302).addHeader("Location", target.toString()).build())
        secure.enqueue(MockResponse.Builder().body("<rss/>").build())
        val outcome = fetcher.fetch(secure.url("/feed").toString(), "", "", FETCH_TIMEOUT_MS)
        assertEquals(200, outcome.status)
        assertEquals(upgraded.toString(), outcome.url)
        secure.takeRequest()
        assertEquals("/moved", secure.takeRequest().url.encodedPath)
    }

    @Test
    fun `a redirect to plain http with no https behind it fails without a plain request`() = runTest {
        secure.enqueue(MockResponse.Builder().code(302).addHeader("Location", plain.url("/feed").toString()).build())
        val outcome = fetcher.fetch(secure.url("/feed").toString(), "", "", FETCH_TIMEOUT_MS)
        assertEquals(0, outcome.status)
        assertFalse(outcome.insecure)
        // The http server sees only the https attempt's handshake, which it cannot read as a request.
        assertTrue(List(plain.requestCount) { plain.takeRequest() }.all { request -> request.failure != null })
    }

    @Test
    fun `a redirect within https is followed and its address kept`() = runTest {
        secure.enqueue(MockResponse.Builder().code(301).addHeader("Location", "/moved").build())
        secure.enqueue(MockResponse.Builder().body("<rss/>").build())
        val outcome = fetcher.fetch(secure.url("/feed").toString(), "", "", FETCH_TIMEOUT_MS)
        assertEquals(200, outcome.status)
        assertEquals(secure.url("/moved").toString(), outcome.url)
    }
}
