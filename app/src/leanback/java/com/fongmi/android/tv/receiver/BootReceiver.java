package com.fongmi.android.tv.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.LiveConfig;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !isBootAction(intent.getAction())) return;
        registerCallback();
    }

    private boolean isBootAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action) || "android.intent.action.QUICKBOOT_POWERON".equals(action);
    }

    private void registerCallback() {
        // registerDefaultNetworkCallback 是 API 24+ 才有，Android 6.0 上会 NoSuchMethodError；改用 API 21+ 的 registerNetworkCallback
        NetworkRequest request = new NetworkRequest.Builder().build();
        ((ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE)).registerNetworkCallback(request, new Callback());
    }

    static class Callback extends ConnectivityManager.NetworkCallback {

        @Override
        public void onAvailable(@NonNull Network network) {
            doJob();
        }

        @Override
        public void onLost(@NonNull Network network) {
        }

        private void doJob() {
            LiveConfig.get().init().load();
            ((ConnectivityManager) App.get().getSystemService(Context.CONNECTIVITY_SERVICE)).unregisterNetworkCallback(this);
        }
    }
}
