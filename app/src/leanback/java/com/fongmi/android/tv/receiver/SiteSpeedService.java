package com.fongmi.android.tv.receiver;

import android.app.Service;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Url;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.dialog.RecommendDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 调试用：遍历「推荐源列表」全部源，逐站点测速（搜索 + 播放全链路）。
 *
 * 为什么是 Service 而不是在 BroadcastReceiver 里干：系统给 goAsync 广播只有 ~60s 上限，
 * 站点测速要串行几百个站（远超 60s），在广播里跑会被 ANR 杀进程。改为 Receiver 仅
 * startService() 立即返回，本 Service 在 App 进程内长期运行、只打 logcat（tag=SiteSpeed），
 * PC 端 `adb logcat -s SiteSpeed` 流式落盘后分析。
 *
 * 多源遍历：通过 RecommendDialog.getSources() 拿到全部推荐源（种子 + qist 动态），
 * 对每个源 URL 在 App 内 VodConfig.get().load() 异步加载（等待就绪 / 超时跳过），
 * 加载完成后读取该源的 sites 逐站测速。detail/player 内部共用全局 Source 单例（非线程安全，
 * 且有状态读写），故站点间、源间必须串行（主循环顺序遍历 + 每站 join 超时）。
 *
 * 性能与稳定性（v2）：
 *  - 超时收紧：搜索 8s / 播放 10s（原 30s），慢站及早放弃，整体耗时大幅下降。
 *  - 播放降采样：每个源只抽样探测前 PLAY_SAMPLE 个「有内容」站的播放（detail+player 最重，
 *    含 spider 解析直链），其余站只测搜索。搜索已能反映源可用性。
 *  - OOM 防护：每源结束 Runtime.gc() 提示回收 spider/jar 相关对象；站间微延迟给 GC 喘息。
 *    原每站 new Thread 的泄漏已存在，但串行下同时最多 1 个泄漏线程，配合调用数下降可控。
 *  - 顶层 try/finally：任何异常（含 OOM 前的链路异常）都打 SITESPEED_DONE 并 stopSelf，
 *    避免无 DONE 或 Service 异常挂起。
 *
 * 触发：adb shell am broadcast -n <pkg>/com.fongmi.android.tv.receiver.SiteSpeedReceiver -a com.fongmi.android.tv.action.SITESPEED
 * 完成标志：logcat 出现 "SITESPEED_DONE sources=N totalSites=M testedSites=K"
 */
public class SiteSpeedService extends Service {

    public static final String ACTION = "com.fongmi.android.tv.action.SITESPEED";
    private static final String TAG = "SiteSpeed";
    private static final String KEYWORD = "爱情";        // 通用搜索词，各源基本都有分类结果
    private static final long SEARCH_TIMEOUT_MS = 8_000;  // 搜索接口硬超时（收紧：原30s）
    private static final long PLAY_TIMEOUT_MS = 10_000;   // 播放链路硬超时（收紧：原30s）
    private static final long LOAD_TIMEOUT_MS = 300_000; // 单源配置加载（含 jar 下载）硬超时；超时跳过该源继续遍历（300s 救回南风等冷加载慢源）
    private static final int PLAY_SAMPLE = 5;             // 每个源只抽样探测前 N 个「有内容」站的播放
    private static final int MAX_SITES_PER_SOURCE = 60;   // 单源最多实测站点数，超出整站跳过（防巨型 drpy 源压垮电视触发 LMK）
    private static final long SITE_GAP_MS = 50;           // 站间微延迟，给 GC 喘息，降低 OOM 风险

