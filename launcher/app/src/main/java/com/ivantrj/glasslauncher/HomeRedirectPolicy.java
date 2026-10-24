package com.ivantrj.glasslauncher;

/** Keep Amazon Settings and every other app outside the Home redirect. */
final class HomeRedirectPolicy {
    private static final String PACKAGE = "com.amazon.tv.launcher";
    private static final String HOME = PACKAGE + ".ui.HomeActivity";

    static boolean isAmazonHome(CharSequence packageName, CharSequence className) {
        if (packageName == null || !PACKAGE.contentEquals(packageName) || className == null) return false;
        String name = className.toString();
        return name.equals(HOME) || name.startsWith(HOME + "_");
    }

    private HomeRedirectPolicy() {}
}
