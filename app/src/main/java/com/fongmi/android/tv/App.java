package com.fongmi.android.tv;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.os.HandlerCompat;

import com.fongmi.android.tv.utils.Notify;
import com.fongmi.hook.Hook;
import com.github.catvod.Init;
import com.google.gson.Gson;

public class App extends Application implements Application.ActivityLifecycleCallbacks {

    private static final String REAL_PKG = "com.fongmi.android.tv";

    private static volatile App instance;
    /** catvod/spider 专用上下文，必须静态强引用（Init 内部为 WeakReference） */
    private static volatile Context catContext;

    private final Handler handler;
    private final Gson gson;
    private final long time;

    private Activity activity;
    private Hook hook;

    public App() {
        instance = this;
        gson = new Gson();
        time = System.currentTimeMillis();
        handler = HandlerCompat.createAsync(Looper.getMainLooper());
    }

    public static App get() {
        return instance;
    }

    public static Gson gson() {
        return get().gson;
    }

    public static long time() {
        return get().time;
    }

    public static Activity activity() {
        return get().activity;
    }

    public static void post(Runnable runnable) {
        get().handler.post(runnable);
    }

    public static void post(Runnable runnable, long delayMillis) {
        get().handler.removeCallbacks(runnable);
        if (delayMillis >= 0) get().handler.postDelayed(runnable, delayMillis);
    }

    public static void removeCallbacks(Runnable runnable) {
        get().handler.removeCallbacks(runnable);
    }

    public static void removeCallbacks(Runnable... runnable) {
        for (Runnable r : runnable) get().handler.removeCallbacks(r);
    }

    public void setHook(Hook hook) {
        this.hook = hook;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // 给 catvod / spider 传递一个「包名被伪装成官方 com.fongmi.android.tv」的上下文，
        // 以通过 spider 的签名/包名反篡改自检（debug 包的 .test 后缀会触发其 System.exit 自杀）。
        // App.getPackageName() 仍返回真实包名，不影响 FileProvider、PendingIntent 等。
        // 注意：Init 内部用 WeakReference 持有，这里必须用静态强引用保活，否则会被 GC 回收导致 NPE。
        // 必须返回一个 Application 子类实例：spider 的 Init.init 里直接 (Application) context 强转，
        // 单纯的 ContextWrapper 过不了强转（会抛 ClassCastException -> Init.context() 为 null ->
        // DexNative.<clinit> 空指针 -> 所有 *Guard spider 实例化失败 -> 搜索/首页无结果）。
        // SpiderContext 继承 Application，所有 Context 调用委托给真正的 App 实例（this），
        // 仅重写 getPackageName() 伪装官方包名以绕过 spider 反篡改自检（.test 后缀会触发其 System.exit 自杀）。
        catContext = new SpiderContext(this);
        Init.set(catContext);
    }

    /**
     * 伪装包名的 Application 子类：除 getPackageName() 返回官方包名外，其余全部委托给真正的 App 实例。
     * 必须 extends Application，否则 spider 的 (Application) context 强转会抛 ClassCastException。
     */
    private static final class SpiderContext extends Application {
        SpiderContext(Application app) {
            attachBaseContext(app);
        }

        @Override
        public String getPackageName() {
            return REAL_PKG;
        }
    }

    /**
     * 向 catvod/spider 报告官方包名的上下文（静态强引用，全局单例）。
     * 用于 spider.init / JarLoader.invokeInit，绕过 .test 后缀导致的反篡改自杀。
     */
    public static Context getCatContext() {
        return catContext != null ? catContext : get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notify.createChannel();
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public PackageManager getPackageManager() {
        return hook != null ? hook : getBaseContext().getPackageManager();
    }

    @Override
    public String getPackageName() {
        return hook != null ? hook.getPackageName() : getBaseContext().getPackageName();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        if (activity != activity()) this.activity = activity;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        if (activity == activity()) this.activity = null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }
}