package org.webrtc;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CameraCaptureSession.CaptureCallback;
import android.hardware.camera2.CameraCaptureSession.StateCallback;
import android.hardware.camera2.CaptureRequest.Builder;
import android.os.Handler;
import android.util.Range;
import android.view.Surface;
import androidx.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat;
import org.webrtc.CameraEnumerationAndroid.CaptureFormat.FramerateRange;
import org.webrtc.CameraSession.CreateSessionCallback;
import org.webrtc.CameraSession.Events;
import org.webrtc.CameraSession.FailureType;

@TargetApi(21)
class FlashCamera2Session implements CameraSession {
    private static final String TAG = "Camera2Session";
    private static final Histogram camera2StartTimeMsHistogram = Histogram.createCounts("WebRTC.Android.Camera2.StartTimeMs", 1, 10000, 50);
    private static final Histogram camera2StopTimeMsHistogram = Histogram.createCounts("WebRTC.Android.Camera2.StopTimeMs", 1, 10000, 50);
    private static final Histogram camera2ResolutionHistogram;
    private final Handler cameraThreadHandler;
    private final CreateSessionCallback callback;
    private final Events events;
    private final Context applicationContext;
    private final CameraManager cameraManager;
    private final SurfaceTextureHelper surfaceTextureHelper;
    private final String cameraId;
    private final int width;
    private final int height;
    private final int framerate;
    private final boolean useFlash;
    private CameraCharacteristics cameraCharacteristics;
    private int cameraOrientation;
    private boolean isCameraFrontFacing;
    private int fpsUnitFactor;
    private CameraEnumerationAndroid.CaptureFormat captureFormat;
    @Nullable
    private CameraDevice cameraDevice;
    @Nullable
    private Surface surface;
    @Nullable
    private CameraCaptureSession captureSession;
    private FlashCamera2Session.SessionState state;
    private boolean firstFrameReported;
    private final long constructionTimeNs;

    public static void create(CreateSessionCallback callback, Events events, Context applicationContext, CameraManager cameraManager, SurfaceTextureHelper surfaceTextureHelper, String cameraId, int width, int height, int framerate, boolean useFlash) {
        new FlashCamera2Session(callback, events, applicationContext, cameraManager, surfaceTextureHelper, cameraId, width, height, framerate, useFlash);
    }

    private FlashCamera2Session(CreateSessionCallback callback, Events events, Context applicationContext, CameraManager cameraManager, SurfaceTextureHelper surfaceTextureHelper, String cameraId, int width, int height, int framerate, boolean useFlash) {
        this.state = FlashCamera2Session.SessionState.RUNNING;
        Logging.d("Camera2Session", "Create new camera2 session on camera " + cameraId);
        this.constructionTimeNs = System.nanoTime();
        this.cameraThreadHandler = new Handler();
        this.callback = callback;
        this.events = events;
        this.applicationContext = applicationContext;
        this.cameraManager = cameraManager;
        this.surfaceTextureHelper = surfaceTextureHelper;
        this.cameraId = cameraId;
        this.width = width;
        this.height = height;
        this.framerate = framerate;
        this.useFlash = useFlash;
        this.start();
    }

    private void start() {
        this.checkIsOnCameraThread();
        Logging.d("Camera2Session", "start");

        try {
            this.cameraCharacteristics = this.cameraManager.getCameraCharacteristics(this.cameraId);
        } catch (CameraAccessException var2) {
            this.reportError("getCameraCharacteristics(): " + var2.getMessage());
            return;
        }

        this.cameraOrientation = (Integer)this.cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        this.isCameraFrontFacing = (Integer)this.cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == 0;
        this.findCaptureFormat();
        this.openCamera();
    }

