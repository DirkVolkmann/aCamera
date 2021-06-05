package com.dirk.acamera.signaling

interface SignalingServerListener {
    fun onConnectionEstablished()
    fun onConnectionAborted()
}
