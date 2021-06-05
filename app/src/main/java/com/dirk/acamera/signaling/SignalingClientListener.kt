package com.dirk.acamera.signaling

import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

interface SignalingClientListener {
    fun onConnectionEstablished()
    fun onConnectionFailed()
    fun onConnectionAborted()
    fun onOfferReceived(description: SessionDescription)
    fun onAnswerReceived(description: SessionDescription)
    fun onIceCandidateReceived(iceCandidate: IceCandidate)
}
