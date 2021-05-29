package com.dirk.acamera

import android.content.Context
import android.util.Log
import io.ktor.application.*
import io.ktor.features.CallLogging
import io.ktor.features.ContentNegotiation
import io.ktor.gson.gson
import io.ktor.http.cio.websocket.*
import io.ktor.http.content.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.WebSocketServerSession
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.*

private const val TAG = "aCamera SignalingServer"

class SignalingServer(
    private val listener: SignalingServerListener,
    private val context: Context
) : Runnable {

    companion object {
        private const val ASSETS_FOLDER = "web"

        private const val SOCKET_PORT = 8080
        private const val SOCKET_PATH = "/socket"
        private const val SOCKET_PING_PERIOD_SECONDS = 60L
        private const val SOCKET_TIMEOUT_SECONDS = 15L
        private const val SOCKET_MAX_FRAME_SIZE = Long.MAX_VALUE
        private const val SOCKET_MASKING = false
    }

    private val server = getServerInstance()
    var connections = 0

    override fun run() {
        Log.d(TAG, "Running server thread")
        copyWebResources()
        server.start()
    }

    private fun getServerInstance(): NettyApplicationEngine {

        return embeddedServer(Netty, SOCKET_PORT) {

            install(CallLogging)

            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(SOCKET_PING_PERIOD_SECONDS)
                timeout = Duration.ofSeconds(SOCKET_TIMEOUT_SECONDS)
                maxFrameSize = SOCKET_MAX_FRAME_SIZE
                masking = SOCKET_MASKING
            }

            install(ContentNegotiation) {
                gson {
                }
            }

            var connections = Collections.synchronizedMap(mutableMapOf<String, WebSocketServerSession>())

            routing {
                webSocket(path = SOCKET_PATH) {
                    val id = UUID.randomUUID().toString()
                    Log.d(TAG, "New client connected with ID: $id")
                    connections[id] = this
                    this@SignalingServer.connections = connections.size
                    Log.d(TAG, "Connected clients: ${this@SignalingServer.connections}")
                    listener.onConnectionEstablished()

                    try {
                        for (data in incoming) {
                            if (data is Frame.Text) {
                                val clients = connections.filter { it.key != id }
                                val text = data.readText()
                                clients.forEach {
                                    Log.d(TAG, "Sending to: ${it.key}")
                                    Log.d(TAG, "Sending: $text")
                                    it.value.send(text)
                                }
                            }
                        }
                    } finally {
                        Log.d(TAG, "Removing client with ID: $id")
                        connections.remove(id)
                        this@SignalingServer.connections = connections.size
                        Log.d(TAG, "Connected clients: ${this@SignalingServer.connections}")
                        listener.onConnectionAborted()
                    }
                }
                static("/") {
                    files(context.filesDir)
                }
            }
        }
    }

    private fun copyWebResources() {
        val files = context.assets.list(ASSETS_FOLDER)

        files?.forEach { path ->
            println(path)
            val input = context.assets.open("$ASSETS_FOLDER/$path")
            val outFile = File(context.filesDir, path)
            val outStream = FileOutputStream(outFile)
            outStream.write(input.readBytes())
            outStream.close()
            input.close()
        }
    }
}
