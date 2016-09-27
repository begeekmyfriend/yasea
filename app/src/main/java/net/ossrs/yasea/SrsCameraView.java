package net.ossrs.yasea;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.widget.Toast;

import com.seu.magicfilter.base.MagicCameraInputFilter;
import com.seu.magicfilter.base.gpuimage.GPUImageFilter;
import com.seu.magicfilter.utils.MagicFilterFactory;
import com.seu.magicfilter.utils.MagicFilterType;
import com.seu.magicfilter.utils.OpenGlUtils;
import com.seu.magicfilter.utils.Rotation;
import com.seu.magicfilter.utils.TextureRotationUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Created by Leo Ma on 2016/2/25.
 */
public class SrsCameraView extends GLSurfaceView implements GLSurfaceView.Renderer, Camera.PreviewCallback {

    private GPUImageFilter filter;
    private MagicCameraInputFilter cameraInputFilter;

    private SurfaceTexture surfaceTexture;
    
    private int textureId = OpenGlUtils.NO_TEXTURE;
    private final FloatBuffer gLCubeBuffer;
    private final FloatBuffer gLTextureBuffer;
    private int surfaceWidth, surfaceHeight;
    private int previewWidth, previewHeight;

    private Camera mCamera;
    private IntBuffer mGLPreviewIntBuffer;
    private ByteBuffer mGLPreviewByteBuffer;
    private byte[] mYuvPreviewBuffer;
    private int mCamId = Camera.CameraInfo.CAMERA_FACING_FRONT;
    private int mPreviewRotation = 90;

    private Thread worker;
    private final Object writeLock = new Object();
    private ConcurrentLinkedQueue<IntBuffer> mGLIntBufferCache = new ConcurrentLinkedQueue<>();
	private PreviewCallback mPrevCb;
		
    public SrsCameraView(Context context) {
        this(context, null);
    }

