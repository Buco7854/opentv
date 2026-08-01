package com.buco7854.opentv.server

import com.buco7854.opentv.serverdata.createOpenTvServerDatabase
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RawLiveRoomTransitionTest {
    private fun actor(authSessionId: String) = Actor(
        userId = "one-user",
        authSessionId = authSessionId,
        username = "viewer",
        displayName = "Viewer",
        roles = setOf("USER"),
        authMethod = "PASSWORD",
        clientKind = "NATIVE",
    )

    @Test
    fun `entering a raw live room closes the solo provider read before opening the relay`() =
        testApplication {
            val root = Files.createTempDirectory("raw-live-room-transition")
            val listener = ServerSocket(0, 2, java.net.InetAddress.getByName("127.0.0.1"))
            val executor = Executors.newFixedThreadPool(2)
            val firstAccepted = CountDownLatch(1)
            val firstDisconnected = CountDownLatch(1)
            val firstConnection = executor.submit {
                listener.accept().use { connection ->
                    val reader = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    firstAccepted.countDown()
                    connection.soTimeout = 2_000
                    try {
                        if (reader.read() == -1) firstDisconnected.countDown()
                    } catch (_: SocketTimeoutException) {
                        // The assertion below reports a solo provider read left open.
                    }
                }
            }

            val connections = ProviderConnections()
            val gate = StreamGate(connections)
            val cipher = StreamCipher(
                Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() }),
            )
            val http = ServerHttp()
            val proxy = StreamProxy(http, cipher, gate) { 1 }
            val sessions = PlaybackSessionRegistry(reapInBackground = false)
            val grants = PlaybackMediaGrants(sessions)
            val relay = LiveRelay(http, connections, { false })
            val database = createOpenTvServerDatabase(root.resolve("opentv.db").toString())
            val downloads = DownloadManager(
                database,
                http,
                ServerSettings(root, pageSize = 50),
                root,
                connections,
                { 1 },
            )
            val remux = RemuxService(http, connections)
            val transcoder = AudioTranscoder(http)
            val target = "http://127.0.0.1:${listener.localPort}/live/channel.ts"
            val phoneActor = actor("phone-auth")
            val televisionActor = actor("tv-auth")
            val phone = sessions.create(phoneActor, 1, "channel", target, "", "")
            val television = sessions.create(televisionActor, 1, "channel", target, "", "")
            val phoneGrant = grants.issue(phoneActor, phone.id)
            val televisionGrant = grants.issue(televisionActor, television.id)
            val media = MediaRouteDependencies(
                proxy = proxy,
                cipher = cipher,
                downloads = downloads,
                sessions = sessions,
                streamGate = gate,
                liveRelay = relay,
                transcoder = transcoder,
                remux = remux,
                mediaGrants = grants,
                connectionLimit = { 1 },
            )
            application { routing { mediaRoutes(media) } }

            try {
                coroutineScope {
                    val solo = async(Dispatchers.Default) {
                        runCatching {
                            client.get(
                                "/stream?u=${urlEncode(cipher.encryptStream(target, phone.id))}" +
                                    "&sid=${urlEncode(phone.id)}&g=${urlEncode(phoneGrant.token)}",
                            ).bodyAsBytes()
                        }
                    }
                    assertTrue(firstAccepted.await(2, TimeUnit.SECONDS))

                    val secondConnection = executor.submit<Boolean> {
                        listener.accept().use { connection ->
                            val soloWasClosed = firstDisconnected.await(1, TimeUnit.SECONDS)
                            connection.getOutputStream().use { output ->
                                output.write(
                                    ("HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: video/mp2t\r\n" +
                                        "Content-Length: 1\r\n" +
                                        "Connection: close\r\n\r\nX").toByteArray(Charsets.ISO_8859_1),
                                )
                                output.flush()
                            }
                            soloWasClosed
                        }
                    }

                    val requestId = assertNotNull(
                        sessions.requestJoin(phone.id, television.id, "Viewer's TV", "channel"),
                    )
                    assertTrue(sessions.answerJoin(phone.id, requestId, accept = true))
                    val shared = async(Dispatchers.Default) {
                        runCatching {
                            client.get(
                                "/relay?u=${urlEncode(cipher.encryptStream(target, television.id))}" +
                                    "&sid=${urlEncode(television.id)}&g=${urlEncode(televisionGrant.token)}",
                            ).bodyAsBytes()
                        }
                    }

                    assertTrue(
                        secondConnection.get(3, TimeUnit.SECONDS),
                        "the room relay opened before the old solo provider request was closed",
                    )
                    relay.close()
                    proxy.drop(phone.id)
                    solo.await()
                    shared.await()
                }
            } finally {
                relay.close()
                sessions.close()
                remux.close()
                downloads.close()
                proxy.close()
                gate.close()
                connections.closeAll()
                database.close()
                listener.close()
                firstConnection.cancel(true)
                executor.shutdownNow()
                root.toFile().deleteRecursively()
            }
        }
}
