package com.ivantrj.glasslauncher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Apple TV style home screen.
 *
 * Focus is handled here, not by Android's focus search, so D-pad movement is
 * deterministic: three zones (top bar, dock, grid), Up/Down move exactly one
 * row, and the ends of a row never fall through to another zone.
 */
public class HomeActivity extends Activity {

    private static final int TOP = 0, DOCK = 1, GRID = 2;
    private static final int COLUMNS = 4;
    static final int MAX_DOCK = 4;
    private static final int PILL_INPUTS = 0, PILL_WIFI = 1, PILL_SETTINGS = 2;
    private static final int NEUTRAL_GLOW = 0xFF8E8E9E;

    private View root, page, band, glow, pillRow;
    private View[] pills;
    private GlassHighlight highlight;
    private TextView greeting, dockTitle, wifiName;
    private LinearLayout dockRow, inputsMenu;
    private RecyclerView grid;
    private ImageView wallpaper;
    private GridAdapter adapter;

    private final List<AppInfo> dockApps = new ArrayList<>();
    private List<AppInfo> gridApps = new ArrayList<>();

    private int zone = DOCK, topIdx = 0, dockIdx = 0, gridIdx = 0;
    private String restoreGridPkg;
    private int gridScrollTarget, gridScrollActual;
    private SpringValue pageSpring;

    private GradientDrawable glowDrawable;
    private int glowColor = NEUTRAL_GLOW;
    private ValueAnimator glowAnimator;

    private boolean menuOpen;
    private int menuIdx;

    // Select fires on release so a hold can become a long press; moving = a dock tile is lifted
    private boolean selectDown, longPressed, moving;
    private List<InputsMenu.Entry> menuEntries = new ArrayList<>();

