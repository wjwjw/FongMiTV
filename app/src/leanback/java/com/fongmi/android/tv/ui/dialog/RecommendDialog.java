package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import android.content.Context;
import android.content.SharedPreferences;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.DialogHistoryBinding;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.ui.adapter.RecommendAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.MirrorUtil;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Response;

public class RecommendDialog extends BaseAlertDialog implements RecommendAdapter.OnClickListener {

    // qist/tvbox 仓库根目录的 json 配置文件清单（GitHub 文件列表接口）
    private static final String API_URL = "https://api.github.com/repos/qist/tvbox/contents/";
    private static final String API_MIRROR = "https://ghproxy.net/https://api.github.com/repos/qist/tvbox/contents/";

    // 进程级缓存：整个 App 生命周期内，设置界面首次进入时拉取一次
    private static List<Config> sRemote;
    private static boolean sFetched;
    private static boolean sFetching;
    private static WeakReference<RecommendDialog> sActive;

    private static final Map<String, String> NAME_MAP = new HashMap<>();

    static {
        NAME_MAP.put("0707.json", "OK影视多线配置");
        NAME_MAP.put("0821.json", "大而全配置");
        NAME_MAP.put("0825.json", "小而精配置");
        NAME_MAP.put("0826.json", "饭太硬配置");
        NAME_MAP.put("0827.json", "FongMi配置");
        NAME_MAP.put("fty.json", "饭太硬接口");
        NAME_MAP.put("js.json", "道长DRPY配置");
        NAME_MAP.put("jsm.json", "家庭电视合集");
        NAME_MAP.put("xyq.json", "香雅情XYQ");
        NAME_MAP.put("dianshi.json", "电视配置");
        NAME_MAP.put("367.json", "367配置");
        NAME_MAP.put("9918.json", "9918配置");
        NAME_MAP.put("99188.json", "99188配置");
    }

    private DialogHistoryBinding binding;
    private RecommendAdapter adapter;
    private List<Config> seedItems;
    private final Map<String, Long> mMeasured = new HashMap<>(); // normalized url -> 延迟ms；null=失败/未测

    public static RecommendDialog create() {
        return new RecommendDialog();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    /**
     * 设置界面首次进入时调用：尝试从 qist/tvbox 仓库拉取最新推荐源清单。
     * 整个进程生命周期只拉取一次；拉取失败则静默回退到本地静态清单。
     */
    public static void prefetch() {
        if (sFetched || sFetching) return;
        sFetching = true;
        Task.execute(() -> {
            try {
                List<Config> remote = fetchRepo();
                if (remote != null && !remote.isEmpty()) sRemote = remote;
            } catch (Throwable ignore) {
            } finally {
                sFetched = true;
                sFetching = false;
                App.post(() -> {
                    RecommendDialog d = sActive == null ? null : sActive.get();
                    if (d != null && d.isAdded() && d.adapter != null) {
                        d.adapter.setItems(d.currentItems());
                        d.relocate();
                        d.startMeasure();
                    }
                });
            }
        });
    }

    private static List<Config> fetchRepo() {
        String json = getText(API_URL);
        if (json == null) json = getText(API_MIRROR);
        if (json == null) return null;
        RepoEntry[] entries = App.gson().fromJson(json, RepoEntry[].class);
        if (entries == null) return null;
        List<Config> out = new ArrayList<>();
        for (RepoEntry e : entries) {
            if (e == null || !"file".equals(e.type) || e.name == null) continue;
            if (!e.name.toLowerCase().endsWith(".json") || e.download_url == null) continue;
            String url = e.download_url.replace(MirrorUtil.RAW_QIST, MirrorUtil.MIRROR_QIST);
            String name = NAME_MAP.getOrDefault(e.name.toLowerCase(), e.name.replaceAll("(?i)\\.json$", ""));
            out.add(new Config().type(0).name(name).url(url));
        }
        return out;
    }

    private static String getText(String url) {
        try (Response res = OkHttp.newCall(url, url).execute()) {
            return res.isSuccessful() ? res.body().string() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogHistoryBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.recommend_title).setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        seedItems = buildSeed(requireContext());
        adapter = new RecommendAdapter(this);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(false);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(1, 16));
        binding.recycler.setAdapter(adapter.setItems(currentItems()));
        sActive = new WeakReference<>(this);
        relocate();
        // 先拉取远程清单；清单回来后再统一测速，避免与 GitHub API 请求抢同一根网络导致测速值虚高。
        // 若本进程已拉取过（sFetched），直接就地测速。
        if (sFetched) startMeasure();
        else prefetch();
    }

    /** 打开列表后，后台并发对每个源 URL 测响应耗时；全部测完后按真实延迟升序重排（失败/未测排末尾）。 */
    private void startMeasure() {
        List<Config> items = currentItems();
        if (items.isEmpty()) return;
        restoreMeasured();                 // 先载入上次实测，冷启动即出稳定序（不闪烁）
        if (!mMeasured.isEmpty()) reorderBySpeed();
        final int total = items.size();
        final AtomicInteger done = new AtomicInteger(0);
        for (Config item : items) {
            String url = item.getUrl();
            Task.execute(() -> measure(url, total, done));
        }
    }