    public SrsCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);

        gLCubeBuffer = ByteBuffer.allocateDirect(TextureRotationUtil.CUBE.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        gLCubeBuffer.put(TextureRotationUtil.CUBE).position(0);

        gLTextureBuffer = ByteBuffer.allocateDirect(TextureRotationUtil.TEXTURE_NO_ROTATION.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        gLTextureBuffer.put(TextureRotationUtil.getRotation(Rotation.NORMAL, false, true)).position(0);

        setEGLContextClientVersion(2);
        setRenderer(this);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

        MagicFilterFactory.initContext(context.getApplicationContext());
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glDisable(GL10.GL_DITHER);
        GLES20.glClearColor(0, 0, 0, 0);

        cameraInputFilter = new MagicCameraInputFilter();
        cameraInputFilter.init();
        cameraInputFilter.initCameraFrameBuffer(previewWidth, previewHeight);
        cameraInputFilter.onInputSizeChanged(previewWidth, previewHeight);

        textureId = OpenGlUtils.getExternalOESTextureID();
        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                requestRender();
            }
        });
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0,0,width, height);
        surfaceWidth = width;
        surfaceHeight = height;
        cameraInputFilter.onDisplaySizeChanged(surfaceWidth, surfaceHeight);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        surfaceTexture.updateTexImage();

        float[] mtx = new float[16];
        surfaceTexture.getTransformMatrix(mtx);
        cameraInputFilter.setTextureTransformMatrix(mtx);
        if (filter == null) {
            cameraInputFilter.onDrawFrame(textureId, gLCubeBuffer, gLTextureBuffer);
        } else {
            int fboTextureId = cameraInputFilter.onDrawToTexture(textureId);
            // Read under off-screen FBO mode
            GLES20.glReadPixels(0, 0, previewWidth, previewHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mGLPreviewIntBuffer);
            // Recover to window-specific FBO
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            filter.onDrawFrame(fboTextureId, gLCubeBuffer, gLTextureBuffer);
            mGLIntBufferCache.add(mGLPreviewIntBuffer);
            synchronized (writeLock) {
                writeLock.notifyAll();
            }
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        mPrevCb.onGetYuvFrame(data);
        camera.addCallbackBuffer(mYuvPreviewBuffer);
    }

    public void setPreviewCallback(PreviewCallback cb) {
        mPrevCb = cb;
    }

    public void setPreviewResolution(int width, int height) {
        previewWidth = width;
        previewHeight = height;
    }

    public boolean setFilter(final MagicFilterType type) {
        if (mCamera == null) {
            return false;
        }

        queueEvent(new Runnable() {
            @Override
            public void run() {
                if (filter != null) {
                    filter.destroy();
                }
                filter = MagicFilterFactory.initFilters(type);
                if (filter != null) {
                    filter.init();
                    filter.onDisplaySizeChanged(surfaceWidth, surfaceHeight);
                    filter.onInputSizeChanged(previewWidth, previewHeight);
                }
                switchCameraFilter();
            }
        });
        requestRender();
        return true;
    }

    private void deleteTextures() {
        if(textureId != OpenGlUtils.NO_TEXTURE){
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    GLES20.glDeleteTextures(1, new int[]{ textureId }, 0);
                    textureId = OpenGlUtils.NO_TEXTURE;
                }
            });
        }
    }

    public void setPreviewRotation(int rotation) {
        mPreviewRotation = rotation;
    }

    public void setCameraId(int id) {
        mCamId = id;
    }

    public int getCameraId() {
        return mCamId;
    }

    public boolean startCamera() {
        if (mCamera != null) {
            return false;
        }
        if (mCamId > (Camera.getNumberOfCameras() - 1) || mCamId < 0) {
            return false;
        }

        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    while (!mGLIntBufferCache.isEmpty()) {
                        IntBuffer picture = mGLIntBufferCache.poll();
                        mGLPreviewByteBuffer.asIntBuffer().put(picture.array());
                        mPrevCb.onGetRgbaFrame(mGLPreviewByteBuffer.array(), previewWidth, previewHeight);
                    }
                    // Waiting for next frame
                    synchronized (writeLock) {
                        try {
                            // isEmpty() may take some time, so we set timeout to detect next frame
                            writeLock.wait(500);
                        } catch (InterruptedException ie) {
                            worker.interrupt();
                        }
                    }
                }
            }
        });
        worker.start();

        mCamera = Camera.open(mCamId);

        Camera.Parameters params = mCamera.getParameters();
        Camera.Size size = mCamera.new Size(previewWidth, previewHeight);
        if (!params.getSupportedPreviewSizes().contains(size) || !params.getSupportedPictureSizes().contains(size)) {
            Toast.makeText(getContext(), String.format("Unsupported resolution %dx%d", size.width, size.height), Toast.LENGTH_SHORT).show();
            stopCamera();
            return false;
        }

        if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
        }

        mYuvPreviewBuffer = new byte[previewWidth * previewHeight * 3 / 2];
        mGLPreviewIntBuffer = IntBuffer.allocate(previewWidth * previewHeight);
        mGLPreviewByteBuffer = ByteBuffer.allocate(previewWidth * previewHeight * 4);

        /***** set parameters *****/
        //params.set("orientation", "portrait");
        //params.set("orientation", "landscape");
        //params.setRotation(90);
        params.setPictureSize(previewWidth, previewHeight);
        params.setPreviewSize(previewWidth, previewHeight);
        int[] range = findClosestFpsRange(SrsEncoder.VFPS, params.getSupportedPreviewFpsRange());
        params.setPreviewFpsRange(range[0], range[1]);
        params.setPreviewFormat(ImageFormat.NV21);
        params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
        params.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
        if (!params.getSupportedFocusModes().isEmpty()) {
            params.setFocusMode(params.getSupportedFocusModes().get(0));
        }
        mCamera.setParameters(params);

        mCamera.setDisplayOrientation(mPreviewRotation);

        switchCameraFilter();

        try {
            mCamera.setPreviewTexture(surfaceTexture);
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCamera.startPreview();

        return true;
    }

    public void stopCamera() {
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
                worker.interrupt();
            }
            mGLIntBufferCache.clear();
            worker = null;
        }

        if (mCamera != null) {
            // need to SET NULL CB before stop preview!!!
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    private int[] findClosestFpsRange(int expectedFps, List<int[]> fpsRanges) {
        expectedFps *= 1000;
        int[] closestRange = fpsRanges.get(0);
        int measure = Math.abs(closestRange[0] - expectedFps) + Math.abs(closestRange[1] - expectedFps);
        for (int[] range : fpsRanges) {
            if (range[0] <= expectedFps && range[1] >= expectedFps) {
                int curMeasure = Math.abs(range[0] - expectedFps) + Math.abs(range[1] - expectedFps);
                if (curMeasure < measure) {
                    closestRange = range;
                    measure = curMeasure;
                }
            }
        }
        return closestRange;
    }

    private void switchCameraFilter() {
        if (filter == null) {
            mCamera.addCallbackBuffer(mYuvPreviewBuffer);
            mCamera.setPreviewCallbackWithBuffer(this);
        } else {
            mCamera.setPreviewCallback(null);
        }
    }

    public interface PreviewCallback {
        void onGetYuvFrame(byte[] data);
        void onGetRgbaFrame(byte[] data, int width, int height);
    }
}
