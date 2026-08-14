package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.Util;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import android.util.Base64;
import dalvik.system.DexClassLoader;

public class BaseLoader {

    private final JarLoader jarLoader;
    private final PyLoader pyLoader;
    private final JsLoader jsLoader;

    private BaseLoader() {
        jarLoader = new JarLoader();
        pyLoader = new PyLoader();
        jsLoader = new JsLoader();
    }

    public static BaseLoader get() {
        return Loader.INSTANCE;
    }

    private static boolean isJs(String api) {
        return api.contains(".js");
    }

    private static boolean isPy(String api) {
        return api.contains(".py");
    }

    private static boolean isCsp(String api) {
        return api.startsWith("csp_");
    }

    // 网盘家族启发式识别：站点名 / api / ext 出现云盘标记或明确网盘关键词即判定。
    // ext 常为 base64(JSON)，内含 "Cloud-drive" 等 flag，故二次 base64 解码再校验一次。
    // 注意：这是安全取向的启发式，可能漏判少数把标记藏进 base64 嵌套的站；误拦正常站可关全局开关。
    private static final String[] CLOUD_KEYWORDS = {
            "cloud-drive", "quark", "aliyun", "alipan", "115", "baidu", "tianyi",
            "网盘", "云盘", "夸克", "阿里"
    };

    public static boolean isCloudDriveSite(String name, String api, String ext) {
        if (!Setting.isDisableCloudDrive()) return false;
        String hay = ((name == null ? "" : name) + "|" + (api == null ? "" : api) + "|" + (ext == null ? "" : ext)).toLowerCase();
        for (String kw : CLOUD_KEYWORDS) {
            if (hay.contains(kw)) return true;
        }
        String decoded = tryDecodeBase64(ext);
        if (decoded != null) {
            String d = decoded.toLowerCase();
            for (String kw : CLOUD_KEYWORDS) {
                if (d.contains(kw)) return true;
            }
        }
        return false;
    }

    private static String tryDecodeBase64(String s) {
        if (s == null || s.isEmpty() || s.length() % 4 != 0) return null;
        try {
            byte[] bytes = Base64.decode(s, Base64.DEFAULT);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public void clear() {
        Task.execute(() -> {
            jarLoader.clear();
            pyLoader.clear();
            jsLoader.clear();
        });
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        if (isCloudDriveSite(null, api, ext)) return new SpiderNull();
        if (isPy(api)) return pyLoader.getSpider(key, api, ext);
        else if (isJs(api)) return jsLoader.getSpider(key, api, ext, jar);
        else if (isCsp(api)) return jarLoader.getSpider(key, api, ext, jar);
        else return new SpiderNull();
    }

    public Spider getSpider(String key) {
        Site site = VodConfig.get().getSite(key);
        Live live = LiveConfig.get().getLive(key);
        if (!site.isEmpty()) return site.spider();
        if (!live.isEmpty()) return live.spider();
        return new SpiderNull();
    }

    public void setRecent(String key, String api, String jar) {
        if (isJs(api)) jsLoader.setRecent(key);
        else if (isPy(api)) pyLoader.setRecent(key);
        else if (isCsp(api)) jarLoader.setRecent(Util.md5(jar));
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (params.containsKey("siteKey")) return getSpider(params.get("siteKey")).proxy(params);
        if ("js".equals(params.get("do"))) return jsLoader.proxy(params);
        if ("py".equals(params.get("do"))) return pyLoader.proxy(params);
        return jarLoader.proxy(params);
    }

    public void parseJar(String jar, boolean recent) {
        if (TextUtils.isEmpty(jar)) return;
        String key = Util.md5(jar);
        jarLoader.parseJar(key, jar);
        if (recent) jarLoader.setRecent(key);
    }

    public DexClassLoader dex(String jar) {
        return jarLoader.dex(jar);
    }

    public JSONObject jsonExt(String key, LinkedHashMap<String, String> jxs, String url) throws Throwable {
        return jarLoader.jsonExt(key, jxs, url);
    }

    public JSONObject jsonExtMix(String flag, String key, String name, LinkedHashMap<String, HashMap<String, String>> jxs, String url) throws Throwable {
        return jarLoader.jsonExtMix(flag, key, name, jxs, url);
    }

    private static class Loader {
        static volatile BaseLoader INSTANCE = new BaseLoader();
    }
}
