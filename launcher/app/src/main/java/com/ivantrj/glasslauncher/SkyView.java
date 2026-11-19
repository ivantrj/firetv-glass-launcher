package com.ivantrj.glasslauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

/**
 * The home background: a sky gradient that follows the sun's real elevation for the
 * configured location (day blue, a dark-blue / light-blue / pink / orange sunset at the
 * actual sunset, deep blue at night), with faint stars once it is dark. Every so often
 * one star twinkles. In the top right the sun climbs from the horizon at sunrise to the
 * top at solar noon and back down at sunset; at night the moon, in its real phase, sits
 * at the top. Redraws once a minute, plus ~30 fps only while a star twinkles.
 */
public class SkyView extends View {

    // Gradient stops, top to bottom (bottom = horizon)
    private static final float[] STOPS = {0f, 0.45f, 0.75f, 1f};

    // Keyframes by sun elevation in degrees, each with one colour per stop
    private static final float[] ELEVATIONS = {-18f, -10f, -4f, 0f, 5f, 15f};
    private static final int[][] SKIES = {
            {0xff02040a, 0xff050916, 0xff081022, 0xff0b152c}, // night
            {0xff040818, 0xff0a1430, 0xff16224a, 0xff26305e}, // nautical twilight
            {0xff0a1430, 0xff1e2c62, 0xff5a4a86, 0xff9a5a7a}, // blue hour, violet horizon
            {0xff142a5c, 0xff4a78b4, 0xffd27a92, 0xfff0924a}, // sunset: dark blue, light blue, pink, orange
            {0xff1a4482, 0xff4a80c0, 0xffb89aa8, 0xffe8b47a}, // golden hour
            {0xff17427e, 0xff2f68a8, 0xff5a8cc4, 0xff8eb4dc}, // day, kept deep enough for white text
    };

    private static final int STAR_COUNT = 350;
    private static final long TWINKLE_MS = 1600;
    private static final long FRAME_MS = 33;

    // Sun track in the top right corner, as fractions of the view
    private static final float TRACK_X = 0.95f, TRACK_TOP = 0.08f, TRACK_HORIZON = 0.6f;
    private static final double SYNODIC_MONTH = 29.530588853;
    private static final double NEW_MOON_2000 = 5.26; // days after J2000.0: 2000-01-06 18:14 UTC (mean phase, within about a day)

    private final Paint skyPaint = new Paint();
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final float[] starX = new float[STAR_COUNT], starY = new float[STAR_COUNT];
    private final float[] starR = new float[STAR_COUNT], starA = new float[STAR_COUNT];
    private final int[] colors = new int[STOPS.length];
    private final Paint sunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moonHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path moonLit = new Path();
    private final RectF arc = new RectF();
    private final float sunRadius, moonRadius;

    private double latitude, longitude;
    private float elevation = -90f;
    private float night; // 0 = no stars, 1 = full night
    private float sunHeight; // 0 = on the horizon, 1 = where it peaks today (solar noon)
    private double moonAge; // 0 = new, 0.5 = full
    private int twinkling = -1;
    private long twinkleStart;
    private boolean visible;

