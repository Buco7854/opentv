package com.buco7854.opentv.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

/** Process entry point. All construction and HTTP configuration are independently testable. */
fun main() {
    val config = ServerConfig.fromEnv()
    val runtime = ServerBootstrap.create(config)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        openTvModule(runtime.graph, runtime)
    }.start(wait = true)
}
