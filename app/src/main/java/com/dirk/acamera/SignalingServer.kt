package com.dirk.acamera

import android.content.Context
import android.util.Log
import io.ktor.application.*
import io.ktor.features.CallLogging
import io.ktor.http.cio.websocket.*
import io.ktor.http.content.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.*
import kotlin.coroutines.CoroutineContext

private const val TAG = "aCamera SignalingServer"

class SignalingServer(
    private val listener: SignalingServerListener,
    private val context: Context
) : CoroutineScope {

    companion object {
        private const val ASSETS_FOLDER = "web"

        private const val SOCKET_PORT = 8080
        private const val SOCKET_PATH = "/socket"
        private const val SOCKET_PING_PERIOD_SECONDS = 60L
        private const val SOCKET_TIMEOUT_SECONDS = 15L
        private const val SOCKET_MAX_FRAME_SIZE = Long.MAX_VALUE
        private const val SOCKET_MASKING = false
    }

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    var connections = 0

    private var sessions = Collections.synchronizedMap(mutableMapOf<String, WebSocketServerSession>())

    private var resourcesReady = false

    private val server = embeddedServer(Netty, SOCKET_PORT) {

        if (!resourcesReady) copyWebResources()

        install(CallLogging)

        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(SOCKET_PING_PERIOD_SECONDS)
            timeout = Duration.ofSeconds(SOCKET_TIMEOUT_SECONDS)
            maxFrameSize = SOCKET_MAX_FRAME_SIZE
            masking = SOCKET_MASKING
        }

        routing {
            webSocket(path = SOCKET_PATH) {
                // Add session
                val id = UUID.randomUUID().toString()
                addSession(this, id)
                try {
                    for (data in incoming) {
                        if (data is Frame.Text) {
                            val clients = sessions.filter { it.key != id }
                            val text = data.readText()
                            clients.forEach {
                                Log.d(TAG, "Sending to: ${it.key}")
                                Log.d(TAG, "Sending: $text")
                                it.value.send(text)
                            }
                        }
                    }
                } finally {
                    removeSession(id)
                }
            }
            static("/") {
                files(context.filesDir)
            }
        }
    }

    init {
        Log.d(TAG, "Running server thread")
        start()
    }

    fun start() = launch {
        server.start(wait = true)
    }

    private fun copyWebResources() = launch(Dispatchers.IO) {
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

    private fun updateConnectionCount() {
        connections = sessions.size
        Log.d(TAG, "Connected clients: $connections")
    }

    private fun addSession(session: WebSocketServerSession, id: String) {
        Log.d(TAG, "New client connected with ID: $id")
        sessions[id] = session
        updateConnectionCount()
        launch(Dispatchers.Main) { listener.onConnectionEstablished() }
    }

    private fun removeSession(id: String) {
        Log.d(TAG, "Removing client with ID: $id")
        sessions.remove(id)
        updateConnectionCount()
        launch(Dispatchers.Main) { listener.onConnectionAborted() }
    }

    fun stop() {
        server.stop(gracePeriodMillis = 5000, timeoutMillis = 10000)
        job.complete()
    }
}
