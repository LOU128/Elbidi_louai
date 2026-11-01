package com.elbidi.horizon;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fait tourner les fonds d'écran avec un fondu enchaîné et un léger zoom lent.
 * Source : les photos déposées dans Android/data/com.elbidi.horizon/files/wallpapers
 * (via « tvtool.py wallpapers »), sinon des paysages générés par {@link SceneRenderer}.
 */
final class WallpaperRotator {

    private static final long SHOW_MS = 45_000;
    private static final long FADE_MS = 2_500;
    private static final float ZOOM = 1.07f;

    private final Activity activity;
    private final ImageView bottom;
    private final ImageView top;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final int width;
    private final int height;

    private ImageView front;
    private boolean running;
    private boolean loading;
    private int sceneIndex;
    private int photoIndex;
    private List<File> photos = new ArrayList<>();
    private ObjectAnimator zoomX, zoomY;

    private final Runnable tick = this::next;

    WallpaperRotator(Activity activity, ImageView bottom, ImageView top) {
        this.activity = activity;
        this.bottom = bottom;
        this.top = top;
        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        width = Math.max(dm.widthPixels, dm.heightPixels);
        height = Math.min(dm.widthPixels, dm.heightPixels);
        sceneIndex = random.nextInt(SceneRenderer.SCENE_COUNT);
        top.setAlpha(0f);
        front = bottom;
    }

    void start() {
        if (running) return;
        running = true;
        if (front.getDrawable() == null) next();
        else ui.postDelayed(tick, SHOW_MS);
    }

    void pause() {
        running = false;
        ui.removeCallbacks(tick);
    }

    void release() {
        pause();
        worker.shutdownNow();
    }

    private void next() {
        if (loading) return;
        loading = true;
        worker.execute(() -> {
            Bitmap b = null;
            try {
                b = nextBitmap();
            } catch (OutOfMemoryError | RuntimeException ignored) {
                // on retentera au prochain tour
            }
            final Bitmap bmp = b;
            ui.post(() -> {
                loading = false;
                if (bmp != null) swapIn(bmp);
                if (running) {
                    ui.removeCallbacks(tick);
                    ui.postDelayed(tick, SHOW_MS);
                }
            });
        });
    }

    private void swapIn(Bitmap bmp) {
        final ImageView incoming = front == bottom ? top : bottom;
        final ImageView outgoing = front;

        incoming.setImageBitmap(bmp);
        incoming.setScaleX(1f);
        incoming.setScaleY(1f);

        boolean first = outgoing.getDrawable() == null;
        if (first) {
            incoming.setAlpha(1f);
        } else if (incoming == top) {
            incoming.setAlpha(0f);
            incoming.animate().alpha(1f).setDuration(FADE_MS).setListener(clearAfter(outgoing)).start();
        } else {
            incoming.setAlpha(1f);
            outgoing.animate().alpha(0f).setDuration(FADE_MS).setListener(clearAfter(outgoing)).start();
        }
        front = incoming;
        kenBurns(incoming);
    }

    private Animator.AnimatorListener clearAfter(final ImageView v) {
        return new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (v == front) return;
                Drawable d = v.getDrawable();
                v.setImageDrawable(null);
                v.setScaleX(1f);
                v.setScaleY(1f);
                if (d instanceof BitmapDrawable) {
                    Bitmap old = ((BitmapDrawable) d).getBitmap();
                    if (old != null && !old.isRecycled()) old.recycle();
                }
            }
        };
    }

    private void kenBurns(View v) {
        if (zoomX != null) zoomX.cancel();
        if (zoomY != null) zoomY.cancel();
        long d = SHOW_MS + FADE_MS * 2;
        zoomX = ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, ZOOM).setDuration(d);
        zoomY = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, ZOOM).setDuration(d);
        zoomX.setInterpolator(new LinearInterpolator());
        zoomY.setInterpolator(new LinearInterpolator());
        zoomX.start();
        zoomY.start();
    }

    private Bitmap nextBitmap() {
        refreshPhotos();
        if (!photos.isEmpty()) {
            for (int tries = 0; tries < photos.size(); tries++) {
                File f = photos.get(photoIndex++ % photos.size());
                Bitmap b = decodeSampled(f);
                if (b != null) return b;
            }
        }
        // Rendu à demi-résolution : léger, et l'agrandissement adoucit les dégradés.
        return SceneRenderer.render(sceneIndex++ % SceneRenderer.SCENE_COUNT,
                Math.max(640, width / 2), Math.max(360, height / 2), random.nextLong());
    }

    private void refreshPhotos() {
        File dir = activity.getExternalFilesDir("wallpapers");
        List<File> found = new ArrayList<>();
        if (dir != null) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")) {
                        found.add(f);
                    }
                }
            }
        }
        if (!sameFiles(found, photos)) {
            Collections.shuffle(found, random);
            photos = found;
            photoIndex = 0;
        }
    }

    private static boolean sameFiles(List<File> a, List<File> b) {
        if (a.size() != b.size()) return false;
        List<String> x = new ArrayList<>(), y = new ArrayList<>();
        for (File f : a) x.add(f.getName());
        for (File f : b) y.add(f.getName());
        Collections.sort(x);
        Collections.sort(y);
        return x.equals(y);
    }

    private Bitmap decodeSampled(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= width && o.outHeight / (sample * 2) >= height) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.RGB_565; // moitié moins de mémoire
        return BitmapFactory.decodeFile(f.getAbsolutePath(), d);
    }
}
