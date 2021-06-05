package com.dirk.acamera.rtc

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {
    }

    override fun onCreateFailure(p0: String?) {
    }

    override fun onSetSuccess() {
    }

    override fun onSetFailure(p0: String?) {
    }
}
