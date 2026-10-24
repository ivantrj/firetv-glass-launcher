package com.ivantrj.glasslauncher;

import android.accessibilityservice.AccessibilityService;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS protects its launcher, so it can't be disabled and stays the system
 * home. This service makes Glass the effective home screen instead:
 *  - whenever Amazon Home comes to the front (Home key, boot, or when
 *    an app exits), Glass is brought straight back on top.
 * Event-driven only: it receives window changes from the Amazon launcher's
 * package, without requesting key filtering or reading window contents.
 */
public class HomeButtonService extends AccessibilityService {

    private long lastLaunch;

    @Override
    protected void onServiceConnected() {
        android.util.Log.i("GlassHome", "Window redirect connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && HomeRedirectPolicy.isAmazonHome(event.getPackageName(), event.getClassName())) {
            // Amazon also hosts SettingsActivity: leave it accessible.
            openGlass();
        }
    }

    private void openGlass() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLaunch < 600) return; // one launch per Home press / launcher flash
        lastLaunch = now;
        try {
            Intent home = new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            PendingIntent.getActivity(this, 0, home,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE).send();
        } catch (PendingIntent.CanceledException | SecurityException e) {
            android.util.Log.w("GlassHome", "Home redirect unavailable; stock Home stays usable", e);
        }
    }

    @Override public void onInterrupt() {}
}
