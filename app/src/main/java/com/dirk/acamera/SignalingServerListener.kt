package com.dirk.acamera

interface SignalingServerListener {
    fun onConnectionEstablished()
    fun onConnectionAborted()
}
