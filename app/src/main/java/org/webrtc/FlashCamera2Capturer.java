package org.webrtc;

import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.webrtc.CameraSession.CreateSessionCallback;
import org.webrtc.CameraSession.Events;

import java.util.Arrays;
import java.util.List;

@TargetApi(21)
public class FlashCamera2Capturer implements FlashCameraVideoCapturer {
    private final CameraManager cameraManager;
    private boolean useFlash;

    public FlashCamera2Capturer(Context context, String cameraName, CameraEventsHandler eventsHandler, CameraManager cameraManager) {
        this.cameraEnumerator = new FlashCamera2Enumerator(context, cameraManager);
        this.cameraManager = cameraManager;

        this.switchState = CameraCapturer.SwitchState.IDLE;
        if (eventsHandler == null) {
            eventsHandler = new CameraEventsHandler() {
                public void onCameraError(String errorDescription) {
                }

                public void onCameraDisconnected() {
                }

                public void onCameraFreezed(String errorDescription) {
                }

                public void onCameraOpening(String cameraName) {
                }

                public void onFirstFrameAvailable() {
                }

                public void onCameraClosed() {
                }
            };
        }

        this.eventsHandler = eventsHandler;
        this.cameraName = cameraName;
        List<String> deviceNames = Arrays.asList(cameraEnumerator.getDeviceNames());
        this.uiThreadHandler = new Handler(Looper.getMainLooper());
        if (deviceNames.isEmpty()) {
            throw new RuntimeException("No cameras attached.");
        } else if (!deviceNames.contains(this.cameraName)) {
            throw new IllegalArgumentException("Camera name " + this.cameraName + " does not match any known camera device.");
        }
    }

    protected void createCameraSession(CreateSessionCallback createSessionCallback, Events events, Context applicationContext, SurfaceTextureHelper surfaceTextureHelper, String cameraName, int width, int height, int framerate) {
        FlashCamera2Session.create(createSessionCallback, events, applicationContext, this.cameraManager, surfaceTextureHelper, cameraName, width, height, framerate, useFlash);
    }

    private static final String TAG = "CameraCapturer";
    private static final int MAX_OPEN_CAMERA_ATTEMPTS = 3;
    private static final int OPEN_CAMERA_DELAY_MS = 500;
    private static final int OPEN_CAMERA_TIMEOUT = 10000;
    private final CameraEnumerator cameraEnumerator;
    private final CameraEventsHandler eventsHandler;
    private final Handler uiThreadHandler;

    @Nullable
    private final CreateSessionCallback createSessionCallback = new CreateSessionCallback() {
        public void onDone(CameraSession session) {
            FlashCamera2Capturer.this.checkIsOnCameraThread();
            Logging.d("CameraCapturer", "Create session done. Switch state: " + FlashCamera2Capturer.this.switchState);
            FlashCamera2Capturer.this.uiThreadHandler.removeCallbacks(FlashCamera2Capturer.this.openCameraTimeoutRunnable);
            synchronized(FlashCamera2Capturer.this.stateLock) {
                FlashCamera2Capturer.this.capturerObserver.onCapturerStarted(true);
                FlashCamera2Capturer.this.sessionOpening = false;
                FlashCamera2Capturer.this.currentSession = session;
                FlashCamera2Capturer.this.cameraStatistics = new CameraStatistics(FlashCamera2Capturer.this.surfaceHelper, FlashCamera2Capturer.this.eventsHandler);
                FlashCamera2Capturer.this.firstFrameObserved = false;
                FlashCamera2Capturer.this.stateLock.notifyAll();
                if (FlashCamera2Capturer.this.switchState == CameraCapturer.SwitchState.IN_PROGRESS) {
                    FlashCamera2Capturer.this.switchState = CameraCapturer.SwitchState.IDLE;
                    if (FlashCamera2Capturer.this.switchEventsHandler != null) {
                        FlashCamera2Capturer.this.switchEventsHandler.onCameraSwitchDone(FlashCamera2Capturer.this.cameraEnumerator.isFrontFacing(FlashCamera2Capturer.this.cameraName));
                        FlashCamera2Capturer.this.switchEventsHandler = null;
                    }
                } else if (FlashCamera2Capturer.this.switchState == CameraCapturer.SwitchState.PENDING) {
                    String selectedCameraName = FlashCamera2Capturer.this.pendingCameraName;
                    FlashCamera2Capturer.this.pendingCameraName = null;
                    FlashCamera2Capturer.this.switchState = CameraCapturer.SwitchState.IDLE;
                    FlashCamera2Capturer.this.switchCameraInternal(FlashCamera2Capturer.this.switchEventsHandler, selectedCameraName);
                }

            }
        }

        public void onFailure(CameraSession.FailureType failureType, String error) {
            FlashCamera2Capturer.this.checkIsOnCameraThread();
            FlashCamera2Capturer.this.uiThreadHandler.removeCallbacks(FlashCamera2Capturer.this.openCameraTimeoutRunnable);
            synchronized(FlashCamera2Capturer.this.stateLock) {
                FlashCamera2Capturer.this.capturerObserver.onCapturerStarted(false);
                FlashCamera2Capturer.this.openAttemptsRemaining--;
                if (FlashCamera2Capturer.this.openAttemptsRemaining <= 0) {
                    Logging.w("CameraCapturer", "Opening camera failed, passing: " + error);
                    FlashCamera2Capturer.this.sessionOpening = false;
                    FlashCamera2Capturer.this.stateLock.notifyAll();
                    if (FlashCamera2Capturer.this.switchState != CameraCapturer.SwitchState.IDLE) {
                        if (FlashCamera2Capturer.this.switchEventsHandler != null) {
                            FlashCamera2Capturer.this.switchEventsHandler.onCameraSwitchError(error);
                            FlashCamera2Capturer.this.switchEventsHandler = null;
                        }

                        FlashCamera2Capturer.this.switchState = CameraCapturer.SwitchState.IDLE;
                    }

                    if (failureType == CameraSession.FailureType.DISCONNECTED) {
                        FlashCamera2Capturer.this.eventsHandler.onCameraDisconnected();
                    } else {
                        FlashCamera2Capturer.this.eventsHandler.onCameraError(error);
                    }
                } else {
                    Logging.w("CameraCapturer", "Opening camera failed, retry: " + error);
                    FlashCamera2Capturer.this.createSessionInternal(500);
                }

            }
        }
    };

