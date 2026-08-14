package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.Prefers;

public class Setting {

    private static final int MIN_WALL = 0;
    private static final int MAX_WALL = 4;
    private static final int MIN_WALL_TYPE = 0;
    private static final int MAX_WALL_TYPE = 2;
    private static final int MIN_SITE_MODE = 0;
    private static final int MAX_SITE_MODE = 1;
    private static final int MIN_SYNC_MODE = 0;
    private static final int MAX_SYNC_MODE = 2;

    public static String getSwitch(boolean value) {
        return ResUtil.getString(value ? R.string.setting_on : R.string.setting_off);
    }

    public static String getDoh() {
        return Prefers.getString("doh");
    }

    public static void putDoh(String doh) {
        Prefers.put("doh", doh);
    }

    public static String getUa() {
        return Prefers.getString("ua");
    }

    public static void putUa(String ua) {
        Prefers.put("ua", ua);
    }

    public static String getKeyword() {
        return Prefers.getString("keyword");
    }

    public static void putKeyword(String keyword) {
        Prefers.put("keyword", keyword);
    }

    public static String getHot() {
        return Prefers.getString("hot");
    }

    public static void putHot(String hot) {
        Prefers.put("hot", hot);
    }

    public static int getWall() {
        return Math.clamp(Prefers.getInt("wall", 1), MIN_WALL, MAX_WALL);
    }

    public static void putWall(int wall) {
        Prefers.put("wall", Math.clamp(wall, MIN_WALL, MAX_WALL));
    }

    public static int getWallType() {
        return Math.clamp(Prefers.getInt("wall_type", 0), MIN_WALL_TYPE, MAX_WALL_TYPE);
    }

    public static void putWallType(int type) {
        Prefers.put("wall_type", Math.clamp(type, MIN_WALL_TYPE, MAX_WALL_TYPE));
    }

    public static int getThemeColor() {
        return Prefers.getInt("theme_color", -1);
    }

    public static void putThemeColor(int color) {
        Prefers.put("theme_color", color);
    }

    public static int getWallColor() {
        return Prefers.getInt("wall_color", 0);
    }

    public static void putWallColor(int color) {
        Prefers.put("wall_color", color);
    }

    public static int getDynamicColor() {
        int color = getThemeColor();
        if (color == -1) return 0;
        return color != 0 ? color : getWallColor();
    }

    public static int getSiteMode() {
        return Math.clamp(Prefers.getInt("site_mode"), MIN_SITE_MODE, MAX_SITE_MODE);
    }

    public static void putSiteMode(int mode) {
        Prefers.put("site_mode", Math.clamp(mode, MIN_SITE_MODE, MAX_SITE_MODE));
    }

    public static int getSyncMode() {
        return Math.clamp(Prefers.getInt("sync_mode"), MIN_SYNC_MODE, MAX_SYNC_MODE);
    }

    public static void putSyncMode(int mode) {
        Prefers.put("sync_mode", Math.clamp(mode, MIN_SYNC_MODE, MAX_SYNC_MODE));
    }

    public static boolean isIncognito() {
        return Prefers.getBoolean("incognito");
    }

    public static void putIncognito(boolean incognito) {
        Prefers.put("incognito", incognito);
    }

    // 默认关闭自动更新检查，避免每次启动都弹出「发现新版本」升级弹窗（私有 fork 无需跟随上游版本）。
    // 设置页手动「检测更新」仍可触发（Updater.force() 会主动 putUpdate(true) 再执行）。
    public static boolean getUpdate() {
        return Prefers.getBoolean("update", false);
    }

    public static void putUpdate(boolean update) {
        Prefers.put("update", update);
    }

    public static boolean isAdblock() {
        return Prefers.getBoolean("adblock", true);
    }

    public static void putAdblock(boolean adblock) {
        Prefers.put("adblock", adblock);
    }

    public static boolean isZhuyin() {
        return Prefers.getBoolean("zhuyin");
    }

    public static void putZhuyin(boolean zhuyin) {
        Prefers.put("zhuyin", zhuyin);
    }

    // 默认开启：屏蔽「网盘家族」爬虫站点（夸克/阿里/115/百度/天翼等）。
    // 这些 type==3 站点播放前要 UC 扫码换 Token，存在把云盘凭证交给源 spider 作者的泄露面；
    // 且 headless 测速会反复弹「需要UC浏览器扫码」。开启后这些站点的 spider 直接返回 SpiderNull，
    // 不弹窗、不泄露。若误拦了正常站，关掉即可（全局开关，作为安全/误拦的取舍阀门）。
    public static boolean isDisableCloudDrive() {
        return Prefers.getBoolean("disable_cloud_drive", true);
    }

    public static void putDisableCloudDrive(boolean value) {
        Prefers.put("disable_cloud_drive", value);
    }
}
