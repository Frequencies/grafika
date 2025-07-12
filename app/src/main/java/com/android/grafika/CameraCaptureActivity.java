/*
 * Copyright 2013 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.EGL14;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.RequiresApi;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.android.grafika.gles.FullFrameRect;
import com.android.grafika.gles.Texture2dProgram;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Shows the camera preview on screen while simultaneously recording it to a .mp4 file.
 * <p>
 * Every time we receive a frame from the camera, we need to:
 * <ul>
 * <li>Render the frame to the SurfaceView, on GLSurfaceView's renderer thread.
 * <li>Render the frame to the mediacodec's input surface, on the encoder thread, if
 *     recording is enabled.
 * </ul>
 * <p>
 * At any given time there are four things in motion:
 * <ol>
 * <li>The UI thread, embodied by this Activity.  We must respect -- or work around -- the
 *     app lifecycle changes.  In particular, we need to release and reacquire the Camera
 *     so that, if the user switches away from us, we're not preventing another app from
 *     using the camera.
 * <li>The Camera, which will busily generate preview frames once we hand it a
 *     SurfaceTexture.  We'll get notifications on the main UI thread unless we define a
 *     Looper on the thread where the SurfaceTexture is created (the GLSurfaceView renderer
 *     thread).
 * <li>The video encoder thread, embodied by TextureMovieEncoder.  This needs to share
 *     the Camera preview external texture with the GLSurfaceView renderer, which means the
 *     EGLContext in this thread must be created with a reference to the renderer thread's
 *     context in hand.
 * <li>The GLSurfaceView renderer thread, embodied by CameraSurfaceRenderer.  The thread
 *     is created for us by GLSurfaceView.  We don't get callbacks for pause/resume or
 *     thread startup/shutdown, though we could generate messages from the Activity for most
 *     of these things.  The EGLContext created on this thread must be shared with the
 *     video encoder, and must be used to create a SurfaceTexture that is used by the
 *     Camera.  As the creator of the SurfaceTexture, it must also be the one to call
 *     updateTexImage().  The renderer thread is thus at the center of a multi-thread nexus,
 *     which is a bit awkward since it's the thread we have the least control over.
 * </ol>
 * <p>
 * GLSurfaceView is fairly painful here.  Ideally we'd create the video encoder, create
 * an EGLContext for it, and pass that into GLSurfaceView to share.  The API doesn't allow
 * this, so we have to do it the other way around.  When GLSurfaceView gets torn down
 * (say, because we rotated the device), the EGLContext gets tossed, which means that when
 * it comes back we have to re-create the EGLContext used by the video encoder.  (And, no,
 * the "preserve EGLContext on pause" feature doesn't help.)
 * <p>
 * We could simplify this quite a bit by using TextureView instead of GLSurfaceView, but that
 * comes with a performance hit.  We could also have the renderer thread drive the video
 * encoder directly, allowing them to work from a single EGLContext, but it's useful to
 * decouple the operations, and it's generally unwise to perform disk I/O on the thread that
 * renders your UI.
 * <p>
 * We want to access Camera from the UI thread (setup, teardown) and the renderer thread
 * (configure SurfaceTexture, start preview), but the API says you can only access the object
 * from a single thread.  So we need to pick one thread to own it, and the other thread has to
 * access it remotely.  Some things are simpler if we let the renderer thread manage it,
 * but we'd really like to be sure that Camera is released before we leave onPause(), which
 * means we need to make a synchronous call from the UI thread into the renderer thread, which
 * we don't really have full control over.  It's less scary to have the UI thread own Camera
 * and have the renderer call back into the UI thread through the standard Handler mechanism.
 * <p>
 * (The <a href="http://developer.android.com/training/camera/cameradirect.html#TaskOpenCamera">
 * camera docs</a> recommend accessing the camera from a non-UI thread to avoid bogging the
 * UI thread down.  Since the GLSurfaceView-managed renderer thread isn't a great choice,
 * we might want to create a dedicated camera thread.  Not doing that here.)
 * <p>
 * With three threads working simultaneously (plus Camera causing periodic events as frames
 * arrive) we have to be very careful when communicating state changes.  In general we want
 * to send a message to the thread, rather than directly accessing state in the object.
 * <p>
 * &nbsp;
 * <p>
 * To exercise the API a bit, the video encoder is required to survive Activity restarts.  In the
 * current implementation it stops recording but doesn't stop time from advancing, so you'll
 * see a pause in the video.  (We could adjust the timer to make it seamless, or output a
 * "paused" message and hold on that in the recording, or leave the Camera running so it
 * continues to generate preview frames while the Activity is paused.)  The video encoder object
 * is managed as a static property of the Activity.
 */
