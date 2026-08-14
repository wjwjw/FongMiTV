package com.fongmi.android.tv.utils;

import android.net.Uri;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GitHub 源镜像兜底工具：
 * 直连 raw.githubusercontent.com 等 GitHub 域名失败时，按候选列表依次换镜像前缀重试；
 * 镜像 URL 仅用于本次拉取，不落库（DB 始终保存原始 URL）。
 */
public class MirrorUtil {

    /** 镜像前缀（顺序即优先级）。注意 URL 保持 https:// 前缀，直接拼在原始 URL 前面。 */
    private static final String[] MIRROR_PREFIXES = {
            "https://ghproxy.net/https://",
            "https://gh.927223.xyz/https://"
    };

    /** 仅这些 GitHub 系域名值得走镜像，其它域名/本地协议原样返回。 */
    private static final Set<String> GITHUB_HOSTS = new HashSet<>(Arrays.asList(
            "raw.githubusercontent.com",
            "github.com",
            "gist.githubusercontent.com",
            "api.github.com"
    ));

    /** jsdelivr 多节点（cdn/fastly/gcore/testingcf）互相兜底。 */
    private static final String[] JSDELIVR_NODES = {
            "https://cdn.jsdelivr.net/",
            "https://fastly.jsdelivr.net/",
            "https://gcore.jsdelivr.net/",
            "https://testingcf.jsdelivr.net/"
    };

    /** qist/tvbox 仓库专属镜像（历史约定，jsm/fty 等 13 个 json 走 qist.wyfc.qzz.io）。 */
    public static final String RAW_QIST = "raw.githubusercontent.com/qist/tvbox/master/";
    public static final String MIRROR_QIST = "qist.wyfc.qzz.io/";

    /** 进程级记忆：原始 URL → 上次成功拉取的镜像 URL。GitHub 被墙时省掉一次超时重试。 */
    private static final Map<String, String> sLastGood = new ConcurrentHashMap<>();

    /** 去掉 URL 上的镜像前缀，还原原始地址（防嵌套镜像、归一化去重）。 */
    public static String canonical(String url) {
        if (url == null) return "";
        String value = url.trim();
        for (String prefix : MIRROR_PREFIXES) {
            if (value.startsWith(prefix)) value = value.substring(prefix.length());
        }
        return value;
    }

    /**
     * 生成拉取候选列表：[上次成功镜像（若有）, 原始URL, 其余镜像…]。
     * 非 GitHub 系域名或非 http(s) 协议只返回原始 URL，不产生额外请求。
     */
    public static List<String> candidates(String url) {
        List<String> out = new ArrayList<>();
        String canon = canonical(url);
        if (canon.isEmpty()) return out;
        String scheme = scheme(canon);
        String host = host(canon);
        if (!("http".equals(scheme) || "https".equals(scheme))) return Arrays.asList(canon);
        if (host.startsWith("cdn.jsdelivr.net") || host.startsWith("fastly.jsdelivr.net")
                || host.startsWith("gcore.jsdelivr.net") || host.startsWith("testingcf.jsdelivr.net")) {
            return jsdelivrCandidates(canon);
        }
        if (!GITHUB_HOSTS.contains(host)) return Arrays.asList(canon);
        String last = sLastGood.get(canon);
        if (last != null && !last.equals(canon)) out.add(last);
        out.add(canon);
        for (String prefix : MIRROR_PREFIXES) {
            String mirror = prefix + canon;
            if (!out.contains(mirror)) out.add(mirror);
        }
        return out;
    }

    /** 归一化：trim + 小写 + 还原镜像前缀 + qist 仓库镜像替换，用于去重/测速表匹配。 */
    public static String normalize(String url) {
        if (url == null) return "";
        return canonical(url).trim().toLowerCase().replace(RAW_QIST, MIRROR_QIST);
    }

    public static void remember(String original, String used) {
        String canon = canonical(original);
        if (used == null || used.equals(canon)) sLastGood.remove(canon);
        else sLastGood.put(canon, used);
    }

    public static void forget(String original) {
        sLastGood.remove(canonical(original));
    }

    private static List<String> jsdelivrCandidates(String canon) {
        List<String> out = new ArrayList<>();
        out.add(canon);
        for (String node : JSDELIVR_NODES) {
            if (node.startsWith("https://") && canon.startsWith("https://")) {
                String path = canon.substring("https://".length());
                int idx = path.indexOf('/');
                if (idx < 0) continue;
                String mirror = node.substring(0, node.length() - 1) + path.substring(idx);
                if (!out.contains(mirror)) out.add(mirror);
            }
        }
        return out;
    }

    private static String scheme(String url) {
        Uri uri = Uri.parse(url);
        return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase().trim();
    }

    private static String host(String url) {
        Uri uri = Uri.parse(url);
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase().trim();
    }
}
