package com.buco7854.opentv.server

import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaCapabilityTest {
    private val cipher = StreamCipher(
        Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    )
    private val connections = ProviderConnections()
    private val gate = StreamGate(connections)
    private val proxy = StreamProxy(ServerHttp(), cipher, gate) { 1 }
    private val sessions = PlaybackSessionRegistry()
    private val grants = PlaybackMediaGrants(sessions)

    @AfterTest
    fun tearDown() {
        sessions.close()
        gate.close()
        connections.closeAll()
    }

    private fun actor(id: String) =
        Actor(id, "auth-$id", id, id, setOf("USER"), "PASSWORD", "BROWSER")

    private fun queryParameter(url: String, name: String): String? =
        URI(url).query?.split('&')?.firstNotNullOfOrNull { pair ->
            pair.substringBefore('=').takeIf { it == name }
                ?.let { URLDecoder.decode(pair.substringAfter('='), Charsets.UTF_8) }
        }

    private val manifest = URI("https://provider.example/live/user/pass/42.m3u8")

    @Test
    fun `rewritten HLS child stays playable by the lease that fetched the manifest`() {
        val owner = actor("owner")
        val lease = sessions.create(owner, 1, "content", manifest.toString(), "", "")
        val grant = grants.issue(owner, lease.id)

        val rewritten = proxy.rewriteHls(
            "#EXTM3U\n#EXTINF:6.0,\nsegment1.ts\n",
            manifest,
            lease.id,
            grant.token,
        )

        val child = rewritten.lineSequence().first { it.startsWith("/api/v1/stream") }
        val capability = assertNotNull(
            cipher.tryDecryptStream(assertNotNull(queryParameter(child, "u")))
        )
        assertEquals("https://provider.example/live/user/pass/segment1.ts", capability.url)
        assertEquals(lease.id, capability.leaseId)

        assertTrue(capability.url != lease.sourceUrl)
        assertFailsWith<PlaybackRevokedException> {
            grants.validateSource(owner, lease.id, grant.token, capability.url)
        }
        grants.validateCapability(owner, lease.id, grant.token, capability)
    }

    @Test
    fun `a capability cannot be replayed by another lease`() {
        val owner = actor("owner")
        val other = actor("other")
        val lease = sessions.create(owner, 1, "content", manifest.toString(), "", "")
        val otherLease = sessions.create(other, 1, "content", manifest.toString(), "", "")
        val otherGrant = grants.issue(other, otherLease.id)

        val capability = assertNotNull(
            cipher.tryDecryptStream(cipher.encryptStream("https://provider.example/x.ts", lease.id))
        )

        assertFailsWith<PlaybackRevokedException> {
            grants.validateCapability(other, otherLease.id, otherGrant.token, capability)
        }
    }

    @Test
    fun `off-origin playlist URIs are refused instead of proxied`() {
        val lease = sessions.create(actor("owner"), 1, "content", manifest.toString(), "", "")

        val rewritten = proxy.rewriteHls(
            """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="http://169.254.169.254/latest/meta-data/"
            #EXTINF:6.0,
            https://evil.example/segment1.ts
            #EXTINF:6.0,
            http://provider.example:8080/segment2.ts
            #EXTINF:6.0,
            segment3.ts
            """.trimIndent(),
            manifest,
            lease.id,
            "grant",
        )

        assertTrue("169.254.169.254" !in rewritten)
        assertTrue("evil.example" !in rewritten)
        assertTrue("8080" !in rewritten)

        val minted = rewritten.lineSequence()
            .filter { it.trimStart().startsWith("/api/v1/stream?") }
            .mapNotNull { queryParameter(it.trim(), "u") }
            .mapNotNull(cipher::tryDecryptStream)
            .toList()
        assertEquals(
            listOf("https://provider.example/live/user/pass/segment3.ts"),
            minted.map { it.url },
        )
        assertEquals(3, rewritten.lines().count { it.startsWith("#EXTINF") })
    }

    @Test
    fun `default and explicit ports are the same origin`() {
        val lease = sessions.create(actor("owner"), 1, "content", manifest.toString(), "", "")

        val rewritten = proxy.rewriteHls(
            "#EXTM3U\nhttps://provider.example:443/segment1.ts\n",
            manifest,
            lease.id,
            "grant",
        )

        val capability = assertNotNull(
            rewritten.lineSequence().first { it.startsWith("/api/v1/stream") }
                .let { queryParameter(it, "u") }
                ?.let(cipher::tryDecryptStream)
        )
        assertEquals("https://provider.example:443/segment1.ts", capability.url)
    }

    @Test
    fun `a stream token without a lease is not a capability`() {
        assertNull(cipher.tryDecryptStream("h.not-a-token"))
        assertFailsWith<IllegalArgumentException> {
            cipher.encryptStream("https://provider.example/x.ts", "")
        }
    }
}
