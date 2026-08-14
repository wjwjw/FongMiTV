package com.fongmi.android.tv.api.config;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.Decoder;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Depot;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Rule;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.utils.CrashGuard;
import com.fongmi.android.tv.utils.MirrorUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.bean.Header;
import com.github.catvod.bean.Proxy;
import com.github.catvod.utils.Json;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class VodConfig extends BaseConfig {

    private static final String TAG = VodConfig.class.getSimpleName();

    private Site home;
    private String wall;
    private Parse parse;
    private List<Doh> doh;
    private List<Rule> rules;
    private List<Site> sites;
    private List<String> ads;
    private List<String> flags;
    private List<Parse> parses;

    public static VodConfig get() {
        return Loader.INSTANCE;
    }

    public static int getCid() {
        return get().getConfig().getId();
    }

    public static String getUrl() {
        return get().getConfig().getUrl();
    }

    public static String getDesc() {
        return get().getConfig().getDesc();
    }

    public static int getHomeIndex() {
        return get().getSites().indexOf(get().getHome());
    }

    public static boolean hasParse() {
        return !get().getParses().isEmpty();
    }

    public static void load(Config config, Callback callback) {
        CrashGuard.recordLoading(config.getUrl());
        get().clear().config(config).load(new Callback() {
            @Override
            public void success() {
                CrashGuard.markAlive();
                callback.success();
            }

            @Override
            public void error(String msg) {
                // 加载流程正常结束（网络错误等），非崩溃，允许下次重试
                CrashGuard.markAlive();
                callback.error(msg);
            }
        });
    }

    public VodConfig init() {
        return config(Config.vod());
    }

    public VodConfig config(Config config) {
        this.config = config;
        return this;
    }

    public VodConfig clear() {
        ads = null;
        doh = null;
        home = null;
        wall = null;
        parse = null;
        sites = null;
        flags = null;
        rules = null;
        parses = null;
        BaseLoader.get().clear();
        RuleConfig.get().invalidate();
        return this;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    @Override
    protected Config defaultConfig() {
        return Config.vod();
    }

    @Override
    protected void postEvent() {
        super.postEvent();
        ConfigEvent.vod();
    }

    @Override
    protected void load(Config config) throws Throwable {
        // GitHub 源镜像兜底：直连失败依次换镜像重试；成功后记忆镜像，下次同源先试镜像。
        // 镜像 URL 仅用于本次拉取与相对路径解析（working），DB 落库仍是原始 URL。
        Throwable last = null;
        for (String candidate : MirrorUtil.candidates(config.getUrl())) {
            try {
                String json = Decoder.getJson(UrlUtil.convert(candidate), TAG);
                Config working = candidate.equals(config.getUrl()) ? config : workingConfig(config, candidate);
                checkJson(working, Json.parse(json).getAsJsonObject());
                MirrorUtil.remember(config.getUrl(), candidate);
                return;
            } catch (Throwable e) {
                last = e;
                MirrorUtil.forget(config.getUrl());
                if (isCanceled(e)) throw e;
            }
        }
        throw last;
    }

    /** 镜像拉取用的临时配置：复制身份字段，仅替换 URL，使 resolveSpider 等相对路径解析到镜像域名。 */
    private Config workingConfig(Config config, String url) {
        Config working = new Config().type(config.getType()).url(url).name(config.getName());
        working.setHome(config.getHome());
        working.setParse(config.getParse());
        return working;
    }

    @Override
    protected boolean isLoaded() {
        return !getSites().isEmpty();
    }

    private void checkJson(Config config, JsonObject object) throws Throwable {
        if (object.has("msg")) {
            throw new Exception(object.get("msg").getAsString());
        } else if (object.has("urls")) {
            parseDepot(config, object);
        } else {
            parseConfig(config, object);
        }
    }

    private void parseDepot(Config config, JsonObject object) throws Throwable {
        List<Depot> items = Depot.arrayFrom(object.getAsJsonArray("urls").toString());
        List<Config> configs = new ArrayList<>();
        for (Depot item : items) configs.add(Config.find(item, VOD));
        if (configs.isEmpty()) throw new Exception("Depot urls is empty");
        load(this.config = configs.get(0));
        // 镜像兜底时 config 可能是镜像 URL，原始 depot 行也要一并清掉（保持直连时删除语义）
        Config.delete(config.getUrl());
        String canon = MirrorUtil.canonical(config.getUrl());
        if (!canon.equals(config.getUrl())) Config.delete(canon);
    }

    private void parseConfig(Config config, JsonObject object) {
        initList(object);
        initLive(config, object);
        initWall(config, object);
        initSite(config, object);
        initParse(config, object);
        config.setLogo(Json.safeString(object, "logo"));
        config.setNotice(Json.safeString(object, "notice"));
        config.setDanmaku(Json.safeString(object, "danmaku"));
    }

    private void initList(JsonObject object) {
        setHeaders(Header.arrayFrom(fetchArray(object, "headers")));
        setProxy(Proxy.arrayFrom(fetchArray(object, "proxy")));
        setRules(Rule.arrayFrom(fetchArray(object, "rules")));
        setDoh(Doh.arrayFrom(fetchArray(object, "doh")));
        setFlags(Json.safeListString(object, "flags"));
        setHosts(Json.safeListString(object, "hosts"));
        setAds(Json.safeListString(object, "ads"));
    }

    private void initLive(Config config, JsonObject object) {
        if (Json.isEmpty(object, "lives")) return;
        // 落库用原始 URL（非镜像 working URL）；镜像仅在本次加载重试时使用，DB/SP 始终保留源站直连地址
        String url = MirrorUtil.canonical(config.getUrl());
        Config temp = Config.find(url, config.getName(), LIVE).save();
        boolean sync = LiveConfig.get().needSync(url);
        if (sync) LiveConfig.get().config(temp.update()).parse(object);
    }

    private void initWall(Config config, JsonObject object) {
        if (Json.isEmpty(object, "wallpaper")) return;
        // 壁纸地址取自源 JSON（本就是原始地址）；若为相对路径则基于【原始】配置 base 解析成绝对地址，
        // 避免解析到镜像 working 域名、保证 DB 存原始 URL。
        this.wall = resolveWall(config, Json.safeString(object, "wallpaper"));
        Config temp = Config.find(wall, config.getName(), WALL).save();
        boolean sync = WallConfig.get().needSync(wall);
        if (sync) WallConfig.get().config(temp.update());
    }

    /** 壁纸相对路径基于原始配置 base 解析为绝对地址；绝对/本地/asset 路径原样返回。 */
    private String resolveWall(Config config, String wall) {
        if (wall.isEmpty() || wall.startsWith("http") || wall.startsWith("file") || wall.startsWith("assets")) {
            return wall;
        }
        return UrlUtil.resolve(UrlUtil.convert(MirrorUtil.canonical(config.getUrl())), wall);
    }

    private void initSite(Config config, JsonObject object) {
        String spider = resolveSpider(config, Json.safeString(object, "spider"));
        BaseLoader.get().parseJar(spider, true);
        // 兜底：即使某个站点自身声明了相对路径 jar，也按配置 base URL 解析成绝对 URL，
        // 否则搜索时 BaseLoader.getSpider 用相对路径算出的 key 与预加载（绝对 URL key）对不上 -> SpiderNull -> 无结果。
        setSites(Json.safeListElement(object, "sites").stream()
                .map(e -> Site.objectFrom(e, spider))
                .map(s -> { s.setJar(resolveSpider(config, s.getJar())); return s; })
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new)));
        Map<String, Site> items = Site.findAll().stream().collect(Collectors.toMap(Site::getKey, Function.identity()));
        getSites().forEach(site -> site.sync(items.get(site.getKey())));
        setHome(config, getSites().isEmpty() ? new Site() : getSites().stream().filter(item -> item.getKey().equals(config.getHome())).findFirst().orElse(getSites().get(0)), false);
    }

    // 配置里的 spider 多为相对路径（如 ./jar/fan.txt;md5;xxx），需相对配置 base URL 解析成绝对 URL，
    // 否则 JarLoader.parseJar 既不下载、也不会以正确 key（路径/URL 的 MD5）命中本地缓存，
    // 导致全局 spider 加载失败、所有 spider 站点回退 SpiderNull、搜索与详情均无结果。
    private String resolveSpider(Config config, String spider) {
        if (spider.isEmpty() || spider.startsWith("http") || spider.startsWith("file") || spider.startsWith("assets")) {
            return spider;
        }
        String base = UrlUtil.convert(config.getUrl());
        String[] parts = spider.split(";md5;", 2);
        String path = parts[0];
        String suffix = parts.length > 1 ? ";md5;" + parts[1] : "";
        return UrlUtil.resolve(base, path) + suffix;
    }

    private void initParse(Config config, JsonObject object) {
        setParses(Json.safeListElement(object, "parses").stream().map(Parse::objectFrom).distinct().collect(Collectors.toCollection(ArrayList::new)));
        setParse(config, getParses().isEmpty() ? new Parse() : getParses().stream().filter(item -> item.getName().equals(config.getParse())).findFirst().orElse(getParses().get(0)), false);
    }

    public List<Site> getSites() {
        return sites == null ? Collections.emptyList() : sites;
    }

    private void setSites(List<Site> sites) {
        this.sites = sites;
    }

    public List<Parse> getParses() {
        return parses == null ? Collections.emptyList() : parses;
    }

    private void setParses(List<Parse> parses) {
        if (!parses.isEmpty()) parses.add(0, Parse.god());
        this.parses = parses;
    }

    public List<Doh> getDoh() {
        List<Doh> items = Doh.get(App.get());
        if (doh == null) return items;
        items.removeAll(doh);
        items.addAll(doh);
        return items;
    }

    private void setDoh(List<Doh> doh) {
        this.doh = doh;
    }

    public List<Rule> getRules() {
        return rules == null ? Collections.emptyList() : rules;
    }

    private void setRules(List<Rule> rules) {
        this.rules = rules;
        RuleConfig.get().invalidate();
    }

    public List<Parse> getParses(int type) {
        return getParses().stream().filter(item -> item.getType() == type).toList();
    }

    public List<Parse> getParses(int type, String flag) {
        List<Parse> items = getParses(type);
        List<Parse> filter = items.stream().filter(item -> item.getExt().getFlag().contains(flag)).toList();
        return filter.isEmpty() ? items : filter;
    }

    public List<String> getFlags() {
        return flags == null ? Collections.emptyList() : flags;
    }

    private void setFlags(List<String> flags) {
        this.flags = flags;
    }

    public List<String> getAds() {
        return ads == null ? Collections.emptyList() : ads;
    }

    private void setAds(List<String> ads) {
        this.ads = ads;
        RuleConfig.get().invalidate();
    }

    public Parse getParse() {
        return parse == null ? new Parse() : parse;
    }

    public void setParse(Parse parse) {
        setParse(getConfig(), parse, true);
    }

    public Site getHome() {
        return home == null ? new Site() : home;
    }

    public void setHome(Site site) {
        setHome(getConfig(), site, true);
        RefreshEvent.home();
    }

    public String getWall() {
        return TextUtils.isEmpty(wall) ? "" : wall;
    }

    public Parse getParse(String name) {
        return getParses().stream().filter(item -> item.getName().equals(name)).findFirst().orElse(new Parse());
    }

    public Site getSite(String key) {
        return getSites().stream().filter(item -> item.getKey().equals(key)).findFirst().orElse(new Site());
    }

    private void setParse(Config config, Parse parse, boolean save) {
        this.parse = parse;
        this.parse.setSelected(true);
        config.setParse(parse.getName());
        getParses().forEach(item -> item.setSelected(parse));
        if (save) config.save();
    }

    private void setHome(Config config, Site site, boolean save) {
        home = site;
        home.setSelected(true);
        config.setHome(home.getKey());
        if (save) config.save();
        getSites().forEach(item -> item.setSelected(home));
    }

    private static class Loader {
        static volatile VodConfig INSTANCE = new VodConfig();
    }
}
