package com.fongmi.android.tv.player.exo;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;

import com.fongmi.android.tv.setting.PreloadSetting;

public class PreCache {

    private MediaItem mediaItem;
    private ExoPlayer player;

    public PreCache() {
    }

    public void start(ExoPlayer player, MediaItem mediaItem) {
        this.mediaItem = mediaItem;
        this.player = player;
        restart();
    }

    public void stop() {
        player = null;
        mediaItem = null;
    }

    public void release() {
        stop();
    }

    private void restart() {
        // DiskPreloadManager belongs to the fongmi media3 fork, which is not present in the local
        // third_party/maven (it ships the standard media3-ui). Preloading is disabled here; normal
        // playback is unaffected.
        if (player == null || mediaItem == null) return;
        if (!PreloadSetting.isPreload()) return;
        if (!canPreload(mediaItem)) return;
        // preload intentionally skipped
    }

    private boolean canPreload(MediaItem mediaItem) {
        if (mediaItem.localConfiguration == null) return false;
        String scheme = mediaItem.localConfiguration.uri.getScheme();
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }
}
