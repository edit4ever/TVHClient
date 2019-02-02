package org.tvheadend.tvhclient.features.shared.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.tvheadend.tvhclient.MainApplication;
import org.tvheadend.tvhclient.features.shared.callbacks.NetworkStatusListener;
import org.tvheadend.tvhclient.utils.NetworkUtils;

import java.lang.ref.WeakReference;

public class NetworkStatusReceiver extends BroadcastReceiver {

    private final WeakReference<NetworkStatusListener> callback;

    public NetworkStatusReceiver(NetworkStatusListener callback) {
        this.callback = new WeakReference<>(callback);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (callback.get() != null && MainApplication.isActivityVisible()) {
            callback.get().onNetworkStatusChanged(NetworkUtils.isConnectionAvailable(context));
        }
    }
}
