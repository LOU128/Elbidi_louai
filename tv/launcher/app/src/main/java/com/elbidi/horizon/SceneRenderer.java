package com.elbidi.horizon;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Random;

/**
 * Paysages nature générés (aucun fichier, aucun téléchargement). Chaque appel
 * produit une variante différente grâce à la graine aléatoire.
 */
final class SceneRenderer {

    static final int SCENE_COUNT = 6;

    private SceneRenderer() { }

    static Bitmap render(int scene, int w, int h, long seed) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Random r = new Random(seed);
        switch (scene) {
            case 0: mountains(c, w, h, r); break;
            case 1: forest(c, w, h, r); break;
            case 2: ocean(c, w, h, r); break;
            case 3: aurora(c, w, h, r); break;
            case 4: dunes(c, w, h, r); break;
            default: lake(c, w, h, r); break;
        }
        return b;
    }

    // ------------------------------------------------------------------ scènes

    /** Chaîne de montagnes à l'aube, au crépuscule ou en plein jour. */
    private static void mountains(Canvas c, int w, int h, Random r) {
        int[][] palettes = {
                {0xFF1D2B4C, 0xFF6A5A8C, 0xFFF0A27A, 0xFFFFD6A0},   // aube
                {0xFF0F1B33, 0xFF3B4E7A, 0xFFB86F7C, 0xFFF6B48F},   // crépuscule
                {0xFF4C86C6, 0xFF8DB9E2, 0xFFCFE3F2, 0xFFEFF5FA},   // journée
        };
        int[] p = palettes[r.nextInt(palettes.length)];
        sky(c, w, h, p);
        float sunX = w * (0.25f + r.nextFloat() * 0.5f);
        glow(c, sunX, h * 0.52f, h * 0.55f, withAlpha(p[3], 0xAA));
        disc(c, sunX, h * 0.52f, h * 0.06f, withAlpha(p[3], 0xEE));
        int far = blend(p[2], p[1], 0.35f);
        int near = darken(p[0], 0.55f);
        int layers = 5;
        for (int i = 0; i < layers; i++) {
            float t = i / (float) (layers - 1);
            float base = h * (0.50f + t * 0.30f);
            float amp = h * (0.30f - t * 0.13f);
            int col = blend(far, near, t);
            ridgeLayer(c, w, h, r, base, amp, 0.52f + r.nextFloat() * 0.08f, col);
            if (i < layers - 1) fog(c, w, base + amp * 0.05f, h * 0.10f, withAlpha(p[2], 0x30));
        }
    }

    /** Forêt de sapins dans la brume. */
    private static void forest(Canvas c, int w, int h, Random r) {
        boolean morning = r.nextBoolean();
        int[] p = morning
                ? new int[]{0xFF8FB3B0, 0xFFB8D0C4, 0xFFDDE6D5, 0xFFF3F0DC}
                : new int[]{0xFF22343A, 0xFF3F5B5A, 0xFF7E9A8C, 0xFFBFCDB8};
        sky(c, w, h, p);
        glow(c, w * (0.2f + r.nextFloat() * 0.6f), h * 0.35f, h * 0.7f, withAlpha(p[3], 0x88));
        int layers = 5;
        int far = blend(p[1], p[0], 0.4f);
        int near = morning ? 0xFF1E3A30 : 0xFF0C1A17;
        for (int i = 0; i < layers; i++) {
            float t = i / (float) (layers - 1);
            float base = h * (0.55f + t * 0.33f);
            float treeH = h * (0.10f + t * 0.22f);
            int col = blend(far, near, t);
            pines(c, w, h, r, base, treeH, col, (int) (w / (treeH * (0.30f + r.nextFloat() * 0.15f))));
            fog(c, w, base - treeH * 0.2f, h * 0.12f, withAlpha(p[2], i == layers - 1 ? 0x10 : 0x50));
        }
    }

    /** Océan au coucher du soleil avec reflets. */
    private static void ocean(Canvas c, int w, int h, Random r) {
        int[][] palettes = {
                {0xFF2B1E4A, 0xFF8A3E6E, 0xFFF0715A, 0xFFFFC27A},
                {0xFF0E2A47, 0xFF27637F, 0xFF8FC1C8, 0xFFF4E3B8},
        };
        int[] p = palettes[r.nextInt(palettes.length)];
        float horizon = h * (0.56f + r.nextFloat() * 0.08f);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
        sp.setShader(new LinearGradient(0, 0, 0, horizon,
                new int[]{p[0], p[1], p[2], p[3]}, new float[]{0f, 0.45f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, horizon, sp);
        float sunX = w * (0.3f + r.nextFloat() * 0.4f);
        glow(c, sunX, horizon, h * 0.6f, withAlpha(p[3], 0xAA));
        disc(c, sunX, horizon - h * 0.03f, h * 0.07f, withAlpha(p[3], 0xF0));

        Paint sea = new Paint(Paint.ANTI_ALIAS_FLAG);
        sea.setShader(new LinearGradient(0, horizon, 0, h,
                new int[]{blend(p[2], p[0], 0.5f), darken(p[0], 0.6f)}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, horizon, w, h, sea);

        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 260; i++) {
            float y = horizon + (float) Math.pow(r.nextFloat(), 1.6) * (h - horizon);
            float depth = (y - horizon) / (h - horizon);
            float spread = w * (0.04f + depth * 0.25f);
            float x = sunX + (float) r.nextGaussian() * spread;
            float len = w * (0.01f + r.nextFloat() * 0.04f) * (0.5f + depth);
            line.setStrokeWidth(Math.max(1f, h * 0.003f * (0.5f + depth)));
            line.setColor(withAlpha(p[3], (int) (40 + 140 * (1 - depth) * r.nextFloat())));
            c.drawLine(x - len / 2, y, x + len / 2, y, line);
        }
    }

    /** Aurore boréale au-dessus de collines enneigées. */
    private static void aurora(Canvas c, int w, int h, Random r) {
        sky(c, w, h, new int[]{0xFF02060F, 0xFF06142A, 0xFF0B2236, 0xFF123047});
        stars(c, w, (int) (h * 0.8f), r, 220);
        int[] hues = {0xFF3CF2A0, 0xFF55D6FF, 0xFFB46CFF};
        Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
        ap.setStyle(Paint.Style.STROKE);
        ap.setStrokeCap(Paint.Cap.ROUND);
        ap.setMaskFilter(new BlurMaskFilter(h * 0.05f, BlurMaskFilter.Blur.NORMAL));
        for (int band = 0; band < 3; band++) {
            int col = band == 0 ? hues[0] : hues[r.nextInt(hues.length)];
            float baseY = h * (0.22f + band * 0.09f + r.nextFloat() * 0.05f);
            float amp = h * (0.05f + r.nextFloat() * 0.06f);
            float phase = r.nextFloat() * 6.28f;
            float freq = 1.5f + r.nextFloat() * 1.5f;
            Path path = new Path();
            for (int i = 0; i <= 48; i++) {
                float x = w * i / 48f;
                float y = baseY + (float) Math.sin(phase + freq * 6.28f * i / 48f) * amp;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            ap.setStrokeWidth(h * (0.08f + r.nextFloat() * 0.06f));
            ap.setShader(new LinearGradient(0, baseY - amp, 0, baseY + h * 0.2f,
                    withAlpha(col, 0x00), withAlpha(col, 0x90), Shader.TileMode.MIRROR));
            c.drawPath(path, ap);
        }
        ridgeLayer(c, w, h, r, h * 0.72f, h * 0.12f, 0.5f, 0xFF1A2A3C);
        ridgeLayer(c, w, h, r, h * 0.84f, h * 0.08f, 0.45f, 0xFFB9C9D6);
        fog(c, w, h * 0.86f, h * 0.06f, 0x30FFFFFF);
    }

    /** Dunes de sable sous un ciel chaud. */
    private static void dunes(Canvas c, int w, int h, Random r) {
        boolean dusk = r.nextBoolean();
        int[] p = dusk
                ? new int[]{0xFF3A2950, 0xFF9C5A6E, 0xFFE89A6A, 0xFFFAD39A}
                : new int[]{0xFF6FA8D6, 0xFFA9CDE6, 0xFFEBD9B9, 0xFFF7E7C8};
        sky(c, w, h, p);
        glow(c, w * (0.2f + r.nextFloat() * 0.6f), h * 0.45f, h * 0.6f, withAlpha(p[3], 0x88));
        int light = dusk ? 0xFFE0955E : 0xFFE7BE85;
        int shade = dusk ? 0xFF6E3B3E : 0xFFB5793F;
        int layers = 4;
        for (int i = 0; i < layers; i++) {
            float t = i / (float) (layers - 1);
            float base = h * (0.55f + t * 0.14f);
            float amp = h * (0.08f + t * 0.06f);
            Path path = new Path();
            path.moveTo(0, h);
            float x = -w * 0.1f;
            float y = base;
            path.lineTo(x, y);
            while (x < w * 1.1f) {
                float seg = w * (0.25f + r.nextFloat() * 0.35f);
                float peak = base - amp * (0.5f + r.nextFloat());
                path.cubicTo(x + seg * 0.3f, y, x + seg * 0.45f, peak, x + seg * 0.6f, peak);
                float ny = base + amp * (r.nextFloat() - 0.3f) * 0.6f;
                path.cubicTo(x + seg * 0.75f, peak, x + seg * 0.85f, ny, x + seg, ny);
                x += seg;
                y = ny;
            }
            path.lineTo(w, h);
            path.close();
            Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
            int top = blend(blend(light, p[2], 0.5f - t * 0.5f), light, t);
            int bottom = blend(blend(shade, p[1], 0.4f - t * 0.4f), shade, t);
            dp.setShader(new LinearGradient(0, base - amp * 1.5f, 0, h, top, bottom, Shader.TileMode.CLAMP));
            c.drawPath(path, dp);
        }
    }

    /** Lac de montagne avec reflet. */
    private static void lake(Canvas c, int w, int h, Random r) {
        float shore = h * (0.58f + r.nextFloat() * 0.06f);
        Bitmap upper = Bitmap.createBitmap(w, (int) shore, Bitmap.Config.ARGB_8888);
        Canvas u = new Canvas(upper);
        int[] p = r.nextBoolean()
                ? new int[]{0xFF27476E, 0xFF5D86B0, 0xFFA8C8DF, 0xFFE6EEF2}
                : new int[]{0xFF2E2448, 0xFF6B4F7E, 0xFFE09A8A, 0xFFFAD2A6};
        sky(u, w, (int) shore, p);
        glow(u, w * (0.3f + r.nextFloat() * 0.4f), shore * 0.7f, shore * 0.8f, withAlpha(p[3], 0x88));
        for (int i = 0; i < 3; i++) {
            float t = i / 2f;
            ridgeLayer(u, w, (int) shore, r, shore * (0.55f + t * 0.3f), shore * (0.45f - t * 0.15f),
                    0.55f, blend(blend(p[2], p[1], 0.4f), darken(p[0], 0.5f), t));
        }
        pines(u, w, (int) shore, r, shore, shore * 0.12f, darken(p[0], 0.35f), w / 14);
        c.drawBitmap(upper, 0, 0, null);

        Matrix m = new Matrix();
        m.setScale(1f, -1f);
        m.postTranslate(0, shore * 2);
        Paint refl = new Paint(Paint.FILTER_BITMAP_FLAG);
        refl.setAlpha(150);
        c.save();
        c.clipRect(0, shore, w, h);
        c.drawColor(darken(p[0], 0.6f));
        c.drawBitmap(upper, m, refl);
        c.restore();
        upper.recycle();

        Paint water = new Paint();
        water.setShader(new LinearGradient(0, shore, 0, h,
                withAlpha(p[1], 0x40), withAlpha(darken(p[0], 0.4f), 0xC0), Shader.TileMode.CLAMP));
        c.drawRect(0, shore, w, h, water);
        Paint ripple = new Paint(Paint.ANTI_ALIAS_FLAG);
        ripple.setStrokeWidth(Math.max(1f, h * 0.002f));
        for (int i = 0; i < 90; i++) {
            float y = shore + r.nextFloat() * (h - shore);
            float x = r.nextFloat() * w;
            float len = w * (0.02f + r.nextFloat() * 0.08f);
            ripple.setColor(withAlpha(p[3], 20 + r.nextInt(40)));
            c.drawLine(x, y, x + len, y, ripple);
        }
    }

    // ------------------------------------------------------------------ briques

    private static void sky(Canvas c, int w, int h, int[] p) {
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(0, 0, 0, h, p, new float[]{0f, 0.4f, 0.75f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
    }

    private static void glow(Canvas c, float x, float y, float radius, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new RadialGradient(x, y, radius, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, paint);
    }

    private static void disc(Canvas c, float x, float y, float radius, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        c.drawCircle(x, y, radius, paint);
    }

    private static void fog(Canvas c, int w, float y, float thickness, int color) {
        Paint paint = new Paint();
        paint.setShader(new LinearGradient(0, y - thickness, 0, y + thickness,
                new int[]{color & 0x00FFFFFF, color, color & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, y - thickness, w, y + thickness, paint);
    }

    private static void stars(Canvas c, int w, int h, Random r, int count) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (int i = 0; i < count; i++) {
            float size = 0.4f + r.nextFloat() * r.nextFloat() * 2.2f;
            paint.setColor(withAlpha(0xFFFFFFFF, 60 + r.nextInt(195)));
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h, size, paint);
        }
    }

    /** Crête par déplacement du point milieu, remplie jusqu'en bas de l'image. */
    private static void ridgeLayer(Canvas c, int w, int h, Random r, float baseY, float amp, float rough, int color) {
        int n = 128;
        float[] y = new float[n + 1];
        y[0] = r.nextFloat();
        y[n] = r.nextFloat();
        float spread = 1f;
        for (int step = n; step > 1; step /= 2) {
            for (int i = 0; i < n; i += step) {
                int mid = i + step / 2;
                y[mid] = (y[i] + y[i + step]) / 2f + (r.nextFloat() - 0.5f) * spread;
            }
            spread *= rough;
        }
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : y) { min = Math.min(min, v); max = Math.max(max, v); }
        float range = Math.max(0.0001f, max - min);
        Path path = new Path();
        path.moveTo(0, h);
        for (int i = 0; i <= n; i++) {
            path.lineTo(w * i / (float) n, baseY - (y[i] - min) / range * amp);
        }
        path.lineTo(w, h);
        path.close();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, baseY - amp, 0, h, color, darken(color, 0.8f), Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
    }

    private static void pines(Canvas c, int w, int h, Random r, float baseY, float treeH, int color, int count) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        c.drawRect(0, baseY, w, h, paint);
        count = Math.max(4, Math.min(count, 400));
        Path path = new Path();
        for (int i = 0; i < count; i++) {
            float x = (i + r.nextFloat()) * w / count;
            float th = treeH * (0.6f + r.nextFloat() * 0.6f);
            float bw = th * (0.18f + r.nextFloat() * 0.08f);
            float top = baseY - th;
            // trois étages de branches
            for (int k = 0; k < 3; k++) {
                float ty = top + th * k * 0.22f;
                float by = top + th * (0.42f + k * 0.29f);
                float half = bw * (0.55f + k * 0.25f);
                path.moveTo(x, ty);
                path.lineTo(x - half, by);
                path.lineTo(x + half, by);
                path.close();
            }
            path.addRect(x - bw * 0.06f, baseY - th * 0.12f, x + bw * 0.06f, baseY + 1, Path.Direction.CW);
        }
        c.drawPath(path, paint);
    }

    // ------------------------------------------------------------------ couleurs

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return Color.argb(
                (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static int darken(int c, float f) {
        return Color.argb(Color.alpha(c), (int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    private static int withAlpha(int c, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (c & 0x00FFFFFF);
    }
}