    @Nullable
    private final Events cameraSessionEventsHandler = new Events() {
        public void onCameraOpening() {
            FlashCamera2Capturer.this.checkIsOnCameraThread();
            synchronized(FlashCamera2Capturer.this.stateLock) {
                if (FlashCamera2Capturer.this.currentSession != null) {
                    Logging.w("CameraCapturer", "onCameraOpening while session was open.");
                } else {
                    FlashCamera2Capturer.this.eventsHandler.onCameraOpening(FlashCamera2Capturer.this.cameraName);
                }
            }
        }

        public void onCameraError(CameraSession session, String error) {
            FlashCamera2Capturer.this.checkIsOnCameraThread();
            synchronized(FlashCamera2Capturer.this.stateLock) {
                if (session != FlashCamera2Capturer.this.currentSession) {
                    Logging.w("CameraCapturer", "onCameraError from another session: " + error);
                } else {
                    FlashCamera2Capturer.this.eventsHandler.onCameraError(error);
                    FlashCamera2Capturer.this.stopCapture();
                }
            }
        }

        public void onCameraDisconnected(CameraSession session) {
            FlashCamera2Capturer.this.checkIsOnCameraThread();
            synchronized(FlashCamera2Capturer.this.stateLock) {
                if (session != FlashCamera2Capturer.this.currentSession) {
                    Logging.w("CameraCapturer", "onCameraDisconnected from another session.");
                } else {
                    FlashCamera2Capturer.this.eventsHandler.onCameraDisconnected();
                    FlashCamera2Capturer.this.stopCapture();
                }
            }
        }

        public void onCameraClosed(CameraSession session) {
            FlashCamera2Capturer.this.checkIsOnCameraThread();
            synchronized(FlashCamera2Capturer.this.stateLock) {
                if (session != FlashCamera2Capturer.this.currentSession && FlashCamera2Capturer.this.currentSession != null) {
                    Logging.d("CameraCapturer", "onCameraClosed from another session.");
                } else {
                    FlashCamera2Capturer.this.eventsHandler.onCameraClosed();
                }
            }
        }

        public void onFrameCaptured(CameraSession session, VideoFrame frame) {
            FlashCamera2Capturer.this.checkIsOnCameraThread();
            synchronized(FlashCamera2Capturer.this.stateLock) {
                if (session != FlashCamera2Capturer.this.currentSession) {
                    Logging.w("CameraCapturer", "onFrameCaptured from another session.");
                } else {
                    if (!FlashCamera2Capturer.this.firstFrameObserved) {
                        FlashCamera2Capturer.this.eventsHandler.onFirstFrameAvailable();
                        FlashCamera2Capturer.this.firstFrameObserved = true;
                    }

                    FlashCamera2Capturer.this.cameraStatistics.addFrame();
                    FlashCamera2Capturer.this.capturerObserver.onFrameCaptured(frame);
                }
            }
        }
    };

