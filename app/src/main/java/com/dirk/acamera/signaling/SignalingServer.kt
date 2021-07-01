package com.dirk.acamera.signaling

import android.content.Context
import android.util.Log
import com.dirk.acamera.utils.buildKeyStore
import com.dirk.acamera.utils.saveToFile
import io.ktor.application.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.content.*
import io.ktor.network.tls.extensions.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.io.*
import java.security.*
import java.time.Duration
import java.util.*
import javax.net.ssl.*
import kotlin.coroutines.CoroutineContext

private const val TAG = "aCamera SignalingServer"

class SignalingServer(
    private val listener: SignalingServerListener,
    private val context: Context,
    private val port: Int = SERVER_PORT_DEFAULT
) : CoroutineScope {

    companion object {
        private const val ASSETS_FOLDER = "web"

        const val SERVER_PORT_DEFAULT = 8443
        private const val SERVER_STOP_GRACE_MILLIS = 5000L
        private const val SERVER_STOP_TIMEOUT_MILLIS = 10000L

        const val SOCKET_PATH = "/socket"
        private const val SOCKET_PING_PERIOD_SECONDS = 60L
        private const val SOCKET_TIMEOUT_SECONDS = 15L
        private const val SOCKET_MAX_FRAME_SIZE = Long.MAX_VALUE
        private const val SOCKET_MASKING = false

        const val CERT_ALIAS = "aCamera"
        private const val CERT_PASS = "android"
        private const val CERT_KEY_SIZE = 256
        private const val CERT_DAYS_VALID = 365*25L
        private val CERT_HASH_ALGORITHM = HashAlgorithm.SHA256
        private val CERT_SIGNATURE_ALGORITHM = SignatureAlgorithm.ECDSA
        private const val KEYSTORE_FILE_NAME = "ssl.keystore"
        const val KEYSTORE_PASS = "android"

        //val ks = io.ktor.network.tls.certificates.buildKeyStore {  }

        // TODO: Don't create a new certificate every time the app is restarted
        val KEYSTORE = buildKeyStore {
            certificate(CERT_ALIAS) {
                hash = CERT_HASH_ALGORITHM
                sign = CERT_SIGNATURE_ALGORITHM
                keySizeInBits = CERT_KEY_SIZE
                password = CERT_PASS
                daysValid = CERT_DAYS_VALID
            }
        }

        lateinit var sslContext: SSLContext
        lateinit var x509TrustManager: X509TrustManager
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
    private val server: NettyApplicationEngine

    init {
        Log.d(TAG, "Creating server instance...")
        server = createServer()
        Log.d(TAG, "Creating server instance done")
    }

    private fun createServer(): NettyApplicationEngine {
        if (!resourcesReady) copyWebResources()

        Log.d(TAG, "Server keystore type: ${KEYSTORE.type}")
        Log.d(TAG, "Saving certificate to file '$KEYSTORE_FILE_NAME'")
        val keyStoreFile = File(context.filesDir, KEYSTORE_FILE_NAME)
        KEYSTORE.saveToFile(keyStoreFile, KEYSTORE_PASS)
        Log.d(TAG, "Saving certificate done")

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(KEYSTORE)
        sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, tmf.trustManagers, null)
        x509TrustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

        return embeddedServer(Netty, applicationEngineEnvironment {
            sslConnector(
                KEYSTORE,
                CERT_ALIAS,
                { KEYSTORE_PASS.toCharArray() },
                { CERT_PASS.toCharArray() }
            ) {
                port = this@SignalingServer.port
                //keyStorePath = keyStore.asFile.absoluteFile
                keyStorePath = keyStoreFile.absoluteFile
            }

            module {
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
        })
    }

    fun start() = launch {
        Log.d(TAG, "Running server thread...")
        try {
            server.start(wait = false)
            // TODO: This does not tell if server is actually running
            Log.d(TAG, "Running server thread success")
            state = State.RUNNING
            listener.onServerRunning()
        } catch (error: Exception) {
            Log.e(TAG, "Server thread failed", error)
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


