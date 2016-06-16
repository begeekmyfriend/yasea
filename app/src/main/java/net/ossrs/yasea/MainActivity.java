package net.ossrs.yasea;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.hardware.Camera;
import android.hardware.Camera.Size;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Random;

import net.ossrs.yasea.rtmp.RtmpPublisher;

public class MainActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private static final String TAG = "Yasea";

    Button btnPublish = null;
    Button btnSwitch = null;
    Button btnRecord = null;

    private AudioRecord mic = null;
    private boolean aloop = false;
    private Thread aworker = null;

    private SurfaceView mCameraView = null;
    private Camera mCamera = null;

    private int mPreviewRotation = 90;
    private int mCamId = Camera.getNumberOfCameras() - 1; // default camera
    private byte[] mYuvFrameBuffer = new byte[SrsEncoder.VPREV_WIDTH * SrsEncoder.VPREV_HEIGHT * 3 / 2];

    private String mNotifyMsg;
    private SharedPreferences sp;
    private String rtmpUrl = "rtmp://ossrs.net/" + getRandomAlphaString(3) + '/' + getRandomAlphaDigitString(5);
    private String recPath = Environment.getExternalStorageDirectory().getPath() + "/test.mp4";

    private SrsFlvMuxer flvMuxer = new SrsFlvMuxer(new RtmpPublisher.EventHandler() {
        @Override
        public void onRtmpConnecting(String msg) {
            mNotifyMsg = msg;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), mNotifyMsg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onRtmpConnected(String msg) {
            mNotifyMsg = msg;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), mNotifyMsg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onRtmpVideoStreaming(String msg) {
        }

        @Override
        public void onRtmpAudioStreaming(String msg) {
        }

        @Override
        public void onRtmpStopped(String msg) {
            mNotifyMsg = msg;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), mNotifyMsg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onRtmpDisconnected(String msg) {
            mNotifyMsg = msg;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), mNotifyMsg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onRtmpOutputFps(final double fps) {
            Log.i(TAG, String.format("Output Fps: %f", fps));
        }
    });

    private SrsMp4Muxer mp4Muxer = new SrsMp4Muxer(new SrsMp4Muxer.EventHandler() {
        @Override
        public void onRecordPause(String msg) {
            mNotifyMsg = msg;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), mNotifyMsg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onRecordResume(String msg) {
            mNotifyMsg = msg;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), mNotifyMsg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onRecordStarted(String msg) {
            mNotifyMsg = "Recording file: " + msg;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), mNotifyMsg, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onRecordFinished(String msg) {
            mNotifyMsg = "MP4 file saved: " + msg;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(getApplicationContext(), mNotifyMsg, Toast.LENGTH_SHORT).show();
                }
            });
        }
    });

    private SrsEncoder mEncoder = new SrsEncoder(flvMuxer, mp4Muxer);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // response screen rotation event
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);

        // restore data.
        sp = getSharedPreferences("SrsPublisher", MODE_PRIVATE);
        rtmpUrl = sp.getString("rtmpUrl", rtmpUrl);

        // initialize url.
        final EditText efu = (EditText) findViewById(R.id.url);
        efu.setText(rtmpUrl);

        // for camera, @see https://developer.android.com/reference/android/hardware/Camera.html
        btnPublish = (Button) findViewById(R.id.publish);
        btnSwitch = (Button) findViewById(R.id.swCam);
        btnRecord = (Button) findViewById(R.id.record);
        mCameraView = (SurfaceView) findViewById(R.id.preview);
        mCameraView.getHolder().addCallback(this);

        btnPublish.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (btnPublish.getText().toString().contentEquals("publish")) {
                    rtmpUrl = efu.getText().toString();
                    Log.i(TAG, String.format("RTMP URL changed to %s", rtmpUrl));
                    SharedPreferences.Editor editor = sp.edit();
                    editor.putString("rtmpUrl", rtmpUrl);
                    editor.commit();

                    try {
                        flvMuxer.start(rtmpUrl);
                    } catch (IOException e) {
                        Log.e(TAG, "start FLV muxer failed.");
                        e.printStackTrace();
                        return;
                    }
                    flvMuxer.setVideoResolution(mEncoder.VOUT_WIDTH, mEncoder.VOUT_HEIGHT);

                    startEncoder();
                    btnPublish.setText("stop");
                } else if (btnPublish.getText().toString().contentEquals("stop")) {
                    stopEncoder();
                    flvMuxer.stop();
                    mp4Muxer.stop();
                    btnPublish.setText("publish");
                    btnRecord.setText("record");
                }
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

        btnRecord.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (btnRecord.getText().toString().contentEquals("record")) {
                    try {
                        mp4Muxer.record(new File(recPath));
                    } catch (IOException e) {
                        Log.e(TAG, "start MP4 muxer failed.");
                        e.printStackTrace();
                    }
                    btnRecord.setText("pause");
                } else if (btnRecord.getText().toString().contentEquals("pause")) {
                    mp4Muxer.pause();
                    btnRecord.setText("resume");
                } else if (btnRecord.getText().toString().contentEquals("resume")) {
                    mp4Muxer.resume();
                    btnRecord.setText("pause");
                }
            }
        });

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                mNotifyMsg = ex.getMessage();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getApplicationContext(), mNotifyMsg, Toast.LENGTH_LONG).show();
                        stopEncoder();
                        flvMuxer.stop();
                        mp4Muxer.stop();
                        btnPublish.setText("publish");
                        btnRecord.setText("record");
                    }
                });
            }
        });
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
        Camera.Parameters params = mCamera.getParameters();
		/* preview size  */
        Size size = mCamera.new Size(SrsEncoder.VPREV_WIDTH, SrsEncoder.VPREV_HEIGHT);
        if (!params.getSupportedPreviewSizes().contains(size)) {
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(),
                    new IllegalArgumentException(String.format("Unsupported preview size %dx%d", size.width, size.height)));
        }

        /* picture size  */
        if (!params.getSupportedPictureSizes().contains(size)) {
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(Thread.currentThread(),
                    new IllegalArgumentException(String.format("Unsupported picture size %dx%d", size.width, size.height)));
        }

        /***** set parameters *****/
        //params.set("orientation", "portrait");
        //params.set("orientation", "landscape");
        //params.setRotation(90);
        params.setPictureSize(SrsEncoder.VPREV_WIDTH, SrsEncoder.VPREV_HEIGHT);
        params.setPreviewSize(SrsEncoder.VPREV_WIDTH, SrsEncoder.VPREV_HEIGHT);
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
                aworker.interrupt();
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

    private void startEncoder() {
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

    private void stopEncoder() {
        stopAudio();
        stopCamera();
        mEncoder.stop();
    }

    private static int[] findClosestFpsRange(int expectedFps, List<int[]> fpsRanges) {
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

    private static String getRandomAlphaString(int length) {
        String base = "abcdefghijklmnopqrstuvwxyz";
        Random random = new Random();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }

    private static String getRandomAlphaDigitString(int length) {
        String base = "abcdefghijklmnopqrstuvwxyz0123456789";
        Random random = new Random();
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged");
    }

    @Override
    public void surfaceCreated(SurfaceHolder arg0) {
        Log.d(TAG, "surfaceCreated");
        if (mCamera != null) {
            try {
                mCamera.setPreviewDisplay(mCameraView.getHolder());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder arg0) {
        Log.d(TAG, "surfaceDestroyed");
    }

    @Override
    protected void onResume() {
        super.onResume();
        final Button btn = (Button) findViewById(R.id.publish);
        btn.setEnabled(true);
        mp4Muxer.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mp4Muxer.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopEncoder();
        flvMuxer.stop();
        mp4Muxer.stop();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            mPreviewRotation = 90;
        } else if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mPreviewRotation = 0;
        }
        stopEncoder();
        mp4Muxer.stop();
        btnRecord.setText("record");
        mEncoder.setScreenOrientation(newConfig.orientation);
        if (btnPublish.getText().toString().contentEquals("stop")) {
            startEncoder();
        }
    }
}