    // v3：主线程 Looper 驱动 + 每站 postDelayed 让出队列，避免主线程紧循环触发 ANR
    // （CatVod 的源加载/解析绑在主线程 Looper，工作线程紧循环会卡死主线程被系统强杀）
    // v4：loadSource 改到独立 worker 线程跑 VodConfig.load（避免主线程被冷加载 jar 卡死），
    //     并加 watchdog 超时（LOAD_TIMEOUT_MS）——加载超时才跳过该源继续遍历，根治"卡一站死全跑"；
    //     onStartCommand 加 mRunning 去重，避免 Service 被系统重启导致双链并发跑同一源。
    private static final int NOTIF_ID = 1;
    private static final String CHANNEL = "sitespeed";
    private Handler mHandler;
    private List<Config> mSources;
    private int mSrcIdx;
    private List<Site> mSites;
    private int mSiteIdx;
    private int mIdx;
    private int mPlaySampled;
    private int mSiteInSrc;
    private int mTotalSites;
    private int mTestedSites;
    private final AtomicBoolean mRunning = new AtomicBoolean(false); // onStartCommand 去重

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mRunning.get()) {
            Log.i(TAG, "SITESPEED_ALREADY_RUNNING skip re-entrant onStartCommand");
            return START_NOT_STICKY;
        }
        mRunning.set(true);
        Log.i(TAG, "SITESPEED_SERVICE_START");
        startForegroundSafe();
        mHandler = new Handler(Looper.getMainLooper());
        mHandler.post(this::beginTest);
        return START_NOT_STICKY;
    }

    // 主线程 Looper 驱动 + 每站 postDelayed 让出队列：
    // CatVod 的源加载/解析绑在主线程 Looper，若在工作线程紧循环调用会卡死主线程被 ANR 强杀。
    // 改为在主线程上「一次测一站、让出 looper、再测下一站」，对齐 CatVod 自身加载写法，避免 ANR。
    private void beginTest() {
        mSources = RecommendDialog.getSources();
        mSrcIdx = 0;
        mTotalSites = 0;
        mTestedSites = 0;
        Log.i(TAG, "SITESPEED_TOTAL_SOURCES " + (mSources != null ? mSources.size() : 0));
        nextSource();
    }

    private void nextSource() {
        if (mSources == null || mSrcIdx >= mSources.size()) {
            finishTest();
            return;
        }
        Config src = mSources.get(mSrcIdx);
        final String srcName = safe(src.getName());
        final String srcUrl = src.getUrl();
        Log.i(TAG, String.format("SRC_START | %s | %s", srcName, srcUrl));
        loadSource(src, LOAD_TIMEOUT_MS, new LoadCb() {
            @Override
            public void onOk() {
                mSites = new ArrayList<>(VodConfig.get().getSites());
                Log.i(TAG, String.format("SRC_SITES | %s | %d", srcName, mSites.size()));
                mSiteIdx = 0;
                mIdx = 0;
                mPlaySampled = 0;
                mSiteInSrc = 0;
                post(() -> nextSite(srcName, srcUrl));
            }

            @Override
            public void onFail() {
                Log.i(TAG, String.format("SRC_FAIL | %s | %s | load timeout/error", srcName, srcUrl));
                mSrcIdx++;
                post(() -> nextSource());
            }
        });
    }

    private void nextSite(final String srcName, final String srcUrl) {
        if (mSites == null || mSiteIdx >= mSites.size()) {
            mTotalSites += (mSites != null ? mSites.size() : 0);
            Runtime.getRuntime().gc();
            mSrcIdx++;
            post(() -> nextSource());
            return;
        }
        Site site = mSites.get(mSiteIdx);
        mSiteIdx++;
        if (site.isHide()) {
            post(() -> nextSite(srcName, srcUrl));
            return;
        }
        if (mSiteInSrc >= MAX_SITES_PER_SOURCE) {
            mTestedSites++;
            mSiteInSrc++;
            post(() -> nextSite(srcName, srcUrl));
            return;
        }
        mSiteInSrc++;
        final int idx = mIdx++;
        boolean allowPlay = mPlaySampled < PLAY_SAMPLE;
        testSiteAsync(site, idx, srcName, srcUrl, allowPlay, new ResultCb() {
            @Override
            public void onResult(Measure m) {
                mPlaySampled += m.didPlay;
                mTestedSites++;
                postDelayed(() -> nextSite(srcName, srcUrl), SITE_GAP_MS);
            }
        });
    }

    // 在独立线程跑单站测速（含 withTimeout 守护线程），完成后回主线程投递结果并排下一站。
    private void testSiteAsync(final Site site, final int idx, final String srcName,
                                final String srcUrl, final boolean allowPlay, final ResultCb cb) {
        new Thread(() -> {
            final Measure m = testSite(site, idx, srcName, srcUrl, allowPlay);
            if (mHandler != null) mHandler.post(() -> cb.onResult(m));
        }).start();
    }

    // 加载指定源配置（含 spider/jar 下载解析）。关键：VodConfig.load 在盒子网络差/存储临界时
    // 可能长时间阻塞甚至永不回调（此前因此卡死整轮测速），故放到独立 worker 线程执行，
    // 并由独立 watchdog 线程计时——超时（或 worker 卡死）即跳过该源、继续遍历，绝不让一站拖垮全局。
    // 主线程全程只负责编排（post/postDelayed），不被加载阻塞，ANR 风险消除。
    private void loadSource(Config cfg, long timeoutMs, final LoadCb cb) {
        final AtomicBoolean done = new AtomicBoolean(false);
        final String name = safe(cfg.getName());
        final Runnable fireOk = () -> {
            if (done.compareAndSet(false, true)) post(() -> cb.onOk());
        };
        final Runnable fireFail = () -> {
            if (done.compareAndSet(false, true)) {
                Log.i(TAG, String.format("SRC_LOAD_FAIL | %s", name));
                post(() -> cb.onFail());
            }
        };
        // worker：实际执行阻塞式 VodConfig.load（冷加载 jar/spider 可能很慢或失败）
        Thread worker = new Thread(() -> VodConfig.get().load(cfg, new Callback() {
            @Override
            public void start() {
            }

            @Override
            public void success() {
                fireOk.run();
            }

            @Override
            public void error(String msg) {
                fireFail.run();
            }
        }));
        worker.setDaemon(true);
        worker.start();
        // watchdog：独立线程计时，即便 worker 在同步 load 中卡死也能触发跳过
        Thread watch = new Thread(() -> {
            try {
                Thread.sleep(timeoutMs);
            } catch (InterruptedException ignored) {
            }
            if (done.compareAndSet(false, true)) {
                Log.i(TAG, String.format("SRC_LOAD_TIMEOUT | %s | >%dms", name, timeoutMs));
                post(() -> cb.onFail());
            }
        });
        watch.setDaemon(true);
        watch.start();
    }

    private void finishTest() {
        mRunning.set(false);
        Log.i(TAG, String.format("SITESPEED_DONE sources=%d totalSites=%d testedSites=%d",
                mSources != null ? mSources.size() : 0, mTotalSites, mTestedSites));
        stopForeground(true);
        stopSelf();
    }

    private void post(Runnable r) {
        if (mHandler != null) mHandler.post(r);
    }

    private void postDelayed(Runnable r, long ms) {
        if (mHandler != null) mHandler.postDelayed(r, ms);
    }

    private void startForegroundSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "源测速",
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            n = new Notification.Builder(this, CHANNEL)
                    .setContentTitle("TVbox 源测速中…")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("TVbox 源测速中…")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setOngoing(true)
                    .build();
        }
        startForeground(NOTIF_ID, n);
    }

    private static final class Measure {
        int sMs = -1, sCnt = 0;
        boolean sOk = false;
        String sErr = "";
        List<Vod> searchList;
        int pMs = -1;
        boolean pOk = false, pSkip = false;
        String pErr = "";
        int didPlay = 0;
    }

    private interface LoadCb {
        void onOk();

        void onFail();
    }

    private interface ResultCb {
        void onResult(Measure m);
    }

    // 在独立守护线程里跑阻塞调用，超时即抛 TimeoutException 并尽力打断底层线程，
    // 防止某个站的网络/spider 永久阻塞拖垮整个遍历。底层若不响应 interrupt 会泄漏线程，
    // 但遍历本身绝不卡死（调试用途可接受）。串行调用下同时最多 1 个泄漏线程。
    private <T> T withTimeout(Callable<T> task, long ms) throws Exception {
        FutureTask<T> ft = new FutureTask<>(task);
        Thread th = new Thread(ft);
        th.setDaemon(true);
        th.start();
        try {
            return ft.get(ms, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            th.interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        } finally {
            th.interrupt();
        }
    }

    // 返回 Measure（含本次是否探测了播放 didPlay=1）
    private Measure testSite(Site site, int idx, String srcName, String srcUrl, boolean allowPlay) {
        Measure m = new Measure();
        String key = site.getKey();
        String name = safe(site.getName());

        // ---- 搜索接口 ----
        long t0 = System.currentTimeMillis();
        try {
            Result search = withTimeout(() -> SiteApi.searchContent(site, KEYWORD, false, "1"), SEARCH_TIMEOUT_MS);
            m.sMs = (int) (System.currentTimeMillis() - t0);
            m.searchList = search.getList();
            m.sCnt = m.searchList != null ? m.searchList.size() : 0;
            m.sOk = true;
        } catch (TimeoutException e) {
            m.sMs = (int) SEARCH_TIMEOUT_MS;
            m.sErr = "timeout";
        } catch (Exception e) {
            m.sMs = (int) (System.currentTimeMillis() - t0);
            m.sErr = e.getMessage() == null ? "unknown" : e.getMessage();
        }

        // ---- 播放接口（search→detail→player）----
        if (allowPlay && m.sOk && m.sCnt > 0 && m.searchList != null && !m.searchList.isEmpty()) {
            long t1 = System.currentTimeMillis();
            try {
                final List<Vod> searchList = m.searchList;
                final String vodId = searchList.get(0).getId();
                Boolean playOk = withTimeout(() -> {
                    Vod detail = SiteApi.detailContent(site.getKey(), vodId).getVod();
                    detail.setFlags();
                    List<Flag> flags = detail.getFlags();
                    if (flags == null || flags.isEmpty()) return Boolean.FALSE;
                    Flag f0 = flags.get(0);
                    List<Episode> eps = f0.getEpisodes();
                    if (eps == null || eps.isEmpty()) return Boolean.FALSE;
                    Result player = SiteApi.playerContent(site.getKey(), f0.getFlag(), eps.get(0).getUrl());
                    Url url = player != null ? player.getUrl() : null;
                    return url != null && !url.isEmpty() && url.v() != null && !url.v().isEmpty();
                }, PLAY_TIMEOUT_MS);
                m.pMs = (int) (System.currentTimeMillis() - t1);
                if (Boolean.FALSE.equals(playOk)) {
                    m.pSkip = true; // 该站无可用播放线路（非线性卡死）
                } else if (playOk != null && playOk) {
                    m.pOk = true;
                } else {
                    m.pSkip = true; // 兜底（正常分支不会到这）
                }
                m.didPlay = 1;
            } catch (TimeoutException e) {
                m.pMs = (int) PLAY_TIMEOUT_MS;
                m.pErr = "timeout";
                m.didPlay = 1;
            } catch (Exception e) {
                m.pMs = (int) (System.currentTimeMillis() - t1);
                m.pErr = e.getMessage() == null ? "unknown" : e.getMessage();
                m.didPlay = 1;
            }
        } else {
            m.pSkip = true; // 搜索失败/无结果/降采样未抽中 -> 不测播放
        }

        // 错误信息里可能含 '|'，替换掉避免破坏 PC 端字段分隔
        m.sErr = m.sErr.replace('|', '/');
        m.pErr = m.pErr.replace('|', '/');
        Log.i(TAG, String.format("[%d] %s | %s | %s | sOk=%d | sMs=%d | sCnt=%d | pOk=%d | pMs=%d | pSkip=%d | sErr=%s | pErr=%s",
                idx, srcName, key, name, m.sOk ? 1 : 0, m.sMs, m.sCnt, m.pOk ? 1 : 0, m.pMs, m.pSkip ? 1 : 0, m.sErr, m.pErr));
        return m;
    }


    private String safe(String s) {
        return s == null ? "" : s.replace('|', '/');
    }
}
