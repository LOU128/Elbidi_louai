package com.elbidi.horizon;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Écran d'accueil minimaliste : fond nature animé, horloge, dock d'applis favorites
 * et grille « Toutes les applis ». Aucune dépendance, aucune pub, aucune télémétrie.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "horizon";
    private static final String KEY_FAVORITES = "favorites";
    private static final String EXTRA_FAVORITES = "favorites";
    private static final long IDLE_HIDE_MS = 30_000;
    private static final int GRID_COLUMNS = 5;

    /** Utilisé seulement si l'utilisateur n'a encore choisi aucun favori. */
    private static final List<String> DEFAULT_FAVORITES = Arrays.asList(
            "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.spotify.tv.android", "com.plexapp.android", "org.xbmc.kodi",
            "com.canal.android.canal", "fr.francetv.pluzz", "com.google.android.youtube.tvmusic");

    static final class AppEntry {
        String pkg;
        String label;
        Intent intent;
        Drawable banner;
        Drawable icon;
        int color = 0xFF2A3440;
        Runnable action; // tuiles spéciales (Toutes les applis, Réglages)
    }

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, AppEntry> apps = new LinkedHashMap<>();
    private final Map<String, TextView> gridBadges = new HashMap<>();

    private SharedPreferences prefs;
    private WallpaperRotator rotator;
    private View chrome;
    private LinearLayout dock;
    private TextView clock;
    private TextView date;
    private FrameLayout gridOverlay;
    private LinearLayout gridRows;
    private boolean chromeHidden;
    private String focusAfterRebuild;

    private final Runnable hideChrome = this::hideChromeNow;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ------------------------------------------------------------------ cycle de vie

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ImageView wallA = new ImageView(this);
        ImageView wallB = new ImageView(this);
        for (ImageView w : new ImageView[]{wallA, wallB}) {
            w.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(w, new FrameLayout.LayoutParams(-1, -1));
        }
        rotator = new WallpaperRotator(this, wallA, wallB);

        root.addView(buildChrome(), new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildGridOverlay(), new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        handleFavoritesExtra(getIntent());
        registerCompat(packageReceiver, packageFilter());
        loadApps();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleFavoritesExtra(intent);
        // Bouton HOME : on revient à l'état de départ.
        closeGrid();
        showChrome();
        focusFirstDockTile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerCompat(timeReceiver, f);
        updateClock();
        rotator.start();
        showChrome();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(timeReceiver); } catch (IllegalArgumentException ignored) { }
        rotator.pause();
        ui.removeCallbacks(hideChrome);
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(packageReceiver); } catch (IllegalArgumentException ignored) { }
        rotator.release();
        bg.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Un écran d'accueil ne se ferme pas : RETOUR ferme seulement la grille.
        if (isGridOpen()) closeGrid();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (chromeHidden) {
                showChrome();
                return true; // le premier appui réveille seulement l'interface
            }
            scheduleHide();
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
                View f = getCurrentFocus();
                if (f != null && f.isLongClickable()) {
                    f.performLongClick();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ------------------------------------------------------------------ interface

    private View buildChrome() {
        FrameLayout layer = new FrameLayout(this);

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xAA000000, 0x00000000}));
        layer.addView(scrim, new FrameLayout.LayoutParams(-1, dp(340), Gravity.BOTTOM));

        View topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000}));
        layer.addView(topScrim, new FrameLayout.LayoutParams(-1, dp(180), Gravity.TOP));

        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END);
        clock = text(64, Typeface.create("sans-serif-light", Typeface.NORMAL), 0xFFFFFFFF);
        date = text(20, Typeface.create("sans-serif", Typeface.NORMAL), 0xDDFFFFFF);
        clockBox.addView(clock);
        clockBox.addView(date);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(36), dp(56), 0);
        layer.addView(clockBox, clp);

        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(dp(18), dp(16), dp(18), dp(16));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        GradientDrawable glass = new GradientDrawable();
        glass.setCornerRadius(dp(26));
        glass.setColor(0x40FFFFFF);
        glass.setStroke(dp(1), 0x33FFFFFF);
        dock.setBackground(glass);

        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setFillViewport(false);
        scroller.setPadding(dp(40), dp(20), dp(40), dp(20));
        scroller.addView(dock, new FrameLayout.LayoutParams(-2, -2));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.setMargins(0, 0, 0, dp(28));
        layer.addView(scroller, dlp);

        layer.setClipChildren(false);
        chrome = layer;
        return layer;
    }

    private View buildGridOverlay() {
        gridOverlay = new FrameLayout(this);
        gridOverlay.setBackgroundColor(0xE6101418);
        gridOverlay.setVisibility(View.GONE);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(64), dp(40), dp(64), dp(40));
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);

        TextView title = text(34, Typeface.create("sans-serif-medium", Typeface.NORMAL), 0xFFFFFFFF);
        title.setText("Toutes les applis");
        col.addView(title);
        TextView hint = text(16, Typeface.DEFAULT, 0x99FFFFFF);
        hint.setText("OK : ouvrir  ·  appui long ou MENU : ajouter / retirer du dock  ·  RETOUR : fermer");
        hint.setPadding(0, dp(4), 0, dp(20));
        col.addView(hint);

        gridRows = new LinearLayout(this);
        gridRows.setOrientation(LinearLayout.VERTICAL);
        gridRows.setClipChildren(false);
        col.addView(gridRows);

        scroll.addView(col);
        gridOverlay.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        return gridOverlay;
    }

    private void rebuildDock() {
        View focused = getCurrentFocus();
        String keep = focusAfterRebuild;
        if (keep == null && focused != null && focused.getTag() instanceof AppEntry) {
            keep = ((AppEntry) focused.getTag()).pkg;
        }
        focusAfterRebuild = null;

        dock.removeAllViews();
        List<String> favs = favorites();
        for (String pkg : favs) {
            AppEntry e = apps.get(pkg);
            if (e != null) dock.addView(tile(e, true));
        }
        if (!favs.isEmpty()) dock.addView(separator());

        AppEntry all = new AppEntry();
        all.pkg = "_all";
        all.label = "Toutes les applis";
        all.color = 0x55FFFFFF;
        all.action = this::openGrid;
        dock.addView(symbolTile(all, "⋯"));

        AppEntry settings = new AppEntry();
        settings.pkg = "_settings";
        settings.label = "Réglages";
        settings.color = 0x55FFFFFF;
        settings.action = () -> safeStart(new Intent(Settings.ACTION_SETTINGS));
        dock.addView(symbolTile(settings, "⚙"));

        if (keep != null) {
            for (int i = 0; i < dock.getChildCount(); i++) {
                View v = dock.getChildAt(i);
                if (v.getTag() instanceof AppEntry && keep.equals(((AppEntry) v.getTag()).pkg)) {
                    v.requestFocus();
                    return;
                }
            }
        }
        if (getCurrentFocus() == null || !isGridOpen()) focusFirstDockTile();
    }

    private void rebuildGrid() {
        gridRows.removeAllViews();
        gridBadges.clear();
        LinearLayout row = null;
        int i = 0;
        for (AppEntry e : apps.values()) {
            if (i % GRID_COLUMNS == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                row.setPadding(0, dp(10), 0, dp(10));
                gridRows.addView(row);
            }
            FrameLayout t = (FrameLayout) tile(e, false);
            TextView star = text(18, Typeface.DEFAULT_BOLD, 0xFFFFD25A);
            star.setText("★");
            star.setShadowLayer(4, 0, 1, 0xAA000000);
            star.setVisibility(favorites().contains(e.pkg) ? View.VISIBLE : View.GONE);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            slp.setMargins(0, dp(4), dp(8), 0);
            t.addView(star, slp);
            gridBadges.put(e.pkg, star);
            row.addView(t);
            i++;
        }
    }

    private View tile(AppEntry e, boolean inDock) {
        FrameLayout card = baseCard(e);
        if (e.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(e.banner);
            card.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout v = new LinearLayout(this);
            v.setOrientation(LinearLayout.VERTICAL);
            v.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(e.icon);
            v.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
            TextView label = text(13, Typeface.DEFAULT, 0xFFFFFFFF);
            label.setText(e.label);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setPadding(dp(8), dp(4), dp(8), 0);
            v.addView(label, new LinearLayout.LayoutParams(-1, -2));
            card.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        card.setContentDescription(e.label);
        card.setOnClickListener(v -> launch(e));
        card.setOnLongClickListener(v -> {
            if (inDock) dockMenu(e); else toggleFavorite(e);
            return true;
        });
        return card;
    }

    private View symbolTile(AppEntry e, String symbol) {
        FrameLayout card = baseCard(e);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        TextView s = text(34, Typeface.DEFAULT, 0xFFFFFFFF);
        s.setText(symbol);
        v.addView(s);
        TextView label = text(13, Typeface.DEFAULT, 0xEEFFFFFF);
        label.setText(e.label);
        v.addView(label);
        card.addView(v, new FrameLayout.LayoutParams(-1, -1));
        card.setLongClickable(false);
        card.setOnClickListener(x -> e.action.run());
        return card;
    }

    private FrameLayout baseCard(AppEntry e) {
        FrameLayout card = new FrameLayout(this);
        card.setTag(e);
        card.setFocusable(true);
        card.setClickable(true);
        card.setLongClickable(true);
        GradientDrawable bgd = new GradientDrawable();
        bgd.setCornerRadius(dp(14));
        bgd.setColor(e.color);
        card.setBackground(bgd);
        card.setClipToOutline(true);
        card.setElevation(dp(2));
        card.setOnFocusChangeListener((v, has) -> v.animate()
                .scaleX(has ? 1.14f : 1f).scaleY(has ? 1.14f : 1f)
                .translationZ(has ? dp(14) : 0)
                .setDuration(150).start());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(176), dp(99));
        lp.setMargins(dp(10), 0, dp(10), 0);
        card.setLayoutParams(lp);
        return card;
    }

    private View separator() {
        View v = new View(this);
        v.setBackgroundColor(0x44FFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(70));
        lp.setMargins(dp(12), 0, dp(12), 0);
        v.setLayoutParams(lp);
        return v;
    }

    private void dockMenu(AppEntry e) {
        String[] items = {"Déplacer vers la gauche", "Déplacer vers la droite", "Retirer du dock"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(e.label)
                .setItems(items, (d, which) -> {
                    List<String> favs = favorites();
                    int i = favs.indexOf(e.pkg);
                    if (i < 0) return;
                    if (which == 0 && i > 0) { favs.remove(i); favs.add(i - 1, e.pkg); }
                    else if (which == 1 && i < favs.size() - 1) { favs.remove(i); favs.add(i + 1, e.pkg); }
                    else if (which == 2) favs.remove(i);
                    focusAfterRebuild = which == 2 ? null : e.pkg;
                    saveFavorites(favs);
                    rebuildDock();
                })
                .show();
    }

    private void toggleFavorite(AppEntry e) {
        List<String> favs = favorites();
        boolean add = !favs.contains(e.pkg);
        if (add) favs.add(e.pkg); else favs.remove(e.pkg);
        saveFavorites(favs);
        TextView star = gridBadges.get(e.pkg);
        if (star != null) star.setVisibility(add ? View.VISIBLE : View.GONE);
        Toast.makeText(this, add ? e.label + " ajouté au dock" : e.label + " retiré du dock", Toast.LENGTH_SHORT).show();
        rebuildDock();
    }

    private void openGrid() {
        rebuildGrid();
        gridOverlay.setAlpha(0f);
        gridOverlay.setVisibility(View.VISIBLE);
        gridOverlay.animate().alpha(1f).setDuration(180).start();
        chrome.setVisibility(View.INVISIBLE);
        ui.removeCallbacks(hideChrome);
        if (gridRows.getChildCount() > 0) {
            ViewGroup first = (ViewGroup) gridRows.getChildAt(0);
            if (first.getChildCount() > 0) first.getChildAt(0).requestFocus();
        }
    }

    private void closeGrid() {
        if (!isGridOpen()) return;
        gridOverlay.setVisibility(View.GONE);
        chrome.setVisibility(View.VISIBLE);
        rebuildDock();
        scheduleHide();
    }

    private boolean isGridOpen() {
        return gridOverlay != null && gridOverlay.getVisibility() == View.VISIBLE;
    }

    private void focusFirstDockTile() {
        if (dock != null && dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    private void showChrome() {
        chromeHidden = false;
        chrome.animate().alpha(1f).setDuration(250).start();
        scheduleHide();
    }

    private void hideChromeNow() {
        if (isGridOpen()) return;
        chromeHidden = true;
        chrome.animate().alpha(0f).setDuration(1200).start();
    }

    private void scheduleHide() {
        ui.removeCallbacks(hideChrome);
        if (!isGridOpen()) ui.postDelayed(hideChrome, IDLE_HIDE_MS);
    }

    private void updateClock() {
        Date now = new Date();
        clock.setText(DateFormat.getTimeFormat(this).format(now));
        date.setText(DateFormat.format("EEEE d MMMM", now));
    }

    // ------------------------------------------------------------------ applis & favoris

    private void loadApps() {
        bg.execute(() -> {
            PackageManager pm = getPackageManager();
            Map<String, AppEntry> found = new HashMap<>();
            String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
            for (String cat : cats) {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                    ActivityInfo ai = ri.activityInfo;
                    if (ai == null || ai.packageName.equals(getPackageName())) continue;
                    if (found.containsKey(ai.packageName)) continue;
                    AppEntry e = new AppEntry();
                    e.pkg = ai.packageName;
                    e.label = String.valueOf(ri.loadLabel(pm));
                    e.intent = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                            .setClassName(ai.packageName, ai.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    try {
                        Drawable banner = ai.loadBanner(pm);
                        if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
                        e.banner = banner;
                        e.icon = ri.loadIcon(pm);
                        e.color = averageColor(e.icon);
                    } catch (RuntimeException ignored) {
                        // une icône défectueuse ne doit pas faire planter l'accueil
                    }
                    found.put(e.pkg, e);
                }
            }
            List<AppEntry> sorted = new ArrayList<>(found.values());
            Collator col = Collator.getInstance();
            sorted.sort((a, b) -> col.compare(a.label, b.label));
            ui.post(() -> {
                apps.clear();
                for (AppEntry e : sorted) apps.put(e.pkg, e);
                rebuildDock();
                if (isGridOpen()) rebuildGrid();
            });
        });
    }

    private List<String> favorites() {
        String raw = prefs.getString(KEY_FAVORITES, null);
        List<String> out = new ArrayList<>();
        if (raw == null) {
            for (String p : DEFAULT_FAVORITES) if (apps.containsKey(p)) out.add(p);
            return out;
        }
        for (String p : raw.split(",")) if (!p.isEmpty() && !out.contains(p)) out.add(p);
        return out;
    }

    private void saveFavorites(List<String> favs) {
        prefs.edit().putString(KEY_FAVORITES, TextUtils.join(",", favs)).apply();
    }

    /** Permet au script d'installation de régler le dock : am start ... --es favorites a,b,c */
    private void handleFavoritesExtra(Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_FAVORITES)) return;
        String raw = intent.getStringExtra(EXTRA_FAVORITES);
        intent.removeExtra(EXTRA_FAVORITES);
        if (raw == null) return;
        prefs.edit().putString(KEY_FAVORITES, raw.replace(" ", "")).apply();
        if (!apps.isEmpty()) rebuildDock();
    }

    private void launch(AppEntry e) {
        if (e.action != null) { e.action.run(); return; }
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(e.pkg);
        if (i == null) i = e.intent;
        safeStart(i);
    }

    private void safeStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (RuntimeException ex) {
            Toast.makeText(this, "Impossible d'ouvrir cette appli", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private static int averageColor(Drawable d) {
        if (d == null) return 0xFF2A3440;
        Bitmap b = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, 8, 8);
        d.draw(c);
        long r = 0, g = 0, bl = 0, n = 0;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
            }
        }
        b.recycle();
        if (n == 0) return 0xFF2A3440;
        // assombri pour garder le texte lisible
        return Color.rgb((int) (r / n * 0.55), (int) (g / n * 0.55), (int) (bl / n * 0.55));
    }

    private IntentFilter packageFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        return f;
    }

    private void registerCompat(BroadcastReceiver r, IntentFilter f) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(r, f);
    }

    private TextView text(float sp, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setShadowLayer(8, 0, 2, 0x88000000);
        return t;
    }

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
