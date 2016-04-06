package net.ossrs.sea;

import android.app.Activity;
import android.content.SharedPreferences;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import java.io.IOException;
import java.util.List;

public class MainActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final String TAG = "SrsPublisher";

    private AudioRecord mic = null;
    private boolean aloop = false;
    private Thread aworker = null;

    private SurfaceView mCameraView = null;
    private Camera mCamera = null;

    private int mPreviewRotation = 90;
    private int mDisplayRotation = 90;
    private int mCamId = Camera.getNumberOfCameras() - 1; // default camera
    private byte[] mYuvFrameBuffer;

    private SrsEncoder mEncoder;

    // settings storage
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sp = getSharedPreferences("SrsPublisher", MODE_PRIVATE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        mEncoder = new SrsEncoder();
        mYuvFrameBuffer = new byte[SrsEncoder.VWIDTH * SrsEncoder.VHEIGHT * 3 / 2];

        // restore data.
        SrsEncoder.rtmpUrl = sp.getString("SrsEncoder.rtmpUrl", SrsEncoder.rtmpUrl);
        SrsEncoder.vbitrate = sp.getInt("VBITRATE", SrsEncoder.vbitrate);
        Log.i(TAG, String.format("initialize rtmp url to %s, vbitrate=%dkbps", SrsEncoder.rtmpUrl, SrsEncoder.vbitrate));

        // initialize url.
        final EditText efu = (EditText) findViewById(R.id.url);
        efu.setText(SrsEncoder.rtmpUrl);
        efu.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String fu = efu.getText().toString();
                if (fu == SrsEncoder.rtmpUrl || fu.isEmpty()) {
                    return;
                }

                SrsEncoder.rtmpUrl = fu;
                Log.i(TAG, String.format("flv url changed to %s", SrsEncoder.rtmpUrl));

                SharedPreferences.Editor editor = sp.edit();
                editor.putString("SrsEncoder.rtmpUrl", SrsEncoder.rtmpUrl);
                editor.commit();
            }
        });

        final EditText evb = (EditText) findViewById(R.id.vbitrate);
        evb.setText(String.format("%dkbps", SrsEncoder.vbitrate / 1000));
        evb.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                int vb = Integer.parseInt(evb.getText().toString().replaceAll("kbps", ""));
                if (vb * 1000 != SrsEncoder.vbitrate) {
                    SrsEncoder.vbitrate = vb * 1000;
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putInt("VBITRATE", SrsEncoder.vbitrate);
                    editor.commit();
                }
            }
        });

        // for camera, @see https://developer.android.com/reference/android/hardware/Camera.html
        final Button btnPublish = (Button) findViewById(R.id.publish);
        final Button btnStop = (Button) findViewById(R.id.stop);
        final Button btnSwitch = (Button) findViewById(R.id.swCam);
        final Button btnRotate = (Button) findViewById(R.id.rotate);
        mCameraView = (SurfaceView) findViewById(R.id.preview);
        mCameraView.getHolder().addCallback(this);
        // mCameraView.getHolder().setFormat(SurfaceHolder.SURFACE_TYPE_HARDWARE);
        btnPublish.setEnabled(true);
        btnStop.setEnabled(false);

        btnPublish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPublish();
                btnPublish.setEnabled(false);
                btnStop.setEnabled(true);
            }
        });

        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPublish();
                btnPublish.setEnabled(true);
                btnStop.setEnabled(false);
            }
        });

        btnSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCamera != null && mEncoder != null) {
                    mCamId = (mCamId + 1) % Camera.getNumberOfCameras();
                    stopCamera();
                    mEncoder.swithCameraFace();
                    startCamera();
                }
            }
        });

        btnRotate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mCamera != null) {
                    mPreviewRotation = (mPreviewRotation + 90) % 360;
                    mCamera.setDisplayOrientation(mPreviewRotation);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        final Button btn = (Button) findViewById(R.id.publish);
        btn.setEnabled(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void startCamera() {
        if (mCamera != null) {
            Log.d(TAG, "start camera, already started. return");
            return;
        }
        if (mCamId > (Camera.getNumberOfCameras() - 1) || mCamId < 0) {
            Log.e(TAG, "####### start camera failed, inviald params, camera No.="+ mCamId);
            return;
        }

        mCamera = Camera.open(mCamId);

        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(mCamId, info);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT){
            mDisplayRotation = (mPreviewRotation + 180) % 360;
            mDisplayRotation = (360 - mDisplayRotation) % 360;
        } else {
            mDisplayRotation = mPreviewRotation;
        }

        Camera.Parameters params = mCamera.getParameters();

		/* supported preview fps range */
//        List<int[]> spfr = params.getSupportedPreviewFpsRange();
//        Log.i("Cam", "! Supported Preview Fps Range:");
//        int rn = 0;
//        for (int[] r : spfr) {
//            Log.i("Cam", "\tRange [" + rn++ + "]: " + r[0] + "~" + r[1]);
//        }
//		/* preview size  */
        List<Size> sizes = params.getSupportedPreviewSizes();
        Log.i("Cam", "! Supported Preview Size:");
        for (int i = 0; i < sizes.size(); i++) {
            Log.i("Cam", "\tSize [" + i + "]: " + sizes.get(i).width + "x" + sizes.get(i).height);
        }
        /* picture size  */
        sizes = params.getSupportedPictureSizes();
        Log.i("Cam", "! Supported Picture Size:");
        for (int i = 0; i < sizes.size(); i++) {
            Log.i("Cam", "\tSize [" + i + "]: " + sizes.get(i).width + "x" + sizes.get(i).height);
        }

        /***** set parameters *****/
        //params.set("orientation", "portrait");
        //params.set("orientation", "landscape");
        //params.setRotation(90);
        params.setPictureSize(SrsEncoder.VWIDTH, SrsEncoder.VHEIGHT);
        params.setPreviewSize(SrsEncoder.VWIDTH, SrsEncoder.VHEIGHT);
        int[] range = findClosestFpsRange(SrsEncoder.VFPS, params.getSupportedPreviewFpsRange());
        params.setPreviewFpsRange(range[0], range[1]);
        params.setPreviewFormat(SrsEncoder.VFORMAT);
        params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
        params.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
        mCamera.setParameters(params);

        mCamera.setDisplayOrientation(mPreviewRotation);

        mCamera.addCallbackBuffer(mYuvFrameBuffer);
        mCamera.setPreviewCallbackWithBuffer(this);
        try {
            mCamera.setPreviewDisplay(mCameraView.getHolder());
        } catch (IOException e) {
            e.printStackTrace();
        }
        mCamera.startPreview();
    }

    private void stopCamera() {
        if (mCamera != null) {
            // need to SET NULL CB before stop preview!!!
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    private void onGetYuvFrame(byte[] data) {
        mEncoder.onGetYuvFrame(data);
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera c) {
        onGetYuvFrame(data);
        c.addCallbackBuffer(mYuvFrameBuffer);
    }

    private void onGetPcmFrame(byte[] pcmBuffer, int size) {
        mEncoder.onGetPcmFrame(pcmBuffer, size);
    }

    private void startAudio() {
        if (mic != null) {
            return;
        }

        int bufferSize = 2 * AudioRecord.getMinBufferSize(SrsEncoder.ASAMPLERATE, SrsEncoder.ACHANNEL, SrsEncoder.AFORMAT);
        mic = new AudioRecord(MediaRecorder.AudioSource.MIC, SrsEncoder.ASAMPLERATE, SrsEncoder.ACHANNEL, SrsEncoder.AFORMAT, bufferSize);
        mic.startRecording();

        byte pcmBuffer[] = new byte[4096];
        while (aloop && !Thread.interrupted()) {
            int size = mic.read(pcmBuffer, 0, pcmBuffer.length);
            if (size <= 0) {
                Log.e(TAG, "***** audio ignored, no data to read.");
                break;
            }
            onGetPcmFrame(pcmBuffer, size);
        }
    }

    private void stopAudio() {
        aloop = false;
        if (aworker != null) {
            Log.i(TAG, "stop audio worker thread");
            aworker.interrupt();
            try {
                aworker.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            aworker = null;
        }

        if (mic != null) {
            mic.setRecordPositionUpdateListener(null);
            mic.stop();
            mic.release();
            mic = null;
        }
    }

    private void startPublish() {
        int ret = mEncoder.start();
        if (ret < 0) {
            return;
        }

        startCamera();

        aworker = new Thread(new Runnable() {
            @Override
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                startAudio();
            }
        });
        aloop = true;
        aworker.start();
    }

    private void stopPublish() {
        stopAudio();
        stopCamera();
        mEncoder.stop();
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

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged");
    }

    @Override
    public void surfaceCreated(SurfaceHolder arg0) {
        Log.d(TAG, "surfaceCreated");
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder arg0) {
        Log.d(TAG, "surfaceDestroyed");
    }
}
