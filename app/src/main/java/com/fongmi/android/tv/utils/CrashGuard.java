package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;

/**
 * 崩溃安全守卫：防止「某个点播源在加载/渲染时触发 spider 自卫式 System.exit 自杀」导致应用永久无法进入设置。
 * 原理：启动自动加载前记录「正在加载的源」并标记「尚未存活」；仅当主页稳定运行 STABLE_MS 后才标记「已存活」。
 * 若上次启动在加载某源时进程被杀（SIGKILL 无法被 Java 捕获），该「未存活」标记保持，下次启动跳过对该源的自动加载。
 */
public class CrashGuard {

    private static final String NAME = "crash_guard";
    private static final String KEY_ALIVE = "home_alive";
    private static final String KEY_LAST_URL = "vod_last_url";
    private static final long STABLE_MS = 8000;

    private static SharedPreferences sp() {
        return App.get().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 记录即将加载的源，并标记为「尚未存活」。若加载/渲染过程中进程被杀，该标记保持。 */
    public static void recordLoading(String url) {
        sp().edit().putString(KEY_LAST_URL, url == null ? "" : url).putBoolean(KEY_ALIVE, false).commit();
    }

    /** 主页稳定运行后调用：标记已存活，解除对该源的跳过。 */
    public static void markAlive() {
        sp().edit().putBoolean(KEY_ALIVE, true).commit();
    }

    /** 上次启动是否在与当前默认源相同的源上崩溃过。 */
    public static boolean crashedLastLaunch(String currentUrl) {
        boolean alive = sp().getBoolean(KEY_ALIVE, true);
        String last = sp().getString(KEY_LAST_URL, "");
        return !alive && !last.isEmpty() && last.equals(currentUrl);
    }

    public static long stableMillis() {
        return STABLE_MS;
    }
}
