package androidx.media3.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

/**
 * Stub that stands in for the fongmi media3 fork's {@code PlayerSeekView}, which is not present in
 * the local third_party/maven (it ships the standard media3-ui). It is backed by the standard
 * {@link PlayerView} so playback and seeking keep working. {@link #getTimeBar()} resolves the
 * standard {@code exo_progress} DefaultTimeBar.
 */
public class PlayerSeekView extends PlayerView {

    public PlayerSeekView(Context context) {
        super(context);
    }

    public PlayerSeekView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public PlayerSeekView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public TimeBar getTimeBar() {
        View v = findViewById(androidx.media3.ui.R.id.exo_progress);
        return v instanceof TimeBar ? (TimeBar) v : null;
    }
}
