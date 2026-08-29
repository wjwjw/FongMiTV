package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;

/**
 * 崩溃安全守卫：防止「某个点播源在加载/渲染时触发 spider 自卫式 System.exit 自杀」导致应用永久无法进入设置。
 * 原理：启动自动加载前记录「正在加载的源」并标记「尚未存活」；仅当主页稳定运行 STABLE_MS 后才标记「已存活」。
 * 若上次启动在加载某源时进程被杀（SIGKILL 无法被 Java 捕获），该「未存活」标记保持，下次启动跳过对该源的自动加载。
 *
 * 自愈增强：守卫不再是永久锁。连续拦截达到 MAX_SKIP 次，或距首次拦截超过 TTL_MS，下次启动将强制重试自动加载，
 * 避免「源已修复 / 只是瞬时故障」却再也进不去主界面、且无法在 TV 上自救的死锁。
 * 同时功能栏（含设置）改为在 HomeActivity.setAdapter() 中同步填充，保证即使 COMMON 事件不来，设置按钮也始终可点。
 */
public class CrashGuard {

    private static final String NAME = "crash_guard";
    private static final String KEY_ALIVE = "home_alive";
    private static final String KEY_LAST_URL = "vod_last_url";
    private static final String KEY_COUNT = "guard_count";
    private static final String KEY_FIRST_TS = "guard_first_ts";
    private static final long STABLE_MS = 8000;
    /** 连续被守卫拦截的最大次数，超过则本次强制重试加载，避免永久锁死。 */
    private static final int MAX_SKIP = 2;
    /** 守卫首次拦截后多久自动过期（毫秒），过期后强制重试加载。默认 24 小时。 */
    private static final long TTL_MS = 24L * 60 * 60 * 1000;

    private static SharedPreferences sp() {
        return App.get().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 记录即将加载的源，并标记为「尚未存活」。若加载/渲染过程中进程被杀，该标记保持。 */
    public static void recordLoading(String url) {
        sp().edit()
                .putString(KEY_LAST_URL, url == null ? "" : url)
                .putBoolean(KEY_ALIVE, false)
                .putLong(KEY_COUNT, 0)
                .putLong(KEY_FIRST_TS, 0)
                .commit();
    }

    /** 主页稳定运行后调用：标记已存活，解除对该源的跳过，并清零拦截计数/起始时间。 */
    public static void markAlive() {
        sp().edit()
                .putBoolean(KEY_ALIVE, true)
                .putLong(KEY_COUNT, 0)
                .putLong(KEY_FIRST_TS, 0)
                .commit();
    }

    /** 上次启动是否在与当前默认源相同的源上崩溃过，且尚未超过重试上限 / 过期时间。 */
    public static boolean shouldSkip(String currentUrl) {
        boolean alive = sp().getBoolean(KEY_ALIVE, true);
        String last = sp().getString(KEY_LAST_URL, "");
        if (alive || last.isEmpty() || !last.equals(currentUrl)) return false;
        long count = sp().getLong(KEY_COUNT, 0);
        long first = sp().getLong(KEY_FIRST_TS, 0);
        long now = System.currentTimeMillis();
        if (first != 0 && now - first > TTL_MS) return false; // 已过期，强制重试
        return count < MAX_SKIP; // 未达拦截上限才继续拦截
    }

    /** 本次因守卫而跳过自动加载时调用：累加拦截次数并记录首次拦截时间（用于达到上限/过期后强制重试）。 */
    public static void noteSkipped() {
        SharedPreferences sp = sp();
        long first = sp.getLong(KEY_FIRST_TS, 0);
        if (first == 0) first = System.currentTimeMillis();
        long count = sp.getLong(KEY_COUNT, 0) + 1;
        sp.edit().putLong(KEY_COUNT, count).putLong(KEY_FIRST_TS, first).commit();
    }

    public static long stableMillis() {
        return STABLE_MS;
    }
}
