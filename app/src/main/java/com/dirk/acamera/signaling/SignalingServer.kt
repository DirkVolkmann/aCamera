package com.dirk.acamera.signaling

import android.content.Context
import android.util.Log
import io.ktor.application.*
import io.ktor.http.cio.websocket.*
import io.ktor.http.content.*
import io.ktor.network.tls.certificates.*
import io.ktor.network.tls.extensions.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.CoroutineContext

private const val TAG = "aCamera SignalingServer"

class SignalingServer(
    private val listener: SignalingServerListener,
    private val context: Context,
    port: Int = SOCKET_PORT_DEFAULT
) : CoroutineScope {

    companion object {
        private const val ASSETS_FOLDER = "web"

        private const val SERVER_STOP_GRACE_MILLIS = 5000L
        private const val SERVER_STOP_TIMEOUT_MILLIS = 10000L

        private const val SOCKET_PATH = "/socket"
        const val SOCKET_PORT_DEFAULT = 8080
        const val SOCKET_PATH = "/socket"
        private const val SOCKET_PING_PERIOD_SECONDS = 60L
        private const val SOCKET_TIMEOUT_SECONDS = 15L
        private const val SOCKET_MAX_FRAME_SIZE = Long.MAX_VALUE
        private const val SOCKET_MASKING = false

        private const val CERT_PASS = "android"
        private const val CERT_ALIAS = "sha1rsa"
        private const val CERT_KEY_SIZE = 1024
        private val CERT_HASH_ALGORITHM = HashAlgorithm.SHA1
        private val CERT_SIGNATURE_ALGORITHM = SignatureAlgorithm.RSA
        private const val KEYSTORE_FILE = "build/http.jks"
        private const val KEYSTORE_PASS = "android"
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
        server = createServer()
        start()
    }

    private fun createServer(): NettyApplicationEngine {
        if (!resourcesReady) copyWebResources()

        val keyStoreFile = File(KEYSTORE_FILE).apply {
            parentFile?.mkdirs()
        }
        Log.d("aCamera CertificateGenerator", "Hello1")
        if (!keyStoreFile.exists()) {
            generateCertificate(
                keyStoreFile,

            ) // Generates the certificate
        }
        Log.d("aCamera CertificateGenerator", "Hello2")

        val keyStore = buildKeyStore {
            certificate(CERT_ALIAS) {
                hash = CERT_HASH_ALGORITHM
                sign = CERT_SIGNATURE_ALGORITHM
                keySizeInBits = CERT_KEY_SIZE
                password = CERT_PASS
            }
        }
        Log.d(TAG, "New keystore type: ${keyStore.type}")
        keyStore.saveToFile(keyStoreFile, KEYSTORE_PASS)

        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).also {
            it.init(keyStore)
        }
        val sslContext = SSLContext.getInstance("TLS").also {
            it.init(null, tmf.trustManagers, null)
        }
        val x509TrustManager = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager

        return embeddedServer(Netty, applicationEngineEnvironment {
            sslConnector(
                keyStore,
                "sha1rsa",
                { "android".toCharArray() },
                { "android".toCharArray() }
            ) {
                port = SERVER_PORT
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