    private void findCaptureFormat() {
        this.checkIsOnCameraThread();
        Range<Integer>[] fpsRanges = (Range[])this.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        this.fpsUnitFactor = Camera2Enumerator.getFpsUnitFactor(fpsRanges);
        List<CameraEnumerationAndroid.CaptureFormat.FramerateRange> framerateRanges = Camera2Enumerator.convertFramerates(fpsRanges, this.fpsUnitFactor);
        List<Size> sizes = Camera2Enumerator.getSupportedSizes(this.cameraCharacteristics);
        Logging.d("Camera2Session", "Available preview sizes: " + sizes);
        Logging.d("Camera2Session", "Available fps ranges: " + framerateRanges);
        if (!framerateRanges.isEmpty() && !sizes.isEmpty()) {
            CameraEnumerationAndroid.CaptureFormat.FramerateRange bestFpsRange = CameraEnumerationAndroid.getClosestSupportedFramerateRange(framerateRanges, this.framerate);
            Size bestSize = CameraEnumerationAndroid.getClosestSupportedSize(sizes, this.width, this.height);
            CameraEnumerationAndroid.reportCameraResolution(camera2ResolutionHistogram, bestSize);
            this.captureFormat = new CameraEnumerationAndroid.CaptureFormat(bestSize.width, bestSize.height, bestFpsRange);
            Logging.d("Camera2Session", "Using capture format: " + this.captureFormat);
        } else {
            this.reportError("No supported capture formats.");
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        this.checkIsOnCameraThread();
        Logging.d("Camera2Session", "Opening camera " + this.cameraId);
        this.events.onCameraOpening();

        try {
            this.cameraManager.openCamera(this.cameraId, new FlashCamera2Session.CameraStateCallback(), this.cameraThreadHandler);
        } catch (CameraAccessException var2) {
            this.reportError("Failed to open camera: " + var2);
        }
    }

    public void stop() {
        Logging.d("Camera2Session", "Stop camera2 session on camera " + this.cameraId);
        this.checkIsOnCameraThread();
        if (this.state != FlashCamera2Session.SessionState.STOPPED) {
            long stopStartTime = System.nanoTime();
            this.state = FlashCamera2Session.SessionState.STOPPED;
            this.stopInternal();
            int stopTimeMs = (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopStartTime);
            camera2StopTimeMsHistogram.addSample(stopTimeMs);
        }

    }

    private void stopInternal() {
        Logging.d("Camera2Session", "Stop internal");
        this.checkIsOnCameraThread();
        this.surfaceTextureHelper.stopListening();
        if (this.captureSession != null) {
            this.captureSession.close();
            this.captureSession = null;
        }

        if (this.surface != null) {
            this.surface.release();
            this.surface = null;
        }

        if (this.cameraDevice != null) {
            this.cameraDevice.close();
            this.cameraDevice = null;
        }

        Logging.d("Camera2Session", "Stop done");
    }

    private void reportError(String error) {
        this.checkIsOnCameraThread();
        Logging.e("Camera2Session", "Error: " + error);
        boolean startFailure = this.captureSession == null && this.state != FlashCamera2Session.SessionState.STOPPED;
        this.state = FlashCamera2Session.SessionState.STOPPED;
        this.stopInternal();
        if (startFailure) {
            this.callback.onFailure(FailureType.ERROR, error);
        } else {
            this.events.onCameraError(this, error);
        }

    }

    private int getFrameOrientation() {
        int rotation = CameraSession.getDeviceOrientation(this.applicationContext);
        if (!this.isCameraFrontFacing) {
            rotation = 360 - rotation;
        }

        return (this.cameraOrientation + rotation) % 360;
    }

    private void checkIsOnCameraThread() {
        if (Thread.currentThread() != this.cameraThreadHandler.getLooper().getThread()) {
            throw new IllegalStateException("Wrong thread");
        }
    }

    static {
        camera2ResolutionHistogram = Histogram.createEnumeration("WebRTC.Android.Camera2.Resolution", CameraEnumerationAndroid.COMMON_RESOLUTIONS.size());
    }

    private static class CameraCaptureCallback extends CameraCaptureSession.CaptureCallback {
        private CameraCaptureCallback() {
        }

        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            Logging.d("Camera2Session", "Capture failed: " + failure);
        }
    }

