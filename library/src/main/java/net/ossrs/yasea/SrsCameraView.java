package net.ossrs.yasea;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import java.io.IOException;
import java.util.List;

/**
 * Created by leo.ma on 2016/9/13.
 */
public class SrsCameraView extends SurfaceView implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private Camera mCamera;

    private int mPreviewRotation = 90;
    private int mCamId = -1;
    private PreviewCallback mPrevCb;
    private byte[] mYuvPreviewFrame;
    private Resolution mResolution = new Resolution(SrsEncoder.vPrevWidth, SrsEncoder.vPrevHeight);

    public interface PreviewCallback {
        void onGetYuvFrame(byte[] data);
    }

    public SrsCameraView(Context context) {
        this(context, null);
    }

    public SrsCameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
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

    public void setPreviewCallback(PreviewCallback cb) {
        mPrevCb = cb;
        getHolder().addCallback(this);
    }

    private Resolution getBestCameraResolution(Camera.Parameters parameters,Resolution screenResolution) {
        float tmp;
        float mindiff = 100f;
        float x_d_y = (float) screenResolution.width / (float) screenResolution.height;
        Camera.Size best = null;
        List<Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();
        for (Camera.Size s : supportedPreviewSizes) {
            tmp = Math.abs(((float) s.height / (float) s.width) - x_d_y);
            if (tmp < mindiff) {
                mindiff = tmp;
                best = s;
            }
        }
        return new Resolution(best.width, best.height);
    }

    private Camera createCamera() {
        Camera camera;
        if (mCamId < 0) {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int numCameras = Camera.getNumberOfCameras();
            int frontId = -1;
            int defaultId = -1;
            for (int i = 0; i < numCameras; i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    defaultId = i;
                }
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    frontId = i;
                    break;
                }
            }
            if (frontId != -1) {
                mCamId = frontId;
            } else if (defaultId != -1) {
                mCamId = defaultId;
            } else {
                mCamId = 0;
            }
        }
        camera = Camera.open(mCamId);
        return camera;
    }

    public Resolution setPreviewResolution(Resolution resolution) {
        mResolution.width = resolution.width;
        mResolution.height = resolution.height;
        if (mCamId < Camera.getNumberOfCameras()) {
            Camera camera = createCamera();
            if (camera != null) {
                Resolution rs = getBestCameraResolution(camera.getParameters(), mResolution);
                if (rs != null) {
                    mResolution = rs;
                }
                camera.release();
            }
        }
        return mResolution;
    }

    public Resolution getPreviewResolution() {
        return mResolution;
    }

    public boolean startCamera() {
        if (mCamera != null) {
            return false;
        }

        mCamera = createCamera();
        if (mCamera == null) {
            return false;
        }

        Camera.Parameters params = mCamera.getParameters();
        Camera.Size size = mCamera.new Size(mResolution.width, mResolution.height);
        if (!params.getSupportedPreviewSizes().contains(size)) {
            Toast.makeText(getContext(), String.format("Unsupported resolution %dx%d", size.width, size.height), Toast.LENGTH_SHORT).show();
            stopCamera();
            return false;
        }

        mYuvPreviewFrame = new byte[mResolution.width * mResolution.height * 3 / 2];

        params.setPreviewSize(mResolution.width, mResolution.height);
        int[] range = findClosestFpsRange(SrsEncoder.VFPS, params.getSupportedPreviewFpsRange());
        params.setPreviewFpsRange(range[0], range[1]);
        params.setPreviewFormat(ImageFormat.NV21);
        params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
        params.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);

        List<String> supportedFocusModes = params.getSupportedFocusModes();

        if (!supportedFocusModes.isEmpty()) {
            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else {
                params.setFocusMode(supportedFocusModes.get(0));
            }
        }

        mCamera.setParameters(params);

        mCamera.setDisplayOrientation(mPreviewRotation);

        mCamera.addCallbackBuffer(mYuvPreviewFrame);
        mCamera.setPreviewCallbackWithBuffer(this);
        try {
            mCamera.setPreviewDisplay(getHolder());
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCamera.startPreview();

        return true;
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

    public void stopCamera() {
        if (mCamera != null) {
            // need to SET NULL CB before stop preview!!!
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        mPrevCb.onGetYuvFrame(data);
        camera.addCallbackBuffer(mYuvPreviewFrame);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder arg0) {
        if (mCamera != null) {
            try {
                mCamera.setPreviewDisplay(getHolder());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder arg0) {
    }

    public static class Resolution {
        int width;
        int height;

        public Resolution(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int getHeight() {
            return height;
        }

        public void setHeight(int height) {
            this.height = height;
        }

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }
    }
}
