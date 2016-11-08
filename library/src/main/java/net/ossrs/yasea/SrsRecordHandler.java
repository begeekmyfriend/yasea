package net.ossrs.yasea;

import android.os.Handler;
import android.os.Message;

import java.lang.ref.WeakReference;

/**
 * Created by leo.ma on 2016/11/4.
 */

public class SrsRecordHandler extends Handler {
    private static final int MSG_RECORD_PAUSE = 0;
    private static final int MSG_RECORD_RESUME = 1;
    private static final int MSG_RECORD_STARTED = 2;
    private static final int MSG_RECORD_FINISHED = 3;

    private WeakReference<SrsRecordListener> mWeakListener;

    SrsRecordHandler(SrsRecordListener listener) {
        mWeakListener = new WeakReference<>(listener);
    }

    public void notifyRecordPause() {
        sendEmptyMessage(MSG_RECORD_PAUSE);
    }

    public void notifyRecordResume() {
        sendEmptyMessage(MSG_RECORD_RESUME);
    }

    public void notifyRecordStarted(String msg) {
        obtainMessage(MSG_RECORD_STARTED, msg).sendToTarget();
    }

    public void notifyRecordFinished(String msg) {
        obtainMessage(MSG_RECORD_FINISHED, msg).sendToTarget();
    }

    @Override  // runs on UI thread
    public void handleMessage(Message msg) {
        SrsRecordListener listener = mWeakListener.get();
        if (listener == null) {
            return;
        }

        switch (msg.what) {
            case MSG_RECORD_PAUSE:
                listener.onRecordPause();
                break;
            case MSG_RECORD_RESUME:
                listener.onRecordResume();
                break;
            case MSG_RECORD_STARTED:
                listener.onRecordStarted((String) msg.obj);
                break;
            case MSG_RECORD_FINISHED:
                listener.onRecordFinished((String) msg.obj);
                break;
            default:
                throw new RuntimeException("unknown msg " + msg.what);
        }
    }
    
    interface SrsRecordListener {

        void onRecordPause();

        void onRecordResume();

        void onRecordStarted(String msg);

        void onRecordFinished(String msg);
    }
}
