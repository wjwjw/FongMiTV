package com.fongmi.android.tv.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 调试用「站点级测速」入口。本 Receiver 仅负责 startService() 后立刻返回，
 * 真正的逐站点 search/play 测速在 {@link SiteSpeedService} 中执行（避开 goAsync 广播 60s ANR 上限）。
 *
 * 触发：adb shell am broadcast -n <pkg>/com.fongmi.android.tv.receiver.SiteSpeedReceiver -a com.fongmi.android.tv.action.SITESPEED
 * 完成标志：logcat 出现 "SITESPEED_DONE count=N"
 */
public class SiteSpeedReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.fongmi.android.tv.action.SITESPEED";
    private static final String TAG = "SiteSpeed";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        Log.i(TAG, "SITESPEED_ONRECEIVE");
        // 立即返回，不在广播里干重活；交给 Service 长期运行
        context.startService(new Intent(context, SiteSpeedService.class));
    }
}
