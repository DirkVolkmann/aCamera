package com.dirk.acamera

import android.media.Image
import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.websocket.*
import io.ktor.http.cio.websocket.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import java.net.ConnectException

private const val TAG = "aCamera Client"

class Client(): CoroutineScope {

    companion object {
        private const val SOCKET_HOST = "127.0.0.1"
        private const val SOCKET_PORT = 8080
        private const val SOCKET_PATH = "/socket"
    }

    private val job = Job()
    override val coroutineContext = Dispatchers.IO + job

    @OptIn(ExperimentalCoroutinesApi::class)
    private val sendChannel = ConflatedBroadcastChannel<ByteArray>()

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    init {
        connect()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun connect() = launch {
        try {
            Log.d(TAG, "Connecting to socket '$SOCKET_HOST:$SOCKET_PORT$SOCKET_PATH'")
            client.ws(
                host = SOCKET_HOST,
                port = SOCKET_PORT,
                path = SOCKET_PATH
            ) {
                Log.d(TAG, "Connection to socket established")
                val dataToSend = sendChannel.openSubscription()
                while (true) {
                    try {
                        dataToSend.poll()?.let {
                            val dataString = it.toString()
                            Log.v(TAG, "Sending: $dataString")
                            //outgoing.send(Frame.Text(dataString))
                            outgoing.send(Frame.Binary(fin = true, it))
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Error while trying to send", e)
                    }
                }
            }
        } catch (e: ConnectException) {
            Log.i(TAG, "connection error", e)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun send(dataToSend: ByteArray) = runBlocking {
        sendChannel.send(dataToSend)
    }

    fun destroy() {
        client.close()
        job.complete()
    }
}
