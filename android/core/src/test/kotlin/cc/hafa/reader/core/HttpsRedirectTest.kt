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
    fun `a redirect from https to plain http counts as no answer`() = runTest {
        secure.enqueue(MockResponse.Builder().code(302).addHeader("Location", plain.url("/feed").toString()).build())
        val outcome = fetcher.fetch(secure.url("/feed").toString(), "", "", FETCH_TIMEOUT_MS)
        assertEquals(0, outcome.status)
        assertTrue(outcome.insecure)
        assertEquals(0, plain.requestCount)
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
