package com.dirk.acamera.signaling

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.websocket.*
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.network.tls.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.net.ConnectException
import java.util.*

private const val TAG = "aCamera SignalingClient"

@ObsoleteCoroutinesApi
class SignalingClient(
    private val listener: SignalingClientListener
) : CoroutineScope {

    companion object {
        // JSON strings
        private const val JSON_TYPE = "type"
        private const val JSON_SDP = "sdp"
        private const val JSON_SDP_ANDROID = "description"
        private const val JSON_SDP_MID = "sdpMid"
        private const val JSON_SDP_MLI = "sdpMLineIndex"
    }

    enum class State {
        INITIALIZING,
        CONNECTING,
        CONNECTION_ESTABLISHED,
        CONNECTION_ABORTED,
        CONNECTION_FAILED
    }

    var state = State.INITIALIZING

    private val job = Job()
    override val coroutineContext
        get() = Dispatchers.IO + job

    private val gson = Gson()

    var retriesDone = 0
    val retriesTotal = 3

    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
        engine {
            https {
                serverName = "127.0.0.1"
                cipherSuites = CIOCipherSuites.SupportedSuites
                addKeyStore(SignalingServer.KEYSTORE, SignalingServer.KEYSTORE_PASS.toCharArray() as CharArray?, SignalingServer.CERT_ALIAS)
                trustManager = SignalingServer.x509TrustManager
            }
        }
    }

    // Use Broadcast Channel to send data to clients
    private val sendChannel = ConflatedBroadcastChannel<String>()

    @ObsoleteCoroutinesApi
    fun connect(
        host: String = "127.0.0.1",
        port: Int = 8080,
        path: String = "",
        waitMillis: Long = 0
    ) = launch {
        state = State.CONNECTING

        // Web socket could not be ready yet
        // Wait a little bit before reconnecting
        delay(waitMillis)

        retriesDone++
        Log.d(TAG, "Connecting to socket '$host:$port$path' try $retriesDone of $retriesTotal")

        try {
            client.wss(
                host = host,
                port = port,
                path = path
            ) {
                // At this point the connection is established
                state = State.CONNECTION_ESTABLISHED
                Log.d(TAG, "Connection to socket established")
                listener.onConnectionEstablished()
                val sendData = sendChannel.openSubscription()

                // React to incoming and outgoing frames
                try {
                    while (true) {
                        // Poll sendData to send data (duh)
                        sendData.tryReceive().getOrNull()?.let {
                            Log.v(TAG, "Sending: $it")
                            outgoing.send(Frame.Text(it))
                        }

                        // Check if a frame is incoming
                        incoming.tryReceive().getOrNull()?.let { frame ->
                            if (frame is Frame.Text) {
                                // Get text from received data
                                val data = frame.readText()
                                Log.v(TAG, "Received: $data")
                                // Data could be "null"
                                val jsonElement: JsonElement = gson.fromJson(data, JsonElement::class.java)
                                if (jsonElement !is JsonNull) {
                                    val jsonObject = jsonElement as JsonObject

                                    withContext(Dispatchers.Main) {

                                        // Frame is an ICE candidate?
                                        if (jsonObject.has(JSON_SDP) && jsonObject.has(JSON_SDP_MID) && jsonObject.has(
                                                JSON_SDP_MLI
                                            )
                                        ) {
                                            Log.d(TAG, "Received message of type 'ICE candidate'")
                                            listener.onIceCandidateReceived(
                                                gson.fromJson(
                                                    data,
                                                    IceCandidate::class.java
                                                )
                                            )
                                        }

                                        // Frame is an ANSWER?
                                        else if (jsonObject.has(JSON_TYPE)) {
                                            // We may have to modify our message a little bit ...
                                            // ... the type has to be uppercase
                                            val jsonObjectType =
                                                jsonObject.get(JSON_TYPE).asString.uppercase(Locale.getDefault())
                                            jsonObject.remove(JSON_TYPE)
                                            jsonObject.addProperty(JSON_TYPE, jsonObjectType)
                                            // ... the description has to be called 'description'
                                            //     but browsers call it 'sdp'
                                            if (jsonObject.has(JSON_SDP)) {
                                                val jsonObjectDesc =
                                                    jsonObject.get(JSON_SDP).asString
                                                jsonObject.remove(JSON_SDP)
                                                jsonObject.addProperty(
                                                    JSON_SDP_ANDROID,
                                                    jsonObjectDesc
                                                )
                                            }
                                            // Now the modified message can be parsed to GSON
                                            // and given to the RTC client
                                            val sessionDescription = gson.fromJson(
                                                jsonObject,
                                                SessionDescription::class.java
                                            )
                                            if (sessionDescription.type == SessionDescription.Type.ANSWER) {
                                                Log.d(TAG, "Received message of type '" + SessionDescription.Type.ANSWER + "'")
                                                listener.onAnswerReceived(sessionDescription)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (error: Throwable) {
                    state = State.CONNECTION_ABORTED
                    Log.e(TAG, "Something happened that upset me :'(", error)
                    listener.onConnectionAborted()
                }
            }
        } catch (error: ConnectException) {
            state = State.CONNECTION_FAILED
            Log.d(TAG, "Failed to connect at try $retriesDone of $retriesTotal")
            Log.w(TAG, "Connection error: ", error)
            listener.onConnectionFailed()
        }
    }

    fun send(dataObject: Any?) = runBlocking {
        sendChannel.send(gson.toJson(dataObject))
    }

    fun destroy() {
        client.close()
        job.complete()
    }
}
