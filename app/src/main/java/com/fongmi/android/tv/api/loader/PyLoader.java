package com.fongmi.android.tv.api.loader;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext) {
        // Chaquopy (Python) runtime is disabled on minSdk 23 builds because Chaquopy
        // requires minSdk >= 24. Python-based parsers fall back to SpiderNull; JS/native
        // parsers in :catvod and :quickjs are unaffected.
        return new SpiderNull();
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        return null;
    }
}