    private boolean stopped = true;
    private long wallpaperStamp = -1;
    private long loadedConfigStamp;
    private float density;
    private SharedPreferences prefs;
    private WifiManager wifiManager;
    private ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.8f);

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        density = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("focus", MODE_PRIVATE);
        wifiManager = getApplicationContext().getSystemService(WifiManager.class);
        connectivity = getSystemService(ConnectivityManager.class);

        root = findViewById(R.id.root);
        page = findViewById(R.id.page);
        band = findViewById(R.id.band);
        glow = findViewById(R.id.glow);
        highlight = findViewById(R.id.highlight);
        greeting = findViewById(R.id.greeting);
        dockTitle = findViewById(R.id.dockTitle);
        wifiName = findViewById(R.id.wifiName);
        dockRow = findViewById(R.id.dockRow);
        grid = findViewById(R.id.grid);
        inputsMenu = findViewById(R.id.inputsMenu);
        wallpaper = findViewById(R.id.wallpaper);
        pillRow = findViewById(R.id.pills);
        pills = new View[] {findViewById(R.id.pillInputs), findViewById(R.id.pillWifi), findViewById(R.id.pillSettings)};

        greeting.setTypeface(Fonts.outfit(this, 300));
        ((TextClock) findViewById(R.id.clock)).setTypeface(Fonts.outfit(this, 120));
        ((TextClock) findViewById(R.id.date)).setTypeface(Fonts.outfit(this, 300));
        dockTitle.setTypeface(Fonts.outfit(this, 250));
        for (View pill : pills) ((TextView) ((LinearLayout) pill).getChildAt(1)).setTypeface(Fonts.outfit(this, 400));

        glowDrawable = new GradientDrawable();
        glowDrawable.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowDrawable.setGradientRadius(480 * density);
        glowDrawable.setGradientCenter(0.5f, 0.5f);
        setGlowColors(glowColor);
        glow.setBackground(glowDrawable);
        glow.setScaleY(0.42f); // squash the circle into a wide ellipse behind the dock

        pageSpring = new SpringValue(240f, 0.92f, v -> page.setTranslationY(-v));

        adapter = new GridAdapter(grid);
        grid.setLayoutManager(new GridLayoutManager(this, COLUMNS));
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.setAdapter(adapter);
        grid.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) { gridScrollActual += dy; }
        });

        // The Wi-Fi pill changes width with the network name: keep the highlight on it
        pillRow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (zone == TOP) moveHighlight();
        });

        zone = prefs.getInt("zone", DOCK) == GRID ? GRID : DOCK;
        dockIdx = prefs.getInt("dockIdx", 0);
        topIdx = prefs.getInt("topIdx", 0);
        restoreGridPkg = prefs.getString("gridPkg", null);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);

        loadApps();
    }

    @Override
    protected void onStart() {
        super.onStart();
        stopped = false;
        IntentFilter timeFilter = new IntentFilter(Intent.ACTION_TIME_TICK);
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, timeFilter);
        connectivity.registerDefaultNetworkCallback(networkCallback);
        updateGreeting();
        updateWifi();
        loadWallpaper();
        if (configStamp() != loadedConfigStamp) loadApps(); // dock.txt / hidden.txt edited
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        unregisterReceiver(timeReceiver);
        connectivity.unregisterNetworkCallback(networkCallback);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (moving) endMove();
        selectDown = false;
        SharedPreferences.Editor e = prefs.edit()
                .putInt("zone", zone)
                .putInt("dockIdx", dockIdx)
                .putInt("topIdx", topIdx);
        if (gridIdx < gridApps.size()) e.putString("gridPkg", gridApps.get(gridIdx).packageName);
        e.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packageReceiver);
        io.shutdownNow();
    }

    /** Home pressed. From an app: keep the remembered focus. Already home: go back to the dock. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra("reload", false)) { // sent by configure.sh
            loadApps();
            wallpaperStamp = -1;
            loadWallpaper();
            return;
        }
        boolean wasVisible = !stopped;
        closeMenu();
        if (wasVisible && !dockApps.isEmpty()) {
            zone = DOCK;
            applyFocus(true);
        }
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        loadedConfigStamp = configStamp();
        Context app = getApplicationContext();
        io.execute(() -> {
            AppLoader.Result result = AppLoader.load(app);
            main.post(() -> onAppsLoaded(result));
        });
    }

    private void onAppsLoaded(AppLoader.Result result) {
        String gridPkg = restoreGridPkg;
        if (gridPkg == null && gridIdx < gridApps.size()) gridPkg = gridApps.get(gridIdx).packageName;
        restoreGridPkg = null;

        dockApps.clear();
        dockApps.addAll(result.dock);
        dockRow.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (AppInfo app : dockApps) {
            View cell = inflater.inflate(R.layout.item_tile, dockRow, false);
            Tiles.bind(cell, app);
            dockRow.addView(cell);
        }
        gridApps = result.grid;
        adapter.setApps(gridApps);

        dockIdx = clamp(dockIdx, 0, dockApps.size() - 1);
        int found = -1;
        for (int i = 0; gridPkg != null && i < gridApps.size(); i++) {
            if (gridApps.get(i).packageName.equals(gridPkg)) found = i;
        }
        gridIdx = clamp(found >= 0 ? found : gridIdx, 0, gridApps.size() - 1);
        if (zone == GRID && gridApps.isEmpty()) zone = DOCK;
        if (zone == DOCK && dockApps.isEmpty()) zone = TOP;

        // Place the highlight only once the new tiles have real positions
        afterLayout(() -> applyFocus(false));
    }

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ---------------------------------------------------------------- keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        if (!handles(key)) return super.dispatchKeyEvent(event);
        if (isSelect(key) && !menuOpen && !moving) {
            // Tap opens on release; holding past the first key repeat (~0.5 s) is a long press
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    selectDown = true;
                    longPressed = false;
                } else if (selectDown && !longPressed) {
                    longPressed = true;
                    longPress();
                }
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (selectDown && !longPressed && !event.isCanceled()) activate();
                selectDown = false;
            }
            return true;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        if (menuOpen) {
            menuKey(key);
            return true;
        }
        if (moving) {
            if (event.getRepeatCount() == 0) moveKey(key); // ignore the held select that started the move
            return true;
        }
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: left(); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: right(); break;
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_BACK: back(); break;
            case KeyEvent.KEYCODE_MENU: openAppInfo(); break;
        }
        return true;
    }

    private static boolean isSelect(int key) {
        return key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static boolean handles(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                return true;
            default:
                return false;
        }
    }

    private void left() {
        if (zone == TOP && topIdx > 0) topIdx--;
        else if (zone == DOCK && dockIdx > 0) dockIdx--;
        else if (zone == GRID && gridIdx % COLUMNS > 0) gridIdx--;
        else return;
        applyFocus(true);
    }

    private void right() {
        if (zone == TOP && topIdx < pills.length - 1) topIdx++;
        else if (zone == DOCK && dockIdx < dockApps.size() - 1) dockIdx++;
        else if (zone == GRID && gridIdx % COLUMNS < COLUMNS - 1 && gridIdx + 1 < gridApps.size()) gridIdx++;
        else return;
        applyFocus(true);
    }

    private void up() {
        if (zone == TOP) return;
        if (zone == DOCK) {
            zone = TOP;
        } else if (gridIdx >= COLUMNS) {
            gridIdx -= COLUMNS;
        } else if (!dockApps.isEmpty()) {
            zone = DOCK;
            dockIdx = Math.min(gridIdx % COLUMNS, dockApps.size() - 1);
        } else {
            zone = TOP;
        }
        applyFocus(true);
    }

    private void down() {
        if (zone == TOP && !dockApps.isEmpty()) {
            zone = DOCK;
        } else if ((zone == TOP || zone == DOCK) && !gridApps.isEmpty()) {
            gridIdx = Math.min(zone == DOCK ? dockIdx : 0, gridApps.size() - 1);
            zone = GRID;
        } else if (zone == GRID && gridIdx / COLUMNS < (gridApps.size() - 1) / COLUMNS) {
            // One row down; a short last row catches the focus on its last tile
            gridIdx = Math.min(gridIdx + COLUMNS, gridApps.size() - 1);
        } else {
            return;
        }
        applyFocus(true);
    }

    private void back() {
        if (zone == DOCK || dockApps.isEmpty()) return;
        zone = DOCK;
        applyFocus(true);
    }

    private void activate() {
        if (zone == TOP) {
            if (topIdx == PILL_INPUTS) openMenu();
            else if (topIdx == PILL_WIFI) launch(new Intent(Settings.ACTION_WIFI_SETTINGS));
            else launch(new Intent(Settings.ACTION_SETTINGS));
        } else {
            AppInfo app = focusedApp();
            if (app != null) launch(app.launchIntent);
        }
    }

    /** Menu button on a tile opens that app's system info page (force stop, clear cache...). */
    private void openAppInfo() {
        AppInfo app = focusedApp();
        if (app == null) return;
        launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null)));
    }

    // ---------------------------------------------------------------- dock editing

    /**
     * Long press on a dock tile lifts it so Left/Right reorder the dock. Long press on a
     * grid tile adds it to the end of the dock (the last dock app goes back to the grid
     * if the dock is full) and lifts it there. The order is saved to dock.txt, the same
     * file configure.sh writes.
     */
    private void longPress() {
        selectDown = false;
        if (zone == GRID && gridIdx < gridApps.size()) {
            AppInfo app = gridApps.get(gridIdx);
            if (dockApps.size() >= MAX_DOCK) {
                AppInfo out = dockApps.remove(dockApps.size() - 1);
                Toast.makeText(this, out.label + " moved to the apps below", Toast.LENGTH_SHORT).show();
            }
            dockApps.add(app);
            dockIdx = dockApps.size() - 1;
            zone = DOCK;
            moving = true;
            saveDock();
            loadApps(); // rebuilds dock and grid from dock.txt; moving stays on
        } else if (zone == DOCK && dockIdx < dockApps.size()) {
            moving = true;
            applyFocus(true);
        } else {
            return;
        }
        Toast.makeText(this, "Left/Right to move · OK to drop · Down to remove", Toast.LENGTH_LONG).show();
    }

    private void moveKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_LEFT: swapDock(dockIdx - 1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: swapDock(dockIdx + 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: // take it out of the dock, back to the grid
                if (dockApps.size() == 1) break; // an empty dock.txt would bring back the defaults
                AppInfo app = dockApps.remove(dockIdx);
                dockIdx = clamp(dockIdx, 0, dockApps.size() - 1);
                moving = false;
                restoreGridPkg = app.packageName;
                saveDock();
                loadApps();
                break;
            case KeyEvent.KEYCODE_MENU:
                break;
            default: // OK, Back, Up drop it where it is
                endMove();
        }
    }

    private void swapDock(int to) {
        if (to < 0 || to >= dockApps.size()) return;
        dockApps.add(to, dockApps.remove(dockIdx));
        View cell = dockRow.getChildAt(dockIdx);
        dockRow.removeViewAt(dockIdx);
        dockRow.addView(cell, to);
        cell.setTranslationY(0);
        dockIdx = to;
        afterLayout(() -> applyFocus(true));
    }

    private void endMove() {
        moving = false;
        saveDock();
        applyFocus(true);
    }

    private void saveDock() {
        StringBuilder sb = new StringBuilder();
        for (AppInfo app : dockApps) sb.append(app.packageName).append('\n');
        File file = new File(getFilesDir(), "dock.txt");
        try (FileWriter w = new FileWriter(file)) {
            w.write(sb.toString());
        } catch (IOException e) {
            Toast.makeText(this, "Couldn't save the dock", Toast.LENGTH_SHORT).show();
        }
        loadedConfigStamp = configStamp(); // our own write, not an outside edit
    }

    private AppInfo focusedApp() {
        if (zone == DOCK && dockIdx < dockApps.size()) return dockApps.get(dockIdx);
        if (zone == GRID && gridIdx < gridApps.size()) return gridApps.get(gridIdx);
        return null;
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Can't open that here", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- focus visuals

    private void applyFocus(boolean animate) {
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View cell = dockRow.getChildAt(i);
            Tiles.setFocused(cell, zone == DOCK && i == dockIdx, animate);
            float lift = moving && i == dockIdx ? -liftOffset() : 0;
            cell.animate().translationY(lift).setDuration(animate ? 200 : 0).setInterpolator(ease).start();
        }
        adapter.setFocused(zone == GRID ? gridIdx : RecyclerView.NO_POSITION);
        for (int i = 0; i < pills.length; i++) {
            boolean on = zone == TOP && i == topIdx;
            float s = on ? 1.06f : 1f;
            pills[i].animate().scaleX(s).scaleY(s).setDuration(animate ? 200 : 0).setInterpolator(ease).start();
        }

        // Down from the dock slides the whole page up; deeper rows scroll the grid itself
        float pageTarget = 0;
        int scrollTarget = 0;
        if (zone == GRID) {
            int row = gridIdx / COLUMNS;
            if (row == 0) {
                pageTarget = band.getTop() - dp(30);
            } else {
                pageTarget = grid.getTop() - dp(20);
                scrollTarget = clamp((row - 1) * rowHeight(), 0, maxGridScroll());
            }
        }
        if (animate) pageSpring.animateTo(pageTarget); else pageSpring.snapTo(pageTarget);
        gridScrollTarget = scrollTarget;
        int dy = scrollTarget - gridScrollActual;
        if (dy != 0) {
            if (animate) grid.smoothScrollBy(0, dy, ease, 420);
            else grid.scrollBy(0, dy);
        }

        updateDockTitle(animate);
        moveHighlight();
    }

    private void moveHighlight() {
        float l, t, w, h;
        if (zone == TOP) {
            View p = pills[topIdx];
            float s = 1.06f, pad = dp(3);
            w = p.getWidth() * s + 2 * pad;
            h = p.getHeight() * s + 2 * pad;
            l = pillRow.getLeft() + p.getLeft() + p.getWidth() / 2f - w / 2f;
            t = pillRow.getTop() + p.getTop() + p.getHeight() / 2f - h / 2f;
            highlight.moveTo(l, t, w, h, h / 2f);
            return;
        }
        float tileW = dp(180), tileH = dp(101);
        if (zone == DOCK) {
            if (dockIdx >= dockRow.getChildCount()) return;
            View cell = dockRow.getChildAt(dockIdx);
            View tile = cell.findViewById(R.id.tile);
            l = dockRow.getLeft() + cell.getLeft() + tile.getLeft();
            t = dockRow.getTop() + cell.getTop() + tile.getTop() - (moving ? liftOffset() : 0);
        } else {
            // Computed from the grid geometry and the scroll target, so the highlight
            // heads straight for the final spot while the grid is still scrolling
            int col = gridIdx % COLUMNS, row = gridIdx / COLUMNS;
            l = grid.getLeft() + grid.getPaddingLeft() + col * dp(204) + dp(12);
            t = grid.getTop() + grid.getPaddingTop() + row * rowHeight() - gridScrollTarget + dp(10);
        }
        float s = Tiles.FOCUS_SCALE, pad = dp(6);
        w = tileW * s + 2 * pad;
        h = tileH * s + 2 * pad;
        highlight.moveTo(l + tileW / 2f - w / 2f, t + tileH / 2f - h / 2f, w, h, dp(14) * s + pad);
    }

    private void updateDockTitle(boolean animate) {
        // Full in the dock, dimmed under the top bar, gone once the page slides up
        float alpha = zone == DOCK ? 1f : zone == TOP ? 0.4f : 0f;
        glow.animate().alpha(zone == DOCK ? 1f : 0.5f).setDuration(animate ? 300 : 0).start();
        if (dockApps.isEmpty()) {
            dockTitle.setText("");
            return;
        }
        AppInfo app = dockApps.get(dockIdx);
        dockTitle.animate().cancel();
        if (!app.label.contentEquals(dockTitle.getText())) {
            dockTitle.setText(app.label);
            if (animate) {
                dockTitle.setAlpha(0f);
                dockTitle.setTranslationX(dp(10));
            }
            animateGlow(app.glowColor, animate);
        }
        if (animate) {
            dockTitle.animate().alpha(alpha).translationX(0).setDuration(260).setInterpolator(ease).start();
        } else {
            dockTitle.setAlpha(alpha);
            dockTitle.setTranslationX(0);
        }
    }

    private void animateGlow(int color, boolean animate) {
        if (glowAnimator != null) glowAnimator.cancel();
        if (!animate) {
            glowColor = color;
            setGlowColors(color);
            return;
        }
        glowAnimator = ValueAnimator.ofArgb(glowColor, color);
        glowAnimator.setDuration(500);
        glowAnimator.addUpdateListener(a -> {
            glowColor = (int) a.getAnimatedValue();
            setGlowColors(glowColor);
        });
        glowAnimator.start();
    }

    private void setGlowColors(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        glowDrawable.setColors(new int[] {Color.argb(120, r, g, b), Color.argb(46, r, g, b), Color.argb(0, r, g, b)});
    }

    // ---------------------------------------------------------------- inputs menu

    private void openMenu() {
        menuEntries = InputsMenu.load(this);
        inputsMenu.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        menuIdx = -1;
        for (int i = 0; i < menuEntries.size(); i++) {
            InputsMenu.Entry entry = menuEntries.get(i);
            TextView row = (TextView) inflater.inflate(R.layout.item_input, inputsMenu, false);
            row.setText(entry.label);
            row.setTypeface(Fonts.outfit(this, 400));
            if (entry.intent == null) row.setAlpha(0.55f);
            else if (menuIdx < 0) menuIdx = i;
            inputsMenu.addView(row);
        }
        updateMenuSelection();

        inputsMenu.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        View pill = pills[PILL_INPUTS];
        float x = pillRow.getLeft() + pill.getLeft();
        x = Math.min(x, root.getWidth() - inputsMenu.getMeasuredWidth() - dp(24));
        float y = pillRow.getTop() + pill.getBottom() + dp(12);
        inputsMenu.setX(x);
        inputsMenu.setY(y - dp(8));
        inputsMenu.setAlpha(0f);
        inputsMenu.setVisibility(View.VISIBLE);
        inputsMenu.animate().alpha(1f).y(y).setDuration(180).setInterpolator(ease).start();
        menuOpen = true;
    }

    private void closeMenu() {
        if (!menuOpen) return;
        menuOpen = false;
        inputsMenu.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> inputsMenu.setVisibility(View.GONE)).start();
    }

    private void menuKey(int key) {
        switch (key) {
            case KeyEvent.KEYCODE_DPAD_UP:
                for (int i = menuIdx - 1; i >= 0; i--) if (menuEntries.get(i).intent != null) { menuIdx = i; break; }
                updateMenuSelection();
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                for (int i = menuIdx + 1; i < menuEntries.size(); i++) if (menuEntries.get(i).intent != null) { menuIdx = i; break; }
                updateMenuSelection();
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (menuIdx >= 0) {
                    closeMenu();
                    launch(menuEntries.get(menuIdx).intent);
                }
                break;
            default: // Back, Left, Right, Menu close it
                closeMenu();
        }
    }

    private void updateMenuSelection() {
        for (int i = 0; i < inputsMenu.getChildCount(); i++) {
            inputsMenu.getChildAt(i).setBackgroundResource(i == menuIdx ? R.drawable.menu_row_selected : 0);
        }
    }

    // ---------------------------------------------------------------- clock, greeting, wifi, wallpaper

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateGreeting(); }
    };

    private void updateGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String text = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon"
                : hour < 22 ? "Good evening" : "Good night";
        greeting.setText(text);
    }

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { main.post(HomeActivity.this::updateWifi); }
        @Override public void onLost(Network n) { main.post(HomeActivity.this::updateWifi); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { main.post(HomeActivity.this::updateWifi); }
    };

    private void updateWifi() {
        Network network = connectivity.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : connectivity.getNetworkCapabilities(network);
        String text;
        if (caps == null) {
            text = "Offline";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            text = "Ethernet";
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            WifiInfo info = wifiManager.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid == null || ssid.equals(WifiManager.UNKNOWN_SSID) || ssid.isEmpty()) {
                text = "Wi-Fi"; // name hidden: location permission or location mode is off
            } else {
                text = ssid.startsWith("\"") && ssid.endsWith("\"") ? ssid.substring(1, ssid.length() - 1) : ssid;
            }
        } else {
            text = "Connected";
        }
        if (!text.contentEquals(wifiName.getText())) wifiName.setText(text);
    }

    /** Optional wallpaper: wallpaper.jpg or wallpaper.png in the app's files dir (see configure.sh). */
    private void loadWallpaper() {
        File file = null;
        for (String name : new String[] {"wallpaper.jpg", "wallpaper.png"}) {
            File f = new File(getFilesDir(), name);
            if (f.isFile()) { file = f; break; }
        }
        long stamp = file == null ? 0 : file.lastModified() + file.length();
        if (stamp == wallpaperStamp) return;
        wallpaperStamp = stamp;
        if (file == null) {
            wallpaper.setImageDrawable(null);
            wallpaper.setVisibility(View.GONE);
            return;
        }
        String path = file.getAbsolutePath();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        io.execute(() -> {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= screenW) o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory, fine for a dimmed photo
            Bitmap bmp = BitmapFactory.decodeFile(path, o);
            main.post(() -> {
                wallpaper.setImageBitmap(bmp);
                wallpaper.setColorFilter(Color.argb(115, 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
                wallpaper.setVisibility(bmp == null ? View.GONE : View.VISIBLE);
            });
        });
    }

    // ---------------------------------------------------------------- helpers

    /** Changes whenever dock.txt or hidden.txt is added, edited or removed. */
    private long configStamp() {
        long stamp = 0;
        for (String name : new String[] {"dock.txt", "hidden.txt"}) {
            File f = new File(getFilesDir(), name);
            if (f.isFile()) stamp = stamp * 31 + f.lastModified() + f.length();
        }
        return stamp;
    }

    private void afterLayout(Runnable r) {
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                r.run();
            }
        });
        root.requestLayout();
    }

    private float liftOffset() { return dp(14); }

    private int rowHeight() { return Math.round(dp(144)); }

    private int maxGridScroll() {
        int rows = (gridApps.size() + COLUMNS - 1) / COLUMNS;
        return Math.max(0, rows * rowHeight() + grid.getPaddingBottom() - grid.getHeight());
    }

    private float dp(float v) { return v * density; }

    private static int clamp(int v, int min, int max) { return max < min ? min : Math.max(min, Math.min(max, v)); }
}