    public SkyView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // Same stars every time, so the sky doesn't reshuffle on each launch
        Random r = new Random(7);
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < STAR_COUNT; i++) {
            starX[i] = r.nextFloat();
            starY[i] = (float) Math.pow(r.nextFloat(), 1.4) * 0.72f; // denser near the top
            starR[i] = (0.35f + r.nextFloat() * r.nextFloat() * 0.9f) * density;
            starA[i] = 0.3f + r.nextFloat() * 0.6f;
        }
        starPaint.setColor(Color.WHITE);
        sunRadius = 20 * density;
        moonRadius = 16 * density;
    }

    /** Where the sky is for. Without one, Glass guesses the longitude from the time zone. */
    void setLocation(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        update();
    }

    /** Recompute the sky for the current time. Called every minute. */
    void update() {
        long now = System.currentTimeMillis();
        double[] sun = sun(now, latitude, longitude);
        elevation = (float) sun[0];
        // Today's highest point is solar noon: 90 degrees minus the latitude-declination gap
        double peak = 90 - Math.abs(latitude - sun[1]);
        sunHeight = peak > 0 ? (float) (elevation / peak) : 0f;
        moonAge = ((now / 86400000.0 - 10957.5 - NEW_MOON_2000) / SYNODIC_MONTH) % 1;
        if (moonAge < 0) moonAge += 1;
        night = clamp((-4f - elevation) / 8f); // stars fade in between -4 and -12 degrees
        int k = 0;
        while (k < ELEVATIONS.length - 2 && elevation > ELEVATIONS[k + 1]) k++;
        float t = clamp((elevation - ELEVATIONS[k]) / (ELEVATIONS[k + 1] - ELEVATIONS[k]));
        for (int i = 0; i < STOPS.length; i++) colors[i] = mix(SKIES[k][i], SKIES[k + 1][i], t);
        skyPaint.setShader(null); // sky and sun glow are rebuilt at the next draw
        glowPaint.setShader(null);
        invalidate();
        scheduleTwinkle();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        skyPaint.setShader(null);
        glowPaint.setShader(null);
        moonHalo.setShader(null);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        visible = isVisible;
        if (visible) update();
        else {
            removeCallbacks(startTwinkle);
            twinkling = -1;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (skyPaint.getShader() == null) {
            skyPaint.setShader(new LinearGradient(0, 0, 0, h, colors, STOPS, Shader.TileMode.CLAMP));
        }
        canvas.drawRect(0, 0, w, h, skyPaint);
        drawSun(canvas, w, h);
        if (night <= 0f) return;

        float pulse = 0f;
        if (twinkling >= 0) {
            float p = (SystemClock.uptimeMillis() - twinkleStart) / (float) TWINKLE_MS;
            if (p >= 1f) {
                twinkling = -1;
                scheduleTwinkle();
            } else {
                pulse = (float) Math.sin(Math.PI * p);
                postInvalidateDelayed(FRAME_MS);
            }
        }
        for (int i = 0; i < STAR_COUNT; i++) {
            float horizonFade = 1f - starY[i] / 0.8f; // stars thin out towards the horizon
            float a = starA[i] * horizonFade * night;
            float r = starR[i];
            if (i == twinkling) {
                a += (1f - a) * pulse;
                r *= 1f + 0.9f * pulse;
            }
            starPaint.setAlpha((int) (255 * clamp(a)));
            canvas.drawCircle(starX[i] * w, starY[i] * h, r, starPaint);
        }
        drawMoon(canvas, w, h);
    }

    /** Sinks below the horizon point and fades out in the first few degrees after sunset. */
    private void drawSun(Canvas canvas, int w, int h) {
        float alpha = clamp((elevation + 3f) / 3f);
        if (alpha <= 0f) return;
        float x = TRACK_X * w;
        float y = (TRACK_HORIZON - Math.min(sunHeight, 1f) * (TRACK_HORIZON - TRACK_TOP)) * h;
        float low = 1f - clamp(elevation / 12f); // orange near the horizon, pale yellow-white up high
        int core = mix(0xfffff4d8, 0xffffb15a, low);
        if (glowPaint.getShader() == null) {
            int rgb = core & 0x00ffffff; // halo: bright just outside the disc, a long faint falloff
            glowPaint.setShader(new RadialGradient(x, y, sunRadius * 7,
                    new int[] {rgb | 0x99000000, rgb | 0x55000000, rgb | 0x1c000000, rgb | 0x08000000, rgb},
                    new float[] {0f, 0.16f, 0.32f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        }
        glowPaint.setAlpha((int) (255 * alpha));
        canvas.drawCircle(x, y, sunRadius * 7, glowPaint);
        sunPaint.setColor(core);
        sunPaint.setAlpha((int) (255 * alpha));
        canvas.drawCircle(x, y, sunRadius, sunPaint);
    }

    /** The moon at the top of the track, lit side right while waxing (northern sky). */
    private void drawMoon(Canvas canvas, int w, int h) {
        float x = TRACK_X * w, y = TRACK_TOP * h, r = moonRadius;
        if (moonHalo.getShader() == null) {
            moonHalo.setShader(new RadialGradient(x, y, r * 4.5f,
                    new int[] {0x55dfe6f4, 0x22dfe6f4, 0x0adfe6f4, 0x00dfe6f4},
                    new float[] {0.2f, 0.35f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        }
        // Brighter halo the fuller the moon
        float lit = (float) (1 - Math.cos(2 * Math.PI * moonAge)) / 2;
        moonHalo.setAlpha((int) (255 * night * (0.3f + 0.7f * lit)));
        canvas.drawCircle(x, y, r * 4.5f, moonHalo);
        moonPaint.setColor(0xffe8ecf4);
        moonPaint.setAlpha((int) (28 * night)); // the dark part, just visible
        canvas.drawCircle(x, y, r, moonPaint);

        double angle = 2 * Math.PI * moonAge;
        float k = (float) Math.cos(angle); // terminator half-width: 1 at new and full moon, 0 at quarters
        boolean waxing = moonAge < 0.5;
        boolean crescent = k > 0;
        moonLit.reset();
        arc.set(x - r, y - r, x + r, y + r);
        moonLit.arcTo(arc, -90, waxing ? 180 : -180); // lit limb, top to bottom
        arc.set(x - Math.abs(k) * r, y - r, x + Math.abs(k) * r, y + r);
        // Back to the top along the terminator: bulging to the lit side for a crescent
        moonLit.arcTo(arc, 90, (waxing == crescent) ? -180 : 180);
        moonLit.close();
        moonPaint.setAlpha((int) (235 * night));
        canvas.drawPath(moonLit, moonPaint);
    }

    private final Runnable startTwinkle = () -> {
        if (!visible || night <= 0f) return;
        twinkling = random.nextInt(STAR_COUNT);
        twinkleStart = SystemClock.uptimeMillis();
        invalidate();
    };

    private void scheduleTwinkle() {
        removeCallbacks(startTwinkle);
        if (visible && night > 0f && twinkling < 0) postDelayed(startTwinkle, 1500 + random.nextInt(4500));
    }

    // ---------------------------------------------------------------- sun

    /**
     * Sun elevation above the horizon and declination, both in degrees
     * (low-precision almanac, about 0.1 degree).
     */
    static double[] sun(long millis, double latitude, double longitude) {
        double d = millis / 86400000.0 - 10957.5; // days since J2000.0
        double g = Math.toRadians(norm(357.529 + 0.98560028 * d));
        double q = norm(280.459 + 0.98564736 * d);
        double lambda = Math.toRadians(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double e = Math.toRadians(23.439 - 0.00000036 * d);
        double ra = Math.atan2(Math.cos(e) * Math.sin(lambda), Math.cos(lambda));
        double dec = Math.asin(Math.sin(e) * Math.sin(lambda));
        double gmst = norm(280.46061837 + 360.98564736629 * d); // degrees
        double hourAngle = Math.toRadians(gmst + longitude) - ra;
        double lat = Math.toRadians(latitude);
        double elevation = Math.toDegrees(Math.asin(Math.sin(lat) * Math.sin(dec)
                + Math.cos(lat) * Math.cos(dec) * Math.cos(hourAngle)));
        return new double[] {elevation, Math.toDegrees(dec)};
    }

    private static double norm(double degrees) {
        double r = degrees % 360;
        return r < 0 ? r + 360 : r;
    }

    private static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    private static int mix(int a, int b, float t) {
        return Color.rgb(
                (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
}