public class CameraCaptureActivity extends Activity
        implements SurfaceTexture.OnFrameAvailableListener, OnItemSelectedListener {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;

    // Camera filters; must match up with cameraFilterNames in strings.xml
    static final int FILTER_NONE = 0;
    static final int FILTER_BLACK_WHITE = 1;
    static final int FILTER_BLUR = 2;
    static final int FILTER_SHARPEN = 3;
    static final int FILTER_EDGE_DETECT = 4;
    static final int FILTER_EMBOSS = 5;

    private GLSurfaceView mGLView;
    private CameraSurfaceRenderer mRenderer;
    private Camera mCamera;
    private CameraHandler mCameraHandler;
    private boolean mRecordingEnabled;      // controls button state

    private int mCameraPreviewWidth, mCameraPreviewHeight;

    private static final TextureMovieEncoder sVideoEncoder = new TextureMovieEncoder();

    private boolean isFrontCamera = true; // Default to front camera

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_capture);

        File outputFile = new File(getFilesDir(), "camera-test.mp4");
        TextView fileText = findViewById(R.id.cameraOutputFile_text);
        fileText.setText(outputFile.toString());

        Button switchCameraButton = findViewById(R.id.switch_camera_button);
        switchCameraButton.setOnClickListener(v -> switchCamera());

        mCameraHandler = new CameraHandler(this);

        mRecordingEnabled = sVideoEncoder.isRecording();

        mGLView = findViewById(R.id.cameraPreview_surfaceView);
        mGLView.setBackgroundColor(0x00000000); // Transparent
        mGLView.setZOrderOnTop(true);
        mGLView.getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        mGLView.setEGLContextClientVersion(2);     // select GLES 2.0
        mRenderer = new CameraSurfaceRenderer(mCameraHandler, sVideoEncoder, outputFile);
        mGLView.setRenderer(mRenderer);
        mGLView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        Log.d(TAG, "onCreate complete: " + this);
    }

    private void switchCamera() {
        // Release the current camera
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }

        // Toggle between front and back camera
        isFrontCamera = !isFrontCamera;

        releaseCamera();
        pauseGlView();
        openCamera(1280, 720);
        resumeGlView();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume -- acquiring camera");
        super.onResume();
        updateControls();

        if (PermissionHelper.hasCameraPermission(this)) {
            if (mCamera == null) {
                openCamera(1280, 720);      // updates mCameraPreviewWidth/Height
            }

        } else {
            PermissionHelper.requestCameraPermission(this, false);
        }
        resumeGlView();
        Log.d(TAG, "onResume complete: " + this);
    }

    private void resumeGlView() {
        mGLView.onResume();
        mGLView.queueEvent(() -> mRenderer.setCameraPreviewSize(mCameraPreviewWidth, mCameraPreviewHeight));
    }

    private void pauseGlView() {
        mGLView.queueEvent(() -> {
            // Tell the renderer that it's about to be paused so it can clean up.
            mRenderer.notifyPausing();
        });
        mGLView.onPause();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause -- releasing camera");
        super.onPause();
        releaseCamera();
        pauseGlView();
        Log.d(TAG, "onPause complete");
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        mCameraHandler.invalidateHandler();     // paranoia
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!PermissionHelper.hasCameraPermission(this)) {
            Toast.makeText(this,
                    "Camera permission is needed to run this application", Toast.LENGTH_LONG).show();
            PermissionHelper.launchPermissionSettings(this);
            finish();
        } else {
            openCamera(1280, 720);      // updates mCameraPreviewWidth/Height

        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        Spinner spinner = (Spinner) parent;
        final int filterNum = spinner.getSelectedItemPosition();

        Log.d(TAG, "onItemSelected: " + filterNum);
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeFilterMode(filterNum);
            }
        });
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
    }

    private void openCamera(int desiredWidth, int desiredHeight) {
        if (mCamera != null) {
            throw new RuntimeException("camera already initialized");
        }

        Camera.CameraInfo info = new Camera.CameraInfo();

        int numCameras = Camera.getNumberOfCameras();
        for (int i = 0; i < numCameras; i++) {
            Camera.getCameraInfo(i, info);
            if (isFrontCamera) {
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    mCamera = Camera.open(i);
                    break;
                }
            } else {
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    mCamera = Camera.open(i);
                    break;
                }
            }
        }
        if (mCamera == null) {
            Log.d(TAG, "No front-facing camera found; opening default");
            mCamera = Camera.open();    // opens first back-facing camera
        }
        if (mCamera == null) {
            throw new RuntimeException("Unable to open camera");
        }

        Camera.Parameters parms = mCamera.getParameters();

        CameraUtils.choosePreviewSize(parms, desiredWidth, desiredHeight);
        parms.setRecordingHint(true);
        mCamera.setParameters(parms);

        int[] fpsRange = new int[2];
        Camera.Size mCameraPreviewSize = parms.getPreviewSize();
        parms.getPreviewFpsRange(fpsRange);
        String previewFacts = mCameraPreviewSize.width + "x" + mCameraPreviewSize.height;
        if (fpsRange[0] == fpsRange[1]) {
            previewFacts += " @" + (fpsRange[0] / 1000.0) + "fps";
        } else {
            previewFacts += " @[" + (fpsRange[0] / 1000.0) +
                    " - " + (fpsRange[1] / 1000.0) + "] fps";
        }
        TextView text = findViewById(R.id.cameraParams_text);
        text.setText(previewFacts);

        mCameraPreviewWidth = mCameraPreviewSize.width;
        mCameraPreviewHeight = mCameraPreviewSize.height;


        AspectFrameLayout layout = findViewById(R.id.cameraPreview_afl);

        Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

        layout.setAspectRatio(1.0);
        layout.setBackgroundColor(0x00000000); // Transparent
        if (display.getRotation() == Surface.ROTATION_0) {
            mCamera.setDisplayOrientation(90);
        } else if (display.getRotation() == Surface.ROTATION_270) {
            mCamera.setDisplayOrientation(180);
        }
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
            Log.d(TAG, "releaseCamera -- done");
        }
    }

    public void clickToggleRecording(@SuppressWarnings("unused") View unused) {
        mRecordingEnabled = !mRecordingEnabled;
        mGLView.queueEvent(new Runnable() {
            @Override
            public void run() {
                // notify the renderer that we want to change the encoder's state
                mRenderer.changeRecordingState(mRecordingEnabled);
            }
        });
        updateControls();
    }

    private void updateControls() {
        Button toggleRelease = findViewById(R.id.toggleRecording_button);
        int id = mRecordingEnabled ?
                R.string.toggleRecordingOff : R.string.toggleRecordingOn;
        toggleRelease.setText(id);
    }

    private void handleSetSurfaceTexture(SurfaceTexture st) {
        st.setOnFrameAvailableListener(this);
        try {
            mCamera.setPreviewTexture(st);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }
        mCamera.startPreview();
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        if (VERBOSE) Log.d(TAG, "ST onFrameAvailable");
        mGLView.requestRender();
    }

    static class CameraHandler extends Handler {
        public static final int MSG_SET_SURFACE_TEXTURE = 0;

        private final WeakReference<CameraCaptureActivity> mWeakActivity;

        public CameraHandler(CameraCaptureActivity activity) {
            mWeakActivity = new WeakReference<CameraCaptureActivity>(activity);
        }

        public void invalidateHandler() {
            mWeakActivity.clear();
        }

        @Override  // runs on UI thread
        public void handleMessage(Message inputMessage) {
            int what = inputMessage.what;
            Log.d(TAG, "CameraHandler [" + this + "]: what=" + what);

            CameraCaptureActivity activity = mWeakActivity.get();
            if (activity == null) {
                Log.w(TAG, "CameraHandler.handleMessage: activity is null");
                return;
            }

            switch (what) {
                case MSG_SET_SURFACE_TEXTURE:
                    activity.handleSetSurfaceTexture((SurfaceTexture) inputMessage.obj);
                    break;
                default:
                    throw new RuntimeException("unknown msg " + what);
            }
        }
    }
}

class CameraSurfaceRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = MainActivity.TAG;
    private static final boolean VERBOSE = false;

    private static final int RECORDING_OFF = 0;
    private static final int RECORDING_ON = 1;
    private static final int RECORDING_RESUMED = 2;

    private final CameraCaptureActivity.CameraHandler mCameraHandler;
    private final TextureMovieEncoder mVideoEncoder;
    private final File mOutputFile;

    private FullFrameRect mFullScreen;
    private FullFrameRect mFullScreen2D; // For drawing 2D FBO texture to screen

    private final float[] mSTMatrix = new float[16];
    private int mTextureId;

    public SurfaceTexture mSurfaceTexture;
    private boolean mRecordingEnabled;
    private int mRecordingStatus;
    private final int mFrameCount;

    // width/height of the incoming camera preview frames
    private boolean mIncomingSizeUpdated;
    private int mIncomingWidth;
    private int mIncomingHeight;

    private int mCurrentFilter;
    private int mNewFilter;

    // FBO and filtered texture for recording
    private int mFboId = -1;
    private int mFboTexId = -1;
    private int size;
    private int videoWidth;
    private int videoHeight;

    public CameraSurfaceRenderer(CameraCaptureActivity.CameraHandler cameraHandler,
                                 TextureMovieEncoder movieEncoder, File outputFile) {
        mCameraHandler = cameraHandler;
        mVideoEncoder = movieEncoder;
        mOutputFile = outputFile;

        mTextureId = -1;

        mRecordingStatus = -1;
        mRecordingEnabled = false;
        mFrameCount = -1;

        mIncomingSizeUpdated = false;
        mIncomingWidth = mIncomingHeight = -1;

        mCurrentFilter = -1;
        mNewFilter = CameraCaptureActivity.FILTER_NONE;
    }

    public void notifyPausing() {
        if (mSurfaceTexture != null) {
            Log.d(TAG, "renderer pausing -- releasing SurfaceTexture");
            mSurfaceTexture.release();
            mSurfaceTexture = null;
        }
        if (mFullScreen != null) {
            mFullScreen.release(false);
            mFullScreen = null;
        }
        if (mFullScreen2D != null) {
            mFullScreen2D.release(false);
            mFullScreen2D = null;
        }
        mIncomingWidth = mIncomingHeight = -1;
    }

    public void changeRecordingState(boolean isRecording) {
        Log.d(TAG, "changeRecordingState: was " + mRecordingEnabled + " now " + isRecording);
        mRecordingEnabled = isRecording;
    }

    public void changeFilterMode(int filter) {
        mNewFilter = filter;
    }

    public void updateFilter() {
        Texture2dProgram.ProgramType programType;
        float[] kernel = null;
        float colorAdj = 0.0f;

        Log.d(TAG, "Updating filter to " + mNewFilter);
        switch (mNewFilter) {
            case CameraCaptureActivity.FILTER_NONE:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT;
                break;
            case CameraCaptureActivity.FILTER_BLACK_WHITE:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_BW;
                break;
            case CameraCaptureActivity.FILTER_BLUR:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                kernel = new float[]{
                        1f / 16f, 2f / 16f, 1f / 16f,
                        2f / 16f, 4f / 16f, 2f / 16f,
                        1f / 16f, 2f / 16f, 1f / 16f};
                break;
            case CameraCaptureActivity.FILTER_SHARPEN:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                kernel = new float[]{
                        0f, -1f, 0f,
                        -1f, 5f, -1f,
                        0f, -1f, 0f};
                break;
            case CameraCaptureActivity.FILTER_EDGE_DETECT:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                kernel = new float[]{
                        -1f, -1f, -1f,
                        -1f, 8f, -1f,
                        -1f, -1f, -1f};
                break;
            case CameraCaptureActivity.FILTER_EMBOSS:
                programType = Texture2dProgram.ProgramType.TEXTURE_EXT_FILT;
                kernel = new float[]{
                        2f, 0f, 0f,
                        0f, -1f, 0f,
                        0f, 0f, -1f};
                colorAdj = 0.5f;
                break;
            default:
                throw new RuntimeException("Unknown filter mode " + mNewFilter);
        }

        if (programType != mFullScreen.getProgram().getProgramType()) {
            mFullScreen.changeProgram(new Texture2dProgram(programType));
            mIncomingSizeUpdated = true;
        }

        if (kernel != null) {
            mFullScreen.getProgram().setKernel(kernel, colorAdj);
        }

        mCurrentFilter = mNewFilter;
    }

    public void setCameraPreviewSize(int width, int height) {
        Log.d(TAG, "setCameraPreviewSize");
        mIncomingWidth = width;
        mIncomingHeight = height;
        mIncomingSizeUpdated = true;
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        Log.d(TAG, "onSurfaceCreated");

        mRecordingEnabled = mVideoEncoder.isRecording();
        if (mRecordingEnabled) {
            mRecordingStatus = RECORDING_RESUMED;
        } else {
            mRecordingStatus = RECORDING_OFF;
        }

        mFullScreen = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_EXT));
        mFullScreen2D = new FullFrameRect(
                new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D));

        mTextureId = mFullScreen.createTextureObject();

        int[] fbo = new int[1];
        int[] tex = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        mFboId = fbo[0];
        mFboTexId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mFboTexId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        mSurfaceTexture = new SurfaceTexture(mTextureId);
        mCameraHandler.sendMessage(mCameraHandler.obtainMessage(
                CameraCaptureActivity.CameraHandler.MSG_SET_SURFACE_TEXTURE, mSurfaceTexture));
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        Log.d(TAG, "onSurfaceChanged " + width + "x" + height);
        size = Math.min(width, height);

        videoWidth = width;
        videoHeight = height;
    }

    private void setViewPort() {
        GLES20.glViewport(0, 0, size, size);
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
    @Override
    public void onDrawFrame(GL10 unused) {
        if (VERBOSE) Log.d(TAG, "onDrawFrame tex=" + mTextureId);
        boolean showBox = false;

        Log.d("MyLog", "mSurfaceTexture.updateTexImage() onDrawFrame");
        mSurfaceTexture.updateTexImage();

        if (mRecordingEnabled) {
            switch (mRecordingStatus) {
                case RECORDING_OFF:
                    Log.d(TAG, "START recording");
                    // start recording
                    mVideoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
                            mOutputFile, size, size, 1000000, EGL14.eglGetCurrentContext()));
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_RESUMED:
                    Log.d(TAG, "RESUME recording");
                    mVideoEncoder.updateSharedContext(EGL14.eglGetCurrentContext());
                    mRecordingStatus = RECORDING_ON;
                    break;
                case RECORDING_ON:
                    // yay
                    break;
                default:
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        } else {
            switch (mRecordingStatus) {
                case RECORDING_ON:
                case RECORDING_RESUMED:
                    // stop recording
                    Log.d(TAG, "STOP recording");
                    mVideoEncoder.stopRecording();
                    mRecordingStatus = RECORDING_OFF;
                    break;
                case RECORDING_OFF:
                    // yay
                    break;
                default:
                    throw new RuntimeException("unknown status " + mRecordingStatus);
            }
        }

        if (mCurrentFilter != mNewFilter) {
            updateFilter();
        }
        if (mIncomingSizeUpdated) {
            int incomingSize = Math.min(mIncomingWidth, mIncomingHeight);
            float aspect = (float) size / incomingSize;
            int texWidth = (int) (mIncomingWidth / aspect);
            int texHeight = (int) (mIncomingHeight / aspect);
            Log.d("Size GLES20", "size = " + size + ", texWidth = " + texWidth + ", texHeight = " + texHeight);
            Log.d("Size GLES20", "videoWidth = " + videoWidth + ", videoHeight = " + videoHeight + ", mIncomingWidth = " + mIncomingWidth+", mIncomingHeight = "+mIncomingHeight);
            mFullScreen.getProgram().setTexSize(mIncomingWidth, mIncomingHeight);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mFboTexId);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, texWidth, texHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            mIncomingSizeUpdated = false;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, mFboTexId, 0);
        setViewPort();
        mSurfaceTexture.getTransformMatrix(mSTMatrix);
        mFullScreen.drawFrame(mTextureId, mSTMatrix, videoWidth, videoHeight);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        // Restore viewport for preview
        setViewPort();
        // Draw FBO texture to screen (FBO 2D texture -> screen)
        float[] identity = new float[16];
        android.opengl.Matrix.setIdentityM(identity, 0);
        mFullScreen2D.drawFrame(mFboTexId, identity, videoWidth, videoHeight);
        // Set the video encoder's texture name to the filtered FBO texture
        mVideoEncoder.setTextureId(mFboTexId);
        // Tell the video encoder thread that a new frame is available.
        mVideoEncoder.frameAvailable(mSurfaceTexture);

        // Draw a flashing box if we're recording.  This only appears on screen.
        /*showBox = (mRecordingStatus == RECORDING_ON);
        if (showBox && (++mFrameCount & 0x04) == 0) {
            drawBox();
        }*/
    }

    private void drawBox() {
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
        GLES20.glScissor(0, 0, 100, 100);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
    }
}