    private class CaptureSessionCallback extends CameraCaptureSession.StateCallback {
        private CaptureSessionCallback() {
        }

        public void onConfigureFailed(CameraCaptureSession session) {
            FlashCamera2Session.this.checkIsOnCameraThread();
            session.close();
            FlashCamera2Session.this.reportError("Failed to configure capture session.");
        }

        public void onConfigured(CameraCaptureSession session) {
            FlashCamera2Session.this.checkIsOnCameraThread();
            Logging.d("Camera2Session", "Camera capture session configured.");
            FlashCamera2Session.this.captureSession = session;

            try {
                CaptureRequest.Builder captureRequestBuilder = FlashCamera2Session.this.cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range(FlashCamera2Session.this.captureFormat.framerate.min / FlashCamera2Session.this.fpsUnitFactor, FlashCamera2Session.this.captureFormat.framerate.max / FlashCamera2Session.this.fpsUnitFactor));
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, 1);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                this.chooseStabilizationMode(captureRequestBuilder);
                this.chooseFocusMode(captureRequestBuilder);
                this.chooseFlashMode(captureRequestBuilder);
                captureRequestBuilder.addTarget(FlashCamera2Session.this.surface);
                session.setRepeatingRequest(captureRequestBuilder.build(), new FlashCamera2Session.CameraCaptureCallback(), FlashCamera2Session.this.cameraThreadHandler);
            } catch (CameraAccessException var3) {
                FlashCamera2Session.this.reportError("Failed to start capture request. " + var3);
                return;
            }

