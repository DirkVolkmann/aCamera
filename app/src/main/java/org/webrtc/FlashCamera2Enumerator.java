package org.webrtc;

import android.content.Context;
import android.hardware.camera2.CameraManager;

public class FlashCamera2Enumerator extends Camera2Enumerator {
    private final CameraManager cameraManager;

    public FlashCamera2Enumerator(Context context, CameraManager cameraManager) {
        super(context);
        this.cameraManager = cameraManager;
    }

    public FlashCamera2Capturer createCapturer(String deviceName, FlashCameraVideoCapturer.CameraEventsHandler eventsHandler) {
        return new FlashCamera2Capturer(this.context, deviceName, eventsHandler, cameraManager);
    }
}
