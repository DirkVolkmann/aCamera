package com.dirk.acamera.signaling

interface SignalingServerListener {
    fun onServerRunning()
    fun onServerFailed()
    fun onConnectionEstablished()
    fun onConnectionAborted()
}