    private void measure(String url, int total, AtomicInteger done) {
        long start = System.currentTimeMillis();
        Long ms = null;
        try (Response res = OkHttp.newCall(url, "measure").execute()) {
            if (res.isSuccessful()) ms = System.currentTimeMillis() - start;
        } catch (Exception e) {
            ms = null;
        }
        final Long finalMs = ms;
        App.post(() -> {
            mMeasured.put(normalize(url), finalMs);
            persistMeasured(url, finalMs);
            RecommendDialog d = sActive == null ? null : sActive.get();
            if (d != null && d.isAdded() && d.adapter != null) {
                d.adapter.setSpeed(url, finalMs == null ? getString(R.string.recommend_speed_fail) : finalMs + " ms");
            }
            if (done.incrementAndGet() >= total) reorderBySpeed();
        });
    }

    /** 全部测速完成后按设备真实延迟升序重排（稳定排序，失败/未测落末尾）。 */
    private void reorderBySpeed() {
        List<Config> items = new ArrayList<>(currentItems());
        Collections.sort(items, (a, b) -> {
            Long ma = mMeasured.get(normalize(a.getUrl()));
            Long mb = mMeasured.get(normalize(b.getUrl()));
            long va = ma == null ? Long.MAX_VALUE : ma;
            long vb = mb == null ? Long.MAX_VALUE : mb;
            return Long.compare(va, vb);
        });
        adapter.setItems(items);
        // setItems 不清除 speeds 映射，标签按新位置自动跟随；此处仅确保全部回填
        for (Config c : items) {
            Long m = mMeasured.get(normalize(c.getUrl()));
            adapter.setSpeed(c.getUrl(), m == null ? getString(R.string.recommend_speed_fail) : m + " ms");
        }
        relocate();
    }

    /** 从本地 Prefs 载入上次实测延迟，作为冷启动稳定序兜底（不阻塞、不闪烁）。 */
    private void restoreMeasured() {
        SharedPreferences sp = App.get().getSharedPreferences("recommend_speed", 0);
        for (Config c : currentItems()) {
            long v = sp.getLong("ms_" + normalize(c.getUrl()), -1);
            if (v >= 0) mMeasured.put(normalize(c.getUrl()), v);
        }
    }

    /** 持久化单源实测延迟；失败(null)不写，避免污染历史最优。 */
    private void persistMeasured(String url, Long ms) {
        if (ms == null) return;
        App.get().getSharedPreferences("recommend_speed", 0)
                .edit().putLong("ms_" + normalize(url), ms).apply();
    }

    /** 滚动并高亮当前正在使用的源。 */
    private void relocate() {
        String current = normalize(VodConfig.get().getConfig().getUrl());
        adapter.setCurrentUrl(current);
        int pos = indexOfUrl(current);
        if (pos >= 0) {
            int p = pos;
            binding.recycler.post(() -> binding.recycler.scrollToPosition(p));
        }
    }

    private int indexOfUrl(String url) {
        List<Config> items = currentItems();
        for (int i = 0; i < items.size(); i++) {
            if (normalize(items.get(i).getUrl()).equals(url)) return i;
        }
        return -1;
    }

    private List<Config> currentItems() {
        return merge(seedItems);
    }

    /** 种子 + 动态去重合并（保持种子顺序，动态源追加在后）。seed 可为实例字段或 Application 资源重建。 */
    private static List<Config> merge(List<Config> seed) {
        if (sRemote == null || sRemote.isEmpty()) return new ArrayList<>(seed);
        Set<String> seen = new HashSet<>();
        List<Config> out = new ArrayList<>(seed);
        for (Config s : seed) seen.add(normalize(s.getUrl()));
        for (Config r : sRemote) {
            if (seen.add(normalize(r.getUrl()))) out.add(r);
        }
        return out;
    }

    /** 归一化：trim + 小写 + 还原镜像前缀 + qist 镜像替换，供去重（见 MirrorUtil）。 */
    private static String normalize(String url) {
        return MirrorUtil.normalize(url);
    }

    private static List<Config> buildSeed(Context ctx) {
        List<Config> items = new ArrayList<>();
        for (String entry : ctx.getResources().getStringArray(R.array.recommend_vod)) {
            String[] parts = entry.split("\\|", 2);
            if (parts.length == 2) items.add(new Config().type(0).name(parts[0].trim()).url(parts[1].trim()));
        }
        return items;
    }

    /**
     * 暴露给 SiteSpeedService：返回全部推荐源（种子 + qist 动态，已去重）。
     * 若动态清单尚未拉取，则同步拉取一次（调用方在独立线程，可阻塞）。
     */
    public static List<Config> getSources() {
        if (!sFetched && !sFetching) {
            try {
                List<Config> r = fetchRepo();
                if (r != null && !r.isEmpty()) sRemote = r;
            } catch (Throwable ignore) {
            }
            sFetched = true;
        }
        // 静态上下文：无实例 seedItems，用 Application 资源重建种子，再走同一套去重+排序逻辑
        return new ArrayList<>(merge(buildSeed(App.get())));
    }

    @Override
    public void onClick(Config item) {
        ((ConfigListener) requireActivity()).setConfig(Config.find(item.getUrl(), item.getName(), 0));
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (adapter.getItemCount() == 0) dismiss();
        else setWidth(0.5f);
    }

    @Override
    public void onDestroy() {
        if (sActive != null && sActive.get() == this) sActive.clear();
        super.onDestroy();
    }

    private static class RepoEntry {
        String name;
        String type;
        String download_url;
    }
}