    private final Runnable openCameraTimeoutRunnable = new Runnable() {
        public void run() {
            FlashCamera2Capturer.this.eventsHandler.onCameraError("Camera failed to start within timeout.");
        }
    };

    private Handler cameraThreadHandler;
    private Context applicationContext;
    private CapturerObserver capturerObserver;
    private SurfaceTextureHelper surfaceHelper;
    private final Object stateLock = new Object();
    private boolean sessionOpening;
    @Nullable
    private CameraSession currentSession;
    private String cameraName;
    private String pendingCameraName;
    private int width;
    private int height;
    private int framerate;
    private int openAttemptsRemaining;
    private CameraCapturer.SwitchState switchState;
    @Nullable
    private CameraSwitchHandler switchEventsHandler;
    @Nullable
    private CameraStatistics cameraStatistics;
    private boolean firstFrameObserved;

    public void initialize(SurfaceTextureHelper surfaceTextureHelper, Context applicationContext, CapturerObserver capturerObserver) {
        this.applicationContext = applicationContext;
        this.capturerObserver = capturerObserver;
        this.surfaceHelper = surfaceTextureHelper;
        this.cameraThreadHandler = surfaceTextureHelper.getHandler();
    }

    public void startCapture(int width, int height, int framerate) {
        Logging.e("CameraCapturer", "Wrong implementation of startCapture was used");
    }

    public void startCapture(int width, int height, int framerate, boolean useFlash) {
        Logging.d("CameraCapturer", "startCapture: " + width + "x" + height + "@" + framerate);
        if (this.applicationContext == null) {
            throw new RuntimeException("CameraCapturer must be initialized before calling startCapture.");
        } else {
            synchronized(this.stateLock) {
                if (!this.sessionOpening && this.currentSession == null) {
                    this.width = width;
                    this.height = height;
                    this.framerate = framerate;
                    this.useFlash = useFlash;
                    this.sessionOpening = true;
                    this.openAttemptsRemaining = 3;
                    this.createSessionInternal(0);
                } else {
                    Logging.w("CameraCapturer", "Session already open");
                }
            }
        }
    }

    private void createSessionInternal(int delayMs) {
        this.uiThreadHandler.postDelayed(this.openCameraTimeoutRunnable, (long)(delayMs + 10000));
        this.cameraThreadHandler.postDelayed(new Runnable() {
            public void run() {
                FlashCamera2Capturer.this.createCameraSession(FlashCamera2Capturer.this.createSessionCallback, FlashCamera2Capturer.this.cameraSessionEventsHandler, FlashCamera2Capturer.this.applicationContext, FlashCamera2Capturer.this.surfaceHelper, FlashCamera2Capturer.this.cameraName, FlashCamera2Capturer.this.width, FlashCamera2Capturer.this.height, FlashCamera2Capturer.this.framerate);
            }
        }, (long)delayMs);
    }

