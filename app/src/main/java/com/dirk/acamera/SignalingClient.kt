package com.dirk.acamera

import android.os.Handler
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.ws
import io.ktor.http.*
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.lang.Exception
import java.net.ConnectException
import java.util.*

private const val TAG = "aCamera SignalingClient"

class SignalingClient(
    private val listener: SignalingClientListener,
    private val retries: Int
) : CoroutineScope {

    // TODO: Connection parameters should be read from settings
    companion object {
        // Connection parameters
        private const val SOCKET_HOST = "127.0.0.1"
        private const val SOCKET_PORT = 8080
        private const val SOCKET_PATH = "/socket"
        private const val RETRY_AFTER_MS = 15000L

        // JSON strings
        private const val JSON_TYPE = "type"
        private const val JSON_SDP = "sdp"
        private const val JSON_SDP_ANDROID = "description"
        private const val JSON_SDP_MID = "sdpMid"
        private const val JSON_SDP_MLI = "sdpMLineIndex"

        // Session types
        private const val TYPE_ANSWER = "ANSWER"
        private const val TYPE_OFFER = "OFFER"
    }

    enum class State {
        INITIALIZING,           // Set internally
        CONNECTING_TO_SERVER,   //
        WAITING_FOR_CLIENT,     // Set by listener
        WAITING_FOR_ANSWER,     //
        WAITING_FOR_CONNECTION, //
        CONNECTION_ESTABLISHED, //
        CONNECTION_ABORTED,     //
        CONNECTION_FAILED       //
    }

    var state = State.INITIALIZING

    private val gson = Gson()
    private val job = Job()
    override val coroutineContext = Dispatchers.IO + job

    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
    }

    // Use Broadcast Channel to send data to clients
    @OptIn(ObsoleteCoroutinesApi::class)
    private val sendChannel = ConflatedBroadcastChannel<String>()

    init {
        // Connect to Signaling Server and retry if server is not up yet
        // TODO: Reconnecting should be done by watchdog
        //for (tryNr in 1..retries) {
            //if (isConnected) break
            //Log.d(TAG, "Connecting to socket '$SOCKET_HOST:$SOCKET_PORT$SOCKET_PATH' try $tryNr of $retries")
            Log.d(TAG, "Connecting to socket '$SOCKET_HOST:$SOCKET_PORT$SOCKET_PATH'")
            connect()
            //Thread.sleep(RETRY_AFTER_MS)
        //}
        //if (!isConnected) {
        //    Log.d(TAG, "Connection to socket finally failed after $retries tries")
        //    listener.onConnectionFailed()
        //}
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private fun connect() = launch {
        state = State.CONNECTING_TO_SERVER
        try {
            client.ws(
                host = SOCKET_HOST,
                port = SOCKET_PORT,
                path = SOCKET_PATH
            ) {
                // At this point the connection is established
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
                                Log.d(TAG, "Received: $data")
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
                                            Log.d(TAG, "ICE candidate received")
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
                                            /*if (sessionDescription.type == SessionDescription.Type.OFFER) {
                                            Log.d(TAG, "Offer received")
                                            listener.onOfferReceived(sessionDescription)
                                        } else */if (sessionDescription.type == SessionDescription.Type.ANSWER) {
                                                Log.d(TAG, "Answer received")
                                                listener.onAnswerReceived(sessionDescription)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Something happened that upset me :'(", e)
                    listener.onConnectionAborted()
                }
            }
        } catch (e: ConnectException) {
            Log.i(TAG, "connection error", e)
            listener.onConnectionFailed()
        }
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    fun send(dataObject: Any?) = runBlocking {
        sendChannel.send(gson.toJson(dataObject))
    }

    fun destroy() {
        client.close()
        job.complete()
    }
}
