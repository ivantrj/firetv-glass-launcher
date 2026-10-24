package com.ivantrj.glasslauncher;

/** Dependency-free JVM checks for the Home/Settings boundary. */
public final class HomeRedirectPolicyTest {
    public static void main(String[] args) {
        String amazon = "com.amazon.tv.launcher";
        check(true, amazon, amazon + ".ui.HomeActivity");
        check(true, amazon, amazon + ".ui.HomeActivity_vNext");
        check(false, amazon, amazon + ".ui.SettingsActivity");
        check(false, amazon, amazon + ".ui.HomeActivitySettings");
        check(false, "com.netflix.ninja", amazon + ".ui.HomeActivity_vNext");
        check(false, amazon, "android.widget.FrameLayout");
        check(false, null, amazon + ".ui.HomeActivity_vNext");
        check(false, amazon, null);
        System.out.println("8 Home redirect boundary checks passed");
    }

    private static void check(boolean expected, String pkg, String activity) {
        if (HomeRedirectPolicy.isAmazonHome(pkg, activity) != expected) {
            throw new AssertionError("Unexpected redirect for " + pkg + "/" + activity);
        }
    }
}
