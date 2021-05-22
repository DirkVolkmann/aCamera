package com.dirk.acamera

import android.os.Handler
import android.util.Log
import com.google.gson.Gson
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.lang.Exception
import java.net.ConnectException

private const val TAG = "aCamera SignalingClient"

class SignalingClient(
    private val listener: SignalingClientListener,
    private val retries: Int
) : CoroutineScope {

    companion object {
        private const val SOCKET_HOST = "127.0.0.1"
        private const val SOCKET_PORT = 8080
        private const val SOCKET_PATH = "/connect"
        private const val RETRY_AFTER_MS = 15000L
    }

    var isConnected = false

    private val gson = Gson()
    private val job = Job()
    override val coroutineContext = Dispatchers.IO + job

    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
    }

    private val sendChannel = ConflatedBroadcastChannel<String>()

    init {
        for (tryNr in 1..retries) {
            if (isConnected) break
            Log.d(TAG, "Connecting to socket '$SOCKET_HOST:$SOCKET_PORT$SOCKET_PATH' try $tryNr of $retries")
            connect()
            Thread.sleep(RETRY_AFTER_MS)
        }

        if (!isConnected) {
            Log.d(TAG, "Connection to socket finally failed after $retries tries")
            listener.onConnectionFailed()
        }
    }

    private fun connect() = launch {
        try {
            client.ws(
                host = SOCKET_HOST,
                port = SOCKET_PORT,
                path = SOCKET_PATH
            ) {
                isConnected = true
                listener.onConnectionEstablished()
                Log.d(TAG, "Connection to socket established")
                val sendData = sendChannel.openSubscription()

                try {
                    while (true) {
                        sendData.poll()?.let {
                            Log.v(TAG, "Sending: $it")
                            outgoing.send(Frame.Text(it))
                        }
                        incoming.poll()?.let { frame ->
                            if (frame is Frame.Text) {
                                val data = frame.readText()
                                Log.d(TAG, "Received: $data")
                                val jsonObject = gson.fromJson(data, JsonObject::class.java)

                                withContext(Dispatchers.Main) {
                                    if (jsonObject.has("sdpMid") && jsonObject.has("sdpMLineIndex") && jsonObject.has("sdp")) {
                                        Log.d(TAG, "ICE candidate received")
                                        listener.onIceCandidateReceived(gson.fromJson(data, IceCandidate::class.java))
                                    } else if (jsonObject.has("type")) {
                                        // We may have to modify our message a little bit ...
                                        // ... the type has to be uppercase
                                        val jsonObjectType = jsonObject.get("type").asString.toUpperCase()
                                        jsonObject.remove("type")
                                        jsonObject.addProperty("type", jsonObjectType)
                                        // ... the description has to be called 'description'
                                        //     but browsers call it 'sdp'
                                        if (jsonObject.has("sdp")) {
                                            val jsonObjectDesc = jsonObject.get("sdp").asString
                                            jsonObject.remove("sdp")
                                            jsonObject.addProperty("description", jsonObjectDesc)
                                        }
                                        // Now the modified message can be parsed to GSON
                                        // and given to the RTC client
                                        val sessionDescription = gson.fromJson(jsonObject, SessionDescription::class.java)
                                        if (jsonObjectType == "OFFER") {
                                            Log.d(TAG, "Offer received")
                                            listener.onOfferReceived(sessionDescription)
                                        } else if (jsonObjectType == "ANSWER") {
                                            Log.d(TAG, "Answer received")
                                            listener.onAnswerReceived(sessionDescription)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.i(TAG, "Something happened that upset me :'(", e)
                    isConnected = false
                    //listener.onConnectionAborted()
                }
            }
        } catch (e: ConnectException) {
            Log.i(TAG, "connection error", e)
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
