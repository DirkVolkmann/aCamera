package com.dirk.acamera;

import android.content.Context
import android.util.Log
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.gson.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.content.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.*

private const val TAG = "aCamera Server"

class Server(
        private val context: Context
): Runnable {

    private var server: NettyApplicationEngine = getServerInstance()

    override fun run() {
        Log.d(TAG, "Running server thread")
        copyWebResources()
        server.start()
    }

    private fun getServerInstance(): NettyApplicationEngine {

        return embeddedServer(Netty, 8080) {

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
                webSocket(path = "/socket") {
                    val id = UUID.randomUUID().toString()
                    connections[id] = this
                    Log.d(TAG, "New client connected with ID: $id")
                    Log.d(TAG, "Connected clients: ${connections.size}")

                    try {
                        for (data in incoming) {
                            if (data is Frame.Binary) {
                                val clients = connections.filter { it.key != id }
                                val text = data.toString()
                                Log.d(TAG, "Received message from $id: $text")
                                if (clients.isEmpty()) {
                                    Log.d(TAG, "No one to send to :(")
                                }
                                clients.forEach {
                                    Log.d(TAG, "Sending to: ${it.key}")
                                    it.value.send(data)
                                }
                            }
                        }
                    } finally {
                        Log.d(TAG, "Removing client with ID: $id")
                        connections.remove(id)
                        Log.d(TAG, "Connected clients: ${connections.size}")
                    }
                }
                /*static("/") {
                    files(context.filesDir)
                }*/
                routing {
                    get("/") {
                        call.respondText("Hello, world!")
                    }
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