            FlashCamera2Session.this.surfaceTextureHelper.startListening((frame) -> {
                FlashCamera2Session.this.checkIsOnCameraThread();
                if (FlashCamera2Session.this.state != FlashCamera2Session.SessionState.RUNNING) {
                    Logging.d("Camera2Session", "Texture frame captured but camera is no longer running.");
                } else {
                    if (!FlashCamera2Session.this.firstFrameReported) {
                        FlashCamera2Session.this.firstFrameReported = true;
                        int startTimeMs = (int)TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - FlashCamera2Session.this.constructionTimeNs);
                        FlashCamera2Session.camera2StartTimeMsHistogram.addSample(startTimeMs);
                    }

                    VideoFrame modifiedFrame = new VideoFrame(CameraSession.createTextureBufferWithModifiedTransformMatrix((TextureBufferImpl)frame.getBuffer(), FlashCamera2Session.this.isCameraFrontFacing, -FlashCamera2Session.this.cameraOrientation), FlashCamera2Session.this.getFrameOrientation(), frame.getTimestampNs());
                    FlashCamera2Session.this.events.onFrameCaptured(FlashCamera2Session.this, modifiedFrame);
                    modifiedFrame.release();
                }
            });
            Logging.d("Camera2Session", "Camera device successfully started.");
            FlashCamera2Session.this.callback.onDone(FlashCamera2Session.this);
        }

        private void chooseStabilizationMode(CaptureRequest.Builder captureRequestBuilder) {
            int[] availableOpticalStabilization = (int[])FlashCamera2Session.this.cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            int[] availableVideoStabilization;
            int var5;
            int mode;
            if (availableOpticalStabilization != null) {
                availableVideoStabilization = availableOpticalStabilization;
                int var4 = availableOpticalStabilization.length;

                for(var5 = 0; var5 < var4; ++var5) {
                    mode = availableVideoStabilization[var5];
                    if (mode == 1) {
                        captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 1);
                        captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 0);
                        Logging.d("Camera2Session", "Using optical stabilization.");
                        return;
                    }
                }
            }

            availableVideoStabilization = (int[])FlashCamera2Session.this.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
            int[] var8 = availableVideoStabilization;
            var5 = availableVideoStabilization.length;

            for(mode = 0; mode < var5; ++mode) {
                int modex = var8[mode];
                if (modex == 1) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, 1);
                    captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 0);
                    Logging.d("Camera2Session", "Using video stabilization.");
                    return;
                }
            }

            Logging.d("Camera2Session", "Stabilization not available.");
        }

        private void chooseFocusMode(CaptureRequest.Builder captureRequestBuilder) {
            int[] availableFocusModes = (int[])FlashCamera2Session.this.cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            int[] var3 = availableFocusModes;
            int var4 = availableFocusModes.length;

            for(int var5 = 0; var5 < var4; ++var5) {
                int mode = var3[var5];
                if (mode == 3) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, 3);
                    Logging.d("Camera2Session", "Using continuous video auto-focus.");
                    return;
                }
            }

            Logging.d("Camera2Session", "Auto-focus is not available.");
        }

        private void chooseFlashMode(CaptureRequest.Builder captureRequestBuilder) {
            if (useFlash && FlashCamera2Session.this.cameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
            }
        }
    }

    private class CameraStateCallback extends android.hardware.camera2.CameraDevice.StateCallback {
        private CameraStateCallback() {
        }

        private String getErrorDescription(int errorCode) {
            switch(errorCode) {
                case 1:
                    return "Camera device is in use already.";
                case 2:
                    return "Camera device could not be opened because there are too many other open camera devices.";
                case 3:
                    return "Camera device could not be opened due to a device policy.";
                case 4:
                    return "Camera device has encountered a fatal error.";
                case 5:
                    return "Camera service has encountered a fatal error.";
                default:
                    return "Unknown camera error: " + errorCode;
            }
        }

        public void onDisconnected(CameraDevice camera) {
            FlashCamera2Session.this.checkIsOnCameraThread();
            boolean startFailure = FlashCamera2Session.this.captureSession == null && FlashCamera2Session.this.state != FlashCamera2Session.SessionState.STOPPED;
            FlashCamera2Session.this.state = FlashCamera2Session.SessionState.STOPPED;
            FlashCamera2Session.this.stopInternal();
            if (startFailure) {
                FlashCamera2Session.this.callback.onFailure(FailureType.DISCONNECTED, "Camera disconnected / evicted.");
            } else {
                FlashCamera2Session.this.events.onCameraDisconnected(FlashCamera2Session.this);
            }

        }

        public void onError(CameraDevice camera, int errorCode) {
            FlashCamera2Session.this.checkIsOnCameraThread();
            FlashCamera2Session.this.reportError(this.getErrorDescription(errorCode));
        }

        public void onOpened(CameraDevice camera) {
            FlashCamera2Session.this.checkIsOnCameraThread();
            Logging.d("Camera2Session", "Camera opened.");
            FlashCamera2Session.this.cameraDevice = camera;
            FlashCamera2Session.this.surfaceTextureHelper.setTextureSize(FlashCamera2Session.this.captureFormat.width, FlashCamera2Session.this.captureFormat.height);
            FlashCamera2Session.this.surface = new Surface(FlashCamera2Session.this.surfaceTextureHelper.getSurfaceTexture());

            try {
                camera.createCaptureSession(Arrays.asList(FlashCamera2Session.this.surface), FlashCamera2Session.this.new CaptureSessionCallback(), FlashCamera2Session.this.cameraThreadHandler);
            } catch (CameraAccessException var3) {
                FlashCamera2Session.this.reportError("Failed to create capture session. " + var3);
            }
        }

        public void onClosed(CameraDevice camera) {
            FlashCamera2Session.this.checkIsOnCameraThread();
            Logging.d("Camera2Session", "Camera device closed.");
            FlashCamera2Session.this.events.onCameraClosed(FlashCamera2Session.this);
        }
    }

    private static enum SessionState {
        RUNNING,
        STOPPED;

        private SessionState() {
        }
    }
}
