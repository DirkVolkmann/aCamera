package org.webrtc;

import android.content.Context;

public interface FlashVideoCapturer {
    void initialize(SurfaceTextureHelper var1, Context var2, CapturerObserver var3);

    void startCapture(int var1, int var2, int var3, boolean var4);

    void stopCapture() throws InterruptedException;

    void changeCaptureFormat(int var1, int var2, int var3, boolean var4);

    void dispose();

    boolean isScreencast();
}
