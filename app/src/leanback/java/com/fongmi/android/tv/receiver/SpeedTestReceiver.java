package com.fongmi.android.tv.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.fongmi.android.tv.R;
import com.github.catvod.net.OkHttp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Response;

/**
 * 调试用：通过 adb 触发，在真机网络上用项目自带的 OkHttp（含 IDN 兼容）逐条测速推荐源。
 * 结果打到 logcat（tag=SpeedTest），供 PC 端 `adb logcat -s SpeedTest` 抓回分析。
 * 不落盘，避开 Android 6 上应用私有目录 adb 读不到的权限问题。
 *
 * 触发：adb shell am broadcast -n <pkg>/com.fongmi.android.tv.receiver.SpeedTestReceiver
 * 完成标志：logcat 出现 "SPEEDTEST_DONE count=N"
 */
public class SpeedTestReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.fongmi.android.tv.action.SPEEDTEST";
    private static final String TAG = "SpeedTest";
    // qist/tvbox 仓库根目录动态源（RecommendDialog.NAME_MAP 的 key），种子未覆盖的
    private static final String[] QIST_FILES = {
            "0707.json", "0821.json", "0825.json", "0826.json", "0827.json",
            "367.json", "9918.json", "99188.json", "XYQ.json", "dianshi.json", "js.json"
    };
    private static final String QIST_MIRROR = "https://qist.wyfc.qzz.io/";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        Log.i(TAG, "SPEEDTEST_ONRECEIVE");
        // goAsync：广播只起了一个临时进程，onReceive 返回后系统会回收进程导致后台线程被秒杀。
        // 用 PendingResult 保活，直到 runTest 跑完调用 finish()。
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                runTest(context);
            } finally {
                result.finish();
            }
        }).start();
    }

    private void runTest(Context context) {
        List<Source> sources = collectSources(context);
        int idx = 0;
        for (Source s : sources) {
            long start = System.currentTimeMillis();
            int code = -1;
            long bytes = -1;
            boolean ok = false;
            try (Response res = OkHttp.newCall(s.url, "speedtest").execute()) {
                code = res.code();
                ok = res.isSuccessful();
                String body = res.body() != null ? res.body().string() : "";
                bytes = body.length();
            } catch (Exception e) {
                Log.w(TAG, "fail " + s.name + " : " + e.getMessage());
            }
            long time = System.currentTimeMillis() - start;
            Log.i(TAG, String.format("[%d] %s | %s | code=%d | %dms | %dB | ok=%b",
                    idx, s.name, s.url, code, time, bytes, ok));
            idx++;
        }
        Log.i(TAG, "SPEEDTEST_DONE count=" + idx);
    }

    private List<Source> collectSources(Context context) {
        Map<String, Source> map = new LinkedHashMap<>();
        for (String entry : context.getResources().getStringArray(R.array.recommend_vod)) {
            String[] p = entry.split("\\|", 2);
            if (p.length == 2) map.put(norm(p[1]), new Source(p[0].trim(), p[1].trim()));
        }
        for (String f : QIST_FILES) {
            String url = QIST_MIRROR + f;
            if (!map.containsKey(norm(url))) map.put(norm(url), new Source(f, url));
        }
        return new ArrayList<>(map.values());
    }

    private String norm(String url) {
        return url.trim().toLowerCase().replace("raw.githubusercontent.com/qist/tvbox/master/", "qist.wyfc.qzz.io/");
    }

    private static class Source {
        final String name;
        final String url;

        Source(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