    public void stopCapture() {
        Logging.d("CameraCapturer", "Stop capture");
        synchronized(this.stateLock) {
            while(this.sessionOpening) {
                Logging.d("CameraCapturer", "Stop capture: Waiting for session to open");

                try {
                    this.stateLock.wait();
                } catch (InterruptedException var4) {
                    Logging.w("CameraCapturer", "Stop capture interrupted while waiting for the session to open.");
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (this.currentSession != null) {
                Logging.d("CameraCapturer", "Stop capture: Nulling session");
                this.cameraStatistics.release();
                this.cameraStatistics = null;
                final CameraSession oldSession = this.currentSession;
                this.cameraThreadHandler.post(new Runnable() {
                    public void run() {
                        oldSession.stop();
                    }
                });
                this.currentSession = null;
                this.capturerObserver.onCapturerStopped();
            } else {
                Logging.d("CameraCapturer", "Stop capture: No session open");
            }
        }

        Logging.d("CameraCapturer", "Stop capture done");
    }

    public void changeCaptureFormat(int width, int height, int framerate) {
        Logging.e("CameraCapturer", "Wrong implementation of changeCaptureFormat was used");
    }

    public void changeCaptureFormat(int width, int height, int framerate, boolean useFlash) {
        Logging.d("CameraCapturer", "changeCaptureFormat: " + width + "x" + height + "@" + framerate);
        synchronized(this.stateLock) {
            this.stopCapture();
            this.startCapture(width, height, framerate, useFlash);
        }
    }

    public void dispose() {
        Logging.d("CameraCapturer", "dispose");
        this.stopCapture();
    }

    public void switchCamera(final CameraSwitchHandler switchEventsHandler) {
        Logging.d("CameraCapturer", "switchCamera");
        this.cameraThreadHandler.post(new Runnable() {
            public void run() {
                List<String> deviceNames = Arrays.asList(FlashCamera2Capturer.this.cameraEnumerator.getDeviceNames());
                if (deviceNames.size() < 2) {
                    FlashCamera2Capturer.this.reportCameraSwitchError("No camera to switch to.", switchEventsHandler);
                } else {
                    int cameraNameIndex = deviceNames.indexOf(FlashCamera2Capturer.this.cameraName);
                    String cameraName = (String)deviceNames.get((cameraNameIndex + 1) % deviceNames.size());
                    FlashCamera2Capturer.this.switchCameraInternal(switchEventsHandler, cameraName);
                }
            }
        });
    }

    public void switchCamera(final CameraSwitchHandler switchEventsHandler, final String cameraName) {
        Logging.d("CameraCapturer", "switchCamera");
        this.cameraThreadHandler.post(new Runnable() {
            public void run() {
                FlashCamera2Capturer.this.switchCameraInternal(switchEventsHandler, cameraName);
            }
        });
    }

    public boolean isScreencast() {
        return false;
    }

    public void printStackTrace() {
        Thread cameraThread = null;
        if (this.cameraThreadHandler != null) {
            cameraThread = this.cameraThreadHandler.getLooper().getThread();
        }

        if (cameraThread != null) {
            StackTraceElement[] cameraStackTrace = cameraThread.getStackTrace();
            if (cameraStackTrace.length > 0) {
                Logging.d("CameraCapturer", "CameraCapturer stack trace:");
                StackTraceElement[] var3 = cameraStackTrace;
                int var4 = cameraStackTrace.length;

                for(int var5 = 0; var5 < var4; ++var5) {
                    StackTraceElement traceElem = var3[var5];
                    Logging.d("CameraCapturer", traceElem.toString());
                }
            }
        }

    }

    private void reportCameraSwitchError(String error, @Nullable CameraSwitchHandler switchEventsHandler) {
        Logging.e("CameraCapturer", error);
        if (switchEventsHandler != null) {
            switchEventsHandler.onCameraSwitchError(error);
        }

    }

    private void switchCameraInternal(@Nullable CameraSwitchHandler switchEventsHandler, String selectedCameraName) {
        Logging.d("CameraCapturer", "switchCamera internal");
        List<String> deviceNames = Arrays.asList(this.cameraEnumerator.getDeviceNames());
        if (!deviceNames.contains(selectedCameraName)) {
            this.reportCameraSwitchError("Attempted to switch to unknown camera device " + selectedCameraName, switchEventsHandler);
        } else {
            synchronized(this.stateLock) {
                if (this.switchState != CameraCapturer.SwitchState.IDLE) {
                    this.reportCameraSwitchError("Camera switch already in progress.", switchEventsHandler);
                    return;
                }

                if (!this.sessionOpening && this.currentSession == null) {
                    this.reportCameraSwitchError("switchCamera: camera is not running.", switchEventsHandler);
                    return;
                }

                this.switchEventsHandler = switchEventsHandler;
                if (this.sessionOpening) {
                    this.switchState = CameraCapturer.SwitchState.PENDING;
                    this.pendingCameraName = selectedCameraName;
                    return;
                }

                this.switchState = CameraCapturer.SwitchState.IN_PROGRESS;
                Logging.d("CameraCapturer", "switchCamera: Stopping session");
                this.cameraStatistics.release();
                this.cameraStatistics = null;
                final CameraSession oldSession = this.currentSession;
                this.cameraThreadHandler.post(new Runnable() {
                    public void run() {
                        oldSession.stop();
                    }
                });
                this.currentSession = null;
                this.cameraName = selectedCameraName;
                this.sessionOpening = true;
                this.openAttemptsRemaining = 1;
                this.createSessionInternal(0);
            }

            Logging.d("CameraCapturer", "switchCamera done");
        }
    }

    private void checkIsOnCameraThread() {
        if (Thread.currentThread() != this.cameraThreadHandler.getLooper().getThread()) {
            Logging.e("CameraCapturer", "Check is on camera thread failed.");
            throw new RuntimeException("Not on camera thread.");
        }
    }

    protected String getCameraName() {
        synchronized(this.stateLock) {
            return this.cameraName;
        }
    }

    static enum SwitchState {
        IDLE,
        PENDING,
        IN_PROGRESS;

        private SwitchState() {
        }
    }
}
