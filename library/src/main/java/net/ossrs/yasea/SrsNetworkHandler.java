package net.ossrs.yasea;

import android.os.Handler;
import android.os.Message;

import java.lang.ref.WeakReference;

/**
 * Created by leo.ma on 2016/11/4.
 */

public class SrsNetworkHandler extends Handler {
    private static final int MSG_NETWORK_WEAK = 0;
    private static final int MSG_NETWORK_RESUME = 1;

    private WeakReference<SrsNetworkListener> mWeakListener;

    public SrsNetworkHandler(SrsNetworkListener listener) {
        mWeakListener = new WeakReference<>(listener);
    }

    public void notifyNetworkWeak() {
        sendEmptyMessage(MSG_NETWORK_WEAK);
    }

    public void notifyNetworkResume() {
        sendEmptyMessage(MSG_NETWORK_RESUME);
    }
    
    @Override  // runs on UI thread
    public void handleMessage(Message msg) {
        SrsNetworkListener listener = mWeakListener.get();
        if (listener == null) {
            return;
        }

        switch (msg.what) {
            case MSG_NETWORK_WEAK:
                listener.onNetworkWeak();
                break;
            case MSG_NETWORK_RESUME:
                listener.onNetworkResume();
                break;
            default:
                throw new RuntimeException("unknown msg " + msg.what);
        }
    }

    public interface SrsNetworkListener {

        void onNetworkWeak();

        void onNetworkResume();
    }    
}
