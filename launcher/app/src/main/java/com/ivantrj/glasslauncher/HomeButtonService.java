package com.ivantrj.glasslauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS protects its launcher, so it can't be disabled and stays the system
 * home. This service makes Glass the effective home screen instead:
 *  - the remote's Home button is consumed and opens Glass;
 *  - whenever the Amazon home screen comes to the front anyway (after boot, or when
 *    an app exits), Glass is brought straight back on top.
 * Only the home screen activity is redirected. Fire OS Settings is another
 * activity of the same launcher package, so matching the whole package locks
 * the user out of Settings and Developer options; matching one class fails open.
 * Event-driven only: it receives window changes from the Amazon launcher's
 * package and key events, and does nothing in between.
 */
public class HomeButtonService extends AccessibilityService {

    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private long lastLaunch;

    @Override
    protected void onServiceConnected() {
        // After boot (or a reinstall) Amazon's home is already up before this service binds,
        // so no window event arrives to redirect it.
        openGlass();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) openGlass();
        return true; // swallow both down and up so Fire OS never sees Home
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && AMAZON_LAUNCHER.contentEquals(event.getPackageName())
                && AMAZON_HOME.contentEquals(event.getClassName())) {
            openGlass();
        }
    }

    private void openGlass() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < 600) return; // one launch per Home press / launcher flash
        lastLaunch = now;
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override public void onInterrupt() {}
}
