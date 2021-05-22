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

class SignalingServer(private val context: Context) {

    private val server = getServerInstance()

    init {
        copyWebResources()
    }

    fun start() {
        Log.d(TAG, "starting server")
        server.start()
    }

    private fun getServerInstance(): NettyApplicationEngine {

        return embeddedServer(Netty, 8080) {
            /*install(DefaultHeaders) {
                header("X-Engine", "Netty") // will send this header with each response
            }*/

            install(CallLogging)

            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(60)
                timeout = Duration.ofSeconds(15)
                maxFrameSize = Long.MAX_VALUE
                masking = false
            }

            install(ContentNegotiation) {
                gson {
                }
            }

            val connections = Collections.synchronizedMap(mutableMapOf<String, WebSocketServerSession>())

            routing {
                webSocket(path = "/connect") {
                    val id = UUID.randomUUID().toString()
                    connections[id] = this
                    Log.d(TAG, "New client connected with ID: $id")
                    Log.d(TAG, "Connected clients: ${connections.size}")

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
                        Log.d(TAG, "Connected clients: ${connections.size}")
                    }
                }
                static("/") {
                    files(context.filesDir)
                }
            }
        }
    }

    private fun copyWebResources() {
        val files = context.assets.list("web")

        files?.forEach { path ->
            println(path)
            val input = context.assets.open("web/$path")
            val outFile = File(context.filesDir, path)
            val outStream = FileOutputStream(outFile)
            outStream.write(input.readBytes())
            outStream.close()
            input.close()
        }
    }
}
