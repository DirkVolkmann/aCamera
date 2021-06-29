package com.dirk.acamera.signaling

import android.content.Context
import android.util.Log
import io.ktor.application.*
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
    private val context: Context,
    port: Int = SOCKET_PORT_DEFAULT
) : CoroutineScope {

    companion object {
        private const val ASSETS_FOLDER = "web"

        const val SOCKET_PORT_DEFAULT = 8080
        const val SOCKET_PATH = "/socket"
        private const val SOCKET_PING_PERIOD_SECONDS = 60L
        private const val SOCKET_TIMEOUT_SECONDS = 15L
        private const val SOCKET_MAX_FRAME_SIZE = Long.MAX_VALUE
        private const val SOCKET_MASKING = false

        private const val SERVER_STOP_GRACE_MILLIS = 5000L
        private const val SERVER_STOP_TIMEOUT_MILLIS = 10000L
    }

    // TODO: Try different ports in case one is already in use
    enum class State {
        INITIALIZING,
        RUNNING,
        FAILED
    }

    var state = State.INITIALIZING
    var connections = 0
    private var sessions = Collections.synchronizedMap(mutableMapOf<String, WebSocketServerSession>())
    private var resourcesReady = false

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private val server = embeddedServer(Netty, port) {
        if (!resourcesReady) copyWebResources()

        // Web socket is used for local and remote signaling clients
        install(WebSockets) {
            pingPeriod = Duration.ofSeconds(SOCKET_PING_PERIOD_SECONDS)
            timeout = Duration.ofSeconds(SOCKET_TIMEOUT_SECONDS)
            maxFrameSize = SOCKET_MAX_FRAME_SIZE
            masking = SOCKET_MASKING
        }

        // Static content can be accessed by the remote client
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
                                Log.v(TAG, "Sending to: ${it.key}")
                                Log.v(TAG, "Sending: $text")
                                it.value.send(text)
                            }
                        }
                    }
                } finally {
                    removeSession(id)
                }
            }
            static("") {
                files(context.filesDir)
            }
            default(File(context.filesDir, "index.html"))
        }
    }

    fun start() = launch {
        Log.d(TAG, "Running server thread...")
        try {
            server.start(wait = false)
            Log.d(TAG, "Running server thread success")
            state = State.RUNNING
            listener.onServerRunning()
        } catch (error: Exception) {
            Log.d(TAG, "Running server thread failed")
            state = State.FAILED
            listener.onServerFailed()
        }
    }

    private fun copyWebResources() = launch(Dispatchers.IO) {
        Log.d(TAG, "Copying web resources started...")
        val files = context.assets.list(ASSETS_FOLDER)

        files?.forEach { path ->
            Log.v(TAG, "Copy resource: $path")
            val input = context.assets.open("$ASSETS_FOLDER/$path")
            val outFile = File(context.filesDir, path)
            val outStream = FileOutputStream(outFile)
            outStream.write(input.readBytes())
            outStream.close()
            input.close()
        }

        resourcesReady = true
        Log.d(TAG, "Copying web resources done")
    }

    private fun updateConnectionCount() {
        connections = sessions.size
        Log.d(TAG, "Connected clients: $connections")
    }

    private fun addSession(session: WebSocketServerSession, id: String) {
        Log.v(TAG, "New client connected with ID: $id")
        sessions[id] = session
        updateConnectionCount()
        listener.onConnectionEstablished()
    }

    private fun removeSession(id: String) {
        Log.v(TAG, "Removing client with ID: $id")
        sessions.remove(id)
        updateConnectionCount()
        listener.onConnectionAborted()
    }

    fun stop() = launch {
        server.stop(gracePeriodMillis = SERVER_STOP_GRACE_MILLIS, timeoutMillis = SERVER_STOP_TIMEOUT_MILLIS)
        job.complete()
    }
}
