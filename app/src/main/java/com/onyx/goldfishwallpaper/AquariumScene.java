package com.onyx.goldfishwallpaper;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.Random;

/**
 * Procedural goldfish aquarium (simulation + rendering), independent of any Surface.
 *
 * Fish anatomy
 *  - 12 segment spine solved with a follow-the-leader inverse-kinematics chain plus a travelling
 *    sinusoidal muscle wave applied as lateral displacement.
 *  - Double-veil tail: two layers of 7 rays, every ray a 6-link chain whose direction lags behind
 *    its rest pose (fluid drag) and flutters with a travelling wave.
 *  - Independently sculling pectoral and pelvic fins, a swaying dorsal crest.
 *  - 3D volumetric body: overlapping radial-gradient discs, specular lens, scale crescents, eyes
 *    with catch-lights, soft drop shadow.
 *  - Backdrop: gradient, sand, soft-shadowed pebbles (baked once), drifting caustics, light rays.
 *
 * Render loop memory: ZERO objects are allocated per frame. All paths, rects, gradients and
 * shaders are created once (per fish, per palette, or on resize / theme change) and reused. The
 * radial body gradient is a single unit-space gradient positioned with canvas translate/scale, so
 * the same 3D shading costs no heap allocation and never mutates a shader that is already queued
 * on the GPU (which is what makes some OEM hardware canvases flicker).
 */
public final class AquariumScene {

    private static final float PI = (float) Math.PI;
    private static final float TWO_PI = PI * 2f;

    private static final int N = 12;               // spine segments (joints)
    private static final int FISH_COUNT = 8;       // 4 swimming pairs in Love mode
    private static final int RAYS = 7;             // veil tail rays per layer
    private static final int LINKS = 6;            // links per ray
    private static final int MAX_RIPPLES = 25;
    private static final int MAX_PELLETS = 48;
    private static final int MAX_BUBBLES = 30;

    /** Half-width profile along the spine (head to peduncle); 1.0 is the deepest part of the body. */
    private static final float[] WIDTH = {
            0.50f, 0.82f, 0.98f, 1.00f, 0.92f, 0.79f, 0.64f, 0.50f, 0.38f, 0.28f, 0.21f, 0.16f};
    /** Ray length fractions: two lobes with a notch in the middle. */
    private static final float[] RAY_LEN = {0.62f, 0.92f, 1.00f, 0.52f, 1.00f, 0.92f, 0.62f};
    /** Per-link steering stiffness: stiff at the root, loose (fluttery) at the tip. */
    private static final float[] K_BASE = {0.50f, 0.42f, 0.34f, 0.27f, 0.22f, 0.18f};

    private static final float[] BODY_STOPS = {0f, 0.32f, 0.70f, 0.93f, 1f};
    private static final int[][] BODY = {
            {0xFFFFE9A8, 0xFFFFB52E, 0xFFFF8A00, 0xFFD25400, 0x00B03A00},   // gold
            {0xFFFFC9A0, 0xFFFF8142, 0xFFF04E12, 0xFFB5300A, 0x009A2400},   // red-orange
            {0xFFFFFFFF, 0xFFFFF1E2, 0xFFFFDCC0, 0xFFE9B08C, 0x00D08A60},   // calico pearl
            {0xFFFFFFFF, 0xFFFFF4EE, 0xFFFFE3DB, 0xFFF0B5B0, 0x00D88A8A}};  // tancho rose pearl
    private static final int[] EDGE = {0xFFC24A00, 0xFF9E2C08, 0xFFD39A78, 0xFFD9A09B};
    private static final int[] FIN = {0xFFFFA340, 0xFFFF6A2E, 0xFFFFC9A0, 0xFFFFB8B0};
    private static final int[] FIN2 = {0xFFFFC870, 0xFFFF9A62, 0xFFFFE9D6, 0xFFFFE0DA};
    private static final int[] PALETTE_PLAN = {0, 3, 1, 0, 2, 1, 0, 2};

    private static final int[] PEBBLES_POND = {0xFF8A8270, 0xFF6E6A5C, 0xFF9C927A, 0xFF5C5A52, 0xFF7A7462};
    private static final int[] PEBBLES_LOVE = {0xFFB5798F, 0xFF8E6AA8, 0xFFD9B5C4, 0xFF9A6C86, 0xFF6F5A88};

    // ------------------------------------------------------------------ model classes

    private static final class Tail {
        final float[] px = new float[RAYS * (LINKS + 1)];
        final float[] py = new float[RAYS * (LINKS + 1)];
        final float length;
        final float spread;
        final float phase;

        Tail(float length, float spread, float phase) {
            this.length = length;
            this.spread = spread;
            this.phase = phase;
        }
    }

    /** One goldfish. Everything it needs to draw itself is allocated once, in the constructor. */
    private static final class Goldfish {
        float x, y, ang, speed, len, cruise, sn;
        float phase, pecPhaseL, pecPhaseR, pelPhaseL, pelPhaseR;
        float dart, dartAng, attractT, attractX, attractY;
        float tx, ty, retarget;
        int palette, pairIdx, slot;
        final float[] sx = new float[N], sy = new float[N];   // IK chain
        final float[] rx = new float[N], ry = new float[N];   // rendered spine (with muscle wave)
        final float[] dx = new float[N], dy = new float[N];   // head-ward unit tangents of the rendered spine
        final float[] hw = new float[N];                      // half widths
        final float[] bj = new float[3], bo = new float[3], bs = new float[3];
        final int[] bc = new int[3];
        int blotches;
        final Tail tailA = new Tail(0.80f, 0.30f, 0f);
        final Tail tailB = new Tail(0.56f, 0.25f, 1.3f);
        // reusable geometry: reset() each frame, never re-created
        final Path body = new Path(), tailPathA = new Path(), tailPathB = new Path();
        final Path veinA = new Path(), veinB = new Path(), dorsal = new Path(), spec = new Path();
    }

    private static final class Pair {
        float cx, cy, ang, speed, tx, ty, retarget, phi, phiSpeed, d, e, seed;
    }

    private static final class Pellet {
        boolean on;
        float x, y, vy, phase, life;
    }

    private static final class Bubble {
        float x, y, r, vy, phase;
    }

    /** Pooled expanding water ripple: a pre-allocated slot that is recycled, never garbage. */
    private static final class Ripple {
        boolean active;
        float x, y, maxR, age, life;

        void start(float x, float y, float maxR, float life, float delay) {
            this.x = x;
            this.y = y;
            this.maxR = maxR;
            this.life = life;
            this.age = delay;
            this.active = true;
        }

        void update(float dt) {
            age += dt;
            if (age >= life) active = false;
        }

        /** Three concentric rings plus six twinkling sparkles on the leading edge. */
        void draw(Canvas c, Paint line, Paint fill, int ring, int spark, float density) {
            if (!active || age < 0f) return;
            float t = age / life;
            float e = 1f - (1f - t) * (1f - t);
            float rad = maxR * e;
            if (rad < 1f) return;
            float a = (1f - t) * (1f - t);
            line.setColor(ring);
            line.setStrokeWidth(density * (2.6f - 1.4f * t));
            line.setAlpha((int) (235f * a));
            c.drawCircle(x, y, rad, line);
            line.setStrokeWidth(density * 1.3f);
            line.setAlpha((int) (150f * a));
            c.drawCircle(x, y, rad * 0.78f, line);
            line.setAlpha((int) (80f * a));
            c.drawCircle(x, y, rad * 0.56f, line);
            fill.setColor(spark);
            for (int k = 0; k < 6; k++) {
                float an = k * TWO_PI / 6f + age * 1.6f;
                float tw = (float) Math.sin(age * 9f + k * 2f);
                fill.setAlpha((int) (255f * a * (0.35f + 0.65f * tw * tw)));
                c.drawCircle(x + (float) Math.cos(an) * rad, y + (float) Math.sin(an) * rad, density * (1.2f + 1.2f * a), fill);
            }
        }
    }

    // ------------------------------------------------------------------ state

    private int w, h;
    private float density = 2f;
    private float unit = 1000f;
    private float time;
    private boolean loveMode;

    private final Random rnd = new Random(1234L);
    private final Goldfish[] fish = new Goldfish[FISH_COUNT];
    private final Pair[] pairs = new Pair[FISH_COUNT / 2];
    private final Ripple[] ripples = new Ripple[MAX_RIPPLES];
    private final Pellet[] pellets = new Pellet[MAX_PELLETS];
    private final Bubble[] bubbles = new Bubble[MAX_BUBBLES];
    private final float[] linkK = new float[LINKS];

    private boolean touching;
    private float touchX, touchY, lastEmitX, lastEmitY;
    private float ambientRipple = 3f;

    // scratch (allocated once)
    private final float[] tX = new float[N], tY = new float[N], tW = new float[N];
    private final float[] uX = new float[N], uY = new float[N];
    private final float[] oX = new float[2 * N + 4], oY = new float[2 * N + 4];
    private final Path scratch = new Path();
    private final Path beamPath = new Path();
    private final RectF arcRect = new RectF();
    private final RectF pebbleRect = new RectF();

    // paints (allocated once)
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint layerPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // shaders / bitmaps: unit-space ones are positioned with canvas translate/scale and never mutate
    private final RadialGradient[] bodyShader = new RadialGradient[4];
    private final RadialGradient blotchOrange, blotchRed, pelletShader;
    private Shader bgShader, glowShader, vignetteShader, beamShader, depthShader;
    private BitmapShader causticA, causticB;
    private Bitmap backdrop;

    // ------------------------------------------------------------------ construction

    public AquariumScene(boolean loveMode) {
        this.loveMode = loveMode;
        for (int i = 0; i < fish.length; i++) fish[i] = new Goldfish();
        for (int i = 0; i < pairs.length; i++) pairs[i] = new Pair();
        for (int i = 0; i < ripples.length; i++) ripples[i] = new Ripple();
        for (int i = 0; i < pellets.length; i++) pellets[i] = new Pellet();
        for (int i = 0; i < bubbles.length; i++) bubbles[i] = new Bubble();

        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);

        for (int i = 0; i < 4; i++) {
            bodyShader[i] = new RadialGradient(-0.26f, -0.32f, 1.30f, BODY[i], BODY_STOPS, Shader.TileMode.CLAMP);
        }
        blotchOrange = new RadialGradient(0f, 0f, 1f,
                new int[]{0xFFFF7A1A, 0xF0FF6A10, 0x00FF5A00}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
        blotchRed = new RadialGradient(0f, 0f, 1f,
                new int[]{0xFFE8271B, 0xF0D81E14, 0x00C81810}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP);
        pelletShader = new RadialGradient(-0.30f, -0.30f, 1.35f,
                new int[]{0xFFFFE3A6, 0xFFD9892B, 0xFF7C4210}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);

        buildCaustics();
    }

    // ------------------------------------------------------------------ public API

    public boolean isLoveMode() {
        return loveMode;
    }

    /** Switches Love Aquarium / Natural Pond. Safe to call while running. */
    public void setLoveMode(boolean love) {
        if (love == loveMode) return;
        loveMode = love;
        buildCaustics();
        if (w > 0) {
            rebuildBackground();
            for (Goldfish f : fish) pickTarget(f);
            for (int k = 0; k < pairs.length; k++) {
                Goldfish a = fish[2 * k], b = fish[2 * k + 1];
                pairs[k].cx = (a.x + b.x) * 0.5f;
                pairs[k].cy = (a.y + b.y) * 0.5f;
                pairs[k].ang = a.ang;
                pickPairTarget(pairs[k]);
            }
        }
    }

    public void resize(int width, int height, float dens) {
        if (width <= 0 || height <= 0) return;
        boolean changed = width != w || height != h;
        w = width;
        h = height;
        density = dens;
        unit = Math.min(width, height);
        rebuildBackground();
        if (changed) {
            initFish();
            initBubbles();
            for (Pellet p : pellets) p.on = false;
            for (Ripple r : ripples) r.active = false;
        }
    }

    public void touchDown(float x, float y) {
        touching = true;
        touchX = x;
        touchY = y;
        lastEmitX = x;
        lastEmitY = y;
        startRipple(x, y, unit * 0.26f, 1.7f, 0f);
        startRipple(x, y, unit * 0.17f, 1.3f, -0.18f);
        scare(x, y);
        for (Goldfish f : fish) {                       // curious fish drift over to investigate the ripple
            float dx = f.x - x, dy = f.y - y, far = f.len * 3.2f;
            if (f.dart <= 0f && f.attractT <= 0f && dx * dx + dy * dy < far * far) {
                f.attractT = rand(3f, 5f);
                f.attractX = x;
                f.attractY = y;
            }
        }
    }

    public void touchMove(float x, float y) {
        touchX = x;
        touchY = y;
        float dx = x - lastEmitX, dy = y - lastEmitY;
        float step = unit * 0.045f;
        if (dx * dx + dy * dy > step * step) {
            startRipple(x, y, unit * 0.15f, 1.1f, 0f);
            lastEmitX = x;
            lastEmitY = y;
        }
        scare(x, y);
    }

    public void touchUp() {
        touching = false;
    }

    /** Drops a cluster of food pellets. Fish swim to them and eat them. */
    public void feed(float x, float y) {
        startRipple(x, y, unit * 0.20f, 1.4f, 0f);
        int dropped = 0;
        for (Pellet p : pellets) {
            if (p.on) continue;
            float a = rand(0f, TWO_PI);
            float r = (float) Math.sqrt(rnd.nextFloat()) * unit * 0.07f;
            p.on = true;
            p.x = x + cosf(a) * r;
            p.y = y + sinf(a) * r;
            p.vy = unit * rand(0.022f, 0.040f);
            p.phase = rand(0f, TWO_PI);
            p.life = rand(13f, 18f);
            if (++dropped >= 9) break;
        }
    }

    /**
     * @param dt      seconds since the previous drawn frame (already clamped by the caller)
     * @param dtScale dt normalised to 60 FPS (dt * 60); 1.0 at 60 Hz, 0.5 at 120 Hz, 2.0 at 30 FPS
     */
    public void update(float dt, float dtScale) {
        if (w <= 0 || dt <= 0f) return;
        time += dt;

        for (int j = 0; j < LINKS; j++) {
            linkK[j] = 1f - (float) Math.pow(1.0 - K_BASE[j], dtScale);
        }
        if (loveMode) {
            for (int k = 0; k < pairs.length; k++) updatePair(pairs[k], k, dt);
        }
        if (touching) scare(touchX, touchY);

        for (Goldfish f : fish) {
            steer(f, dt);
            solveSpine(f);
            updateTail(f, f.tailA, f.tailPathA, f.veinA);
            updateTail(f, f.tailB, f.tailPathB, f.veinB);
            buildBodyPaths(f);
        }
        updatePellets(dt);
        for (Ripple r : ripples) if (r.active) r.update(dt);
        updateBubbles(dt);

        ambientRipple -= dt;
        if (ambientRipple <= 0f) {
            ambientRipple = rand(3.5f, 8f);
            startRipple(rand(0.1f, 0.9f) * w, rand(0.1f, 0.9f) * h, unit * rand(0.10f, 0.18f), 2.2f, 0f);
        }
    }

    /** Draws one complete frame. Allocation free. */
    public void renderAquarium(Canvas c) {
        if (w <= 0) return;
        drawBackdrop(c);
        drawBubbles(c);
        for (Goldfish f : fish) drawShadow(c, f);
        for (Goldfish f : fish) drawFish(c, f);
        drawPellets(c);
        int ring = loveMode ? 0xFFFFD6EC : 0xFFCFF7FF;
        int spark = loveMode ? 0xFFFFF0F6 : 0xFFEFFFFF;
        line.setShader(null);
        fill.setShader(null);
        for (Ripple r : ripples) r.draw(c, line, fill, ring, spark, density);
    }

    // ------------------------------------------------------------------ setup helpers

    private void initFish() {
        float base = unit * 0.235f;
        for (int k = 0; k < pairs.length; k++) {
            Pair p = pairs[k];
            p.cx = w * rand(0.28f, 0.72f);
            p.cy = h * (0.16f + 0.22f * k + rand(-0.03f, 0.03f));
            p.ang = rand(0f, TWO_PI);
            p.phi = rand(0f, TWO_PI);
            p.phiSpeed = rand(0.45f, 0.70f);
            p.seed = rand(0f, 10f);
        }
        for (int i = 0; i < FISH_COUNT; i++) {
            Goldfish f = fish[i];
            f.pairIdx = i / 2;
            f.slot = i % 2;
            float sc = f.slot == 0 ? rand(0.86f, 1.18f) : fish[i - 1].len / base * rand(0.88f, 1.0f);
            f.len = base * sc;
            f.palette = PALETTE_PLAN[i];
            f.cruise = f.len * rand(0.40f, 0.62f);
            f.speed = f.cruise;
            f.phase = rand(0f, TWO_PI);
            f.pecPhaseL = rand(0f, TWO_PI);
            f.pecPhaseR = rand(0f, TWO_PI);
            f.pelPhaseL = rand(0f, TWO_PI);
            f.pelPhaseR = rand(0f, TWO_PI);
            f.dart = 0f;
            f.attractT = 0f;
            for (int j = 0; j < N; j++) f.hw[j] = WIDTH[j] * f.len * 0.205f;

            f.blotches = 0;
            if (f.palette == 2) {
                f.blotches = 3;
                f.bj[0] = rand(1.1f, 2.0f); f.bo[0] = rand(-0.3f, 0.3f); f.bs[0] = rand(0.85f, 1.15f); f.bc[0] = 1;
                f.bj[1] = rand(3.7f, 4.7f); f.bo[1] = rand(-0.35f, 0.35f); f.bs[1] = rand(0.85f, 1.2f); f.bc[1] = 0;
                f.bj[2] = rand(6.3f, 7.6f); f.bo[2] = rand(-0.3f, 0.3f); f.bs[2] = rand(0.8f, 1.1f); f.bc[2] = 0;
            } else if (f.palette == 3) {
                f.blotches = 1;
                f.bj[0] = 1.3f; f.bo[0] = 0f; f.bs[0] = 1.0f; f.bc[0] = 1;
            }

            Pair p = pairs[f.pairIdx];
            float sgn = f.slot == 0 ? 1f : -1f;
            f.ang = p.ang;
            f.x = p.cx - sinf(p.ang) * f.len * 0.55f * sgn;
            f.y = p.cy + cosf(p.ang) * f.len * 0.55f * sgn;
            f.sn = 0.5f;
            float seg = f.len / (N - 1);
            for (int j = 0; j < N; j++) {
                f.sx[j] = f.x - cosf(f.ang) * seg * j;
                f.sy[j] = f.y - sinf(f.ang) * seg * j;
            }
            pickTarget(f);
            solveSpine(f);
            resetTail(f, f.tailA);
            resetTail(f, f.tailB);
            buildBodyPaths(f);
        }
        float avg = 0f;
        for (Goldfish f : fish) avg += f.len;
        avg /= FISH_COUNT;
        for (Pair p : pairs) {
            p.speed = avg * 0.34f;
            p.d = avg * 0.52f;
            p.e = avg * 0.62f;
            pickPairTarget(p);
        }
    }

    private void initBubbles() {
        for (Bubble b : bubbles) {
            b.x = rand(0f, w);
            b.y = rand(0f, h);
            b.r = density * rand(1.5f, 5f);
            b.vy = unit * rand(0.025f, 0.075f);
            b.phase = rand(0f, TWO_PI);
        }
    }

    /** Builds the cached shaders and bakes gradient, sand and pebbles into one bitmap. Runs on resize / theme change only. */
    private void rebuildBackground() {
        int[] bg;
        int[] glow;
        int[] beam;
        if (loveMode) {
            bg = new int[]{0xFF160730, 0xFF33124F, 0xFF5E2060, 0xFF8C3A6B};
            glow = new int[]{0x5CFF9EC8, 0x00FF9EC8};
            beam = new int[]{0x34FFD6EC, 0x00FFD6EC};
        } else {
            bg = new int[]{0xFF012A33, 0xFF04515E, 0xFF07757F, 0xFF0C9DA3};
            glow = new int[]{0x5078F0E8, 0x0078F0E8};
            beam = new int[]{0x30D2FFFF, 0x00D2FFFF};
        }
        bgShader = new LinearGradient(0f, 0f, 0f, h, bg, new float[]{0f, 0.45f, 0.8f, 1f}, Shader.TileMode.CLAMP);
        glowShader = new RadialGradient(w * 0.5f, -h * 0.04f, h * 0.85f, glow, null, Shader.TileMode.CLAMP);
        beamShader = new LinearGradient(0f, 0f, 0f, h * 0.85f, beam, null, Shader.TileMode.CLAMP);
        float diag = (float) Math.sqrt((double) w * w + (double) h * h);
        vignetteShader = new RadialGradient(w * 0.5f, h * 0.5f, diag * 0.62f,
                new int[]{0x00000000, 0x00000000, 0x74000014}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        depthShader = new LinearGradient(0f, 0f, 0f, h, new int[]{0x00000000, 0x3A000018}, null, Shader.TileMode.CLAMP);

        backdrop = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas bc = new Canvas(backdrop);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(bgShader);
        bc.drawRect(0f, 0f, w, h, p);
        p.setShader(glowShader);
        bc.drawRect(0f, 0f, w, h, p);
        p.setShader(null);

        Random r = new Random(77L);
        int[] pal = loveMode ? PEBBLES_LOVE : PEBBLES_POND;
        int specks = (int) (w * (long) h / 2600L);                       // fine sand grain
        for (int i = 0; i < specks; i++) {
            p.setColor(r.nextBoolean() ? 0xFFFFFFFF : 0xFF000000);
            p.setAlpha(10 + r.nextInt(26));
            bc.drawCircle(r.nextFloat() * w, r.nextFloat() * h, 0.6f + r.nextFloat() * 1.2f, p);
        }
        int count = Math.max(30, (int) (w * (long) h / (unit * unit * 0.030f)));
        for (int i = 0; i < count; i++) {                                 // soft-shadowed pebbles
            float rx = unit * (0.012f + 0.050f * r.nextFloat() * r.nextFloat());
            float ry = rx * (0.60f + 0.30f * r.nextFloat());
            float px = r.nextFloat() * w, py = r.nextFloat() * h;
            int base = pal[r.nextInt(pal.length)];
            bc.save();
            bc.translate(px, py);
            bc.rotate(r.nextFloat() * 180f);
            pebbleRect.set(-rx, -ry, rx, ry);
            p.setMaskFilter(new BlurMaskFilter(Math.max(2f, rx * 0.55f), BlurMaskFilter.Blur.NORMAL));
            p.setColor(0x66000010);
            bc.save();
            bc.translate(rx * 0.25f, ry * 0.45f);
            bc.drawOval(pebbleRect, p);
            bc.restore();
            p.setMaskFilter(null);
            int light = lighten(base, 0.35f), dark = darken(base, 0.55f);
            p.setShader(new RadialGradient(-rx * 0.35f, -ry * 0.40f, rx * 1.5f,
                    new int[]{light, base, dark}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            bc.drawOval(pebbleRect, p);
            p.setShader(null);
            p.setColor(0x55FFFFFF);
            pebbleRect.set(-rx * 0.55f, -ry * 0.62f, -rx * 0.05f, -ry * 0.22f);
            bc.drawOval(pebbleRect, p);
            bc.restore();
        }
    }

    private static int lighten(int c, float t) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return 0xFF000000 | ((int) (r + (255 - r) * t) << 16) | ((int) (g + (255 - g) * t) << 8) | (int) (b + (255 - b) * t);
    }

    private static int darken(int c, float t) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return 0xFF000000 | ((int) (r * (1f - t)) << 16) | ((int) (g * (1f - t)) << 8) | (int) (b * (1f - t));
    }

    /** Tileable caustic web: bright lines where interfering sine fields cross zero. */
    private void buildCaustics() {
        final int s = 160;
        int[] px = new int[s * s];
        int r = loveMode ? 255 : 205;
        int g = loveMode ? 190 : 255;
        int b = loveMode ? 222 : 255;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                float u = x / (float) s * TWO_PI;
                float v = y / (float) s * TWO_PI;
                float a = sinf(u * 2f + 1.4f * sinf(v * 3f + 0.6f));
                float bb = sinf(v * 2f + 1.4f * sinf(u * 3f + 2.1f));
                float cc = sinf(u + v + 1.2f * sinf(u * 2f - v * 2f));
                float val = (a + bb + cc) / 3f;
                float ln = 1f - Math.abs(val) / 0.12f;
                if (ln < 0f) ln = 0f;
                ln = ln * (float) Math.sqrt(ln);
                int alpha = (int) (ln * 255f);
                px[y * s + x] = (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        bmp.setPixels(px, 0, s, 0, 0, s, s);
        causticA = new BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        causticB = new BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
    }

    // ------------------------------------------------------------------ simulation

    private void startRipple(float x, float y, float maxR, float life, float delay) {
        for (Ripple r : ripples) {
            if (!r.active) {
                r.start(x, y, maxR, life, delay);
                return;
            }
        }
    }

    private void pickTarget(Goldfish f) {
        float mx = Math.min(f.len * 0.9f, w * 0.4f);
        float my = Math.min(f.len * 0.9f, h * 0.4f);
        f.tx = rand(mx, w - mx);
        f.ty = rand(my, h - my);
        f.retarget = rand(5f, 11f);
    }

    private void pickPairTarget(Pair p) {
        float mx = Math.min(unit * 0.22f, w * 0.4f);
        float my = Math.min(unit * 0.22f, h * 0.4f);
        p.tx = rand(mx, w - mx);
        p.ty = rand(my, h - my);
        p.retarget = rand(8f, 16f);
    }

    private void updatePair(Pair p, int k, float dt) {
        p.retarget -= dt;
        float ddx = p.tx - p.cx, ddy = p.ty - p.cy;
        float reach = unit * 0.12f;
        if (p.retarget <= 0f || ddx * ddx + ddy * ddy < reach * reach) {
            pickPairTarget(p);
            ddx = p.tx - p.cx;
            ddy = p.ty - p.cy;
        }
        float d = (float) Math.sqrt(ddx * ddx + ddy * ddy);
        float vx = d > 1e-3f ? ddx / d : 0f, vy = d > 1e-3f ? ddy / d : 0f;
        float m = unit * 0.20f;
        if (p.cx < m) vx += 2.2f * (m - p.cx) / m; else if (p.cx > w - m) vx -= 2.2f * (p.cx - (w - m)) / m;
        if (p.cy < m) vy += 2.2f * (m - p.cy) / m; else if (p.cy > h - m) vy -= 2.2f * (p.cy - (h - m)) / m;
        float aim = (float) Math.atan2(vy, vx);
        float diff = wrapAngle(aim - p.ang);
        float maxTurn = 0.5f * dt;
        p.ang += clamp(diff, -maxTurn, maxTurn);
        p.cx += cosf(p.ang) * p.speed * dt;
        p.cy += sinf(p.ang) * p.speed * dt;

        Goldfish a = fish[2 * k], b = fish[2 * k + 1];
        float mx = (a.x + b.x) * 0.5f, my = (a.y + b.y) * 0.5f;
        float sx = mx - p.cx, sy = my - p.cy;
        float far = (a.len + b.len) * 0.8f;
        if (sx * sx + sy * sy > far * far) {
            p.cx += sx * 0.6f * dt;
            p.cy += sy * 0.6f * dt;
        }
        p.phi += dt * p.phiSpeed * (0.35f + 0.65f * (0.5f + 0.5f * sinf(time * 0.23f + p.seed)));
    }

    private float aimAt(Goldfish f, float tx, float ty, float wallK) {
        float vx = tx - f.x, vy = ty - f.y;
        float d = (float) Math.sqrt(vx * vx + vy * vy);
        if (d > 1e-3f) {
            vx /= d;
            vy /= d;
        }
        float m = f.len * 0.8f;
        if (f.x < m) vx += 2.2f * wallK * (m - f.x) / m; else if (f.x > w - m) vx -= 2.2f * wallK * (f.x - (w - m)) / m;
        if (f.y < m) vy += 2.2f * wallK * (m - f.y) / m; else if (f.y > h - m) vy -= 2.2f * wallK * (f.y - (h - m)) / m;
        return (float) Math.atan2(vy, vx);
    }

    private int nearestPellet(Goldfish f) {
        int best = -1;
        float bd = unit * unit * 0.9f;
        for (int i = 0; i < pellets.length; i++) {
            Pellet pl = pellets[i];
            if (!pl.on) continue;
            float dx = pl.x - f.x, dy = pl.y - f.y;
            float d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        return best;
    }

    private void steer(Goldfish f, float dt) {
        float desiredSpeed = f.cruise;
        float aim;
        float turnBoost = 1f;
        boolean fleeing = f.dart > 0f;
        int pi = fleeing ? -1 : nearestPellet(f);

        if (fleeing) {
            f.dart -= dt;
            aim = f.dartAng;
            desiredSpeed = f.len * 2.1f;
            turnBoost = 2.4f;
        } else if (pi >= 0) {
            Pellet pl = pellets[pi];
            aim = aimAt(f, pl.x, pl.y, 0.25f);
            desiredSpeed = f.len * 0.95f;
            float ddx = pl.x - f.x, ddy = pl.y - f.y;
            float d2 = ddx * ddx + ddy * ddy;
            float eatR = f.len * 0.17f;
            if (d2 < eatR * eatR) {
                pl.on = false;
                startRipple(pl.x, pl.y, unit * 0.07f, 0.9f, 0f);
            } else if (d2 < f.len * f.len) {
                desiredSpeed = f.len * 0.45f;
            }
        } else if (f.attractT > 0f) {
            f.attractT -= dt;
            aim = aimAt(f, f.attractX, f.attractY, 0.8f);
            desiredSpeed = f.len * 0.70f;
            float ddx = f.attractX - f.x, ddy = f.attractY - f.y;
            if (ddx * ddx + ddy * ddy < f.len * f.len * 0.5f) f.attractT = 0f;
        } else if (loveMode) {
            Pair p = pairs[f.pairIdx];
            float fx = cosf(p.ang), fy = sinf(p.ang);
            float sgn = f.slot == 0 ? 1f : -1f;
            float sp = sinf(p.phi), cp = cosf(p.phi);
            float tx = p.cx + (fx * p.e * sp - fy * p.d * cp) * sgn;
            float ty = p.cy + (fy * p.e * sp + fx * p.d * cp) * sgn;
            float ddx = tx - f.x, ddy = ty - f.y;
            float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy);
            float toTarget = aimAt(f, tx, ty, 0.6f);
            float align = clamp(1f - dist / (f.len * 0.9f), 0f, 0.85f);
            aim = toTarget + wrapAngle(p.ang - toTarget) * align;
            desiredSpeed = clamp(p.speed + dist * 1.4f, f.len * 0.22f, f.len * 1.15f);
        } else {
            f.retarget -= dt;
            float ddx = f.tx - f.x, ddy = f.ty - f.y;
            float reach = f.len * 0.9f;
            if (f.retarget <= 0f || ddx * ddx + ddy * ddy < reach * reach) pickTarget(f);
            aim = aimAt(f, f.tx, f.ty, 1f);
            desiredSpeed = f.cruise * (0.85f + 0.3f * sinf(time * 0.5f + f.len));
        }

        float diff = wrapAngle(aim - f.ang);
        float maxTurn = clamp(f.speed / (f.len * 0.42f), 0.9f, 4.0f) * turnBoost * dt;
        f.ang += clamp(diff, -maxTurn, maxTurn);
        if (!fleeing) desiredSpeed *= 0.62f + 0.38f * Math.max(0f, cosf(diff));
        float rate = fleeing ? 7f : 1.8f;
        f.speed += (desiredSpeed - f.speed) * (1f - (float) Math.exp(-rate * dt));
        f.x += cosf(f.ang) * f.speed * dt;
        f.y += sinf(f.ang) * f.speed * dt;
        float lim = f.len * 0.3f;
        f.x = clamp(f.x, -lim, w + lim);
        f.y = clamp(f.y, -lim, h + lim);

        f.sn = clamp(f.speed / (f.len * 1.1f), 0f, 1.6f);
        f.phase += dt * TWO_PI * (0.55f + 1.55f * f.sn);
        f.pecPhaseL += dt * TWO_PI * (1.10f + 0.70f * f.sn);
        f.pecPhaseR += dt * TWO_PI * (0.93f + 0.80f * f.sn);
        f.pelPhaseL += dt * TWO_PI * (0.70f + 0.40f * f.sn);
        f.pelPhaseR += dt * TWO_PI * (0.82f + 0.35f * f.sn);
    }

    /** Makes any fish whose body is close to (x, y) dart away. */
    private void scare(float x, float y) {
        for (Goldfish f : fish) {
            if (f.dart > 0.2f) continue;
            float best = Float.MAX_VALUE;
            for (int i = 0; i < N; i += 3) {
                float dx = f.sx[i] - x, dy = f.sy[i] - y;
                float d = dx * dx + dy * dy;
                if (d < best) best = d;
            }
            float r = f.len * 0.42f;
            if (best < r * r) {
                f.dart = rand(0.6f, 0.95f);
                f.dartAng = (float) Math.atan2(f.y - y, f.x - x) + rand(-0.5f, 0.5f);
                f.attractT = 0f;
            }
        }
    }

    /** IK chain (each joint dragged to a fixed distance from its parent) + travelling muscle wave. */
    private void solveSpine(Goldfish f) {
        final float seg = f.len / (N - 1);
        f.sx[0] = f.x;
        f.sy[0] = f.y;
        for (int i = 1; i < N; i++) {
            float dx = f.sx[i] - f.sx[i - 1], dy = f.sy[i] - f.sy[i - 1];
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 1e-4f) {
                dx = -cosf(f.ang);
                dy = -sinf(f.ang);
                d = 1f;
            }
            float k = seg / d;
            f.sx[i] = f.sx[i - 1] + dx * k;
            f.sy[i] = f.sy[i - 1] + dy * k;
        }
        for (int i = 0; i < N; i++) {
            int a = i == 0 ? 0 : i - 1;
            int b = i == N - 1 ? N - 1 : i + 1;
            float dx = f.sx[a] - f.sx[b], dy = f.sy[a] - f.sy[b];
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 1e-4f) {
                dx = cosf(f.ang);
                dy = sinf(f.ang);
                d = 1f;
            }
            uX[i] = dx / d;
            uY[i] = dy / d;
        }
        float amp = f.len * 0.075f * (0.28f + 0.72f * Math.min(1f, f.sn));
        for (int i = 0; i < N; i++) {
            float t = i / (float) (N - 1);
            float env = 0.10f + 0.90f * t * t;
            float off = amp * env * sinf(f.phase - i * 0.496f);
            f.rx[i] = f.sx[i] - uY[i] * off;
            f.ry[i] = f.sy[i] + uX[i] * off;
        }
        for (int i = 0; i < N; i++) {
            int a = i == 0 ? 0 : i - 1;
            int b = i == N - 1 ? N - 1 : i + 1;
            float dx = f.rx[a] - f.rx[b], dy = f.ry[a] - f.ry[b];
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 1e-4f) {
                dx = uX[i];
                dy = uY[i];
                d = 1f;
            }
            f.dx[i] = dx / d;
            f.dy[i] = dy / d;
        }
    }

    private void resetTail(Goldfish f, Tail t) {
        final int s = LINKS + 1;
        float bx = -f.dx[N - 1], by = -f.dy[N - 1];
        float baseAng = (float) Math.atan2(by, bx);
        float tailLen = f.len * t.length;
        for (int r = 0; r < RAYS; r++) {
            float restA = baseAng + (r - (RAYS - 1) * 0.5f) * t.spread;
            float linkLen = tailLen / LINKS * RAY_LEN[r];
            int o = r * s;
            t.px[o] = f.rx[N - 1];
            t.py[o] = f.ry[N - 1];
            for (int j = 1; j <= LINKS; j++) {
                t.px[o + j] = t.px[o + j - 1] + cosf(restA) * linkLen;
                t.py[o + j] = t.py[o + j - 1] + sinf(restA) * linkLen;
            }
        }
    }

    /** Ray chains whose link directions lag behind their rest pose = fluid drag. Paths are reset, not re-created. */
    private void updateTail(Goldfish f, Tail t, Path outline, Path veins) {
        final int s = LINKS + 1;
        float rootX = f.rx[N - 1], rootY = f.ry[N - 1];
        float bx = rootX - f.rx[N - 2], by = rootY - f.ry[N - 2];
        float baseAng = (float) Math.atan2(by, bx);
        float tailLen = f.len * t.length;
        float flutAmp = 0.10f + 0.20f * Math.min(f.sn, 1.2f);

        veins.reset();
        for (int r = 0; r < RAYS; r++) {
            float restA = baseAng + (r - (RAYS - 1) * 0.5f) * t.spread;
            float linkLen = tailLen / LINKS * RAY_LEN[r];
            int o = r * s;
            t.px[o] = rootX;
            t.py[o] = rootY;
            veins.moveTo(rootX, rootY);
            for (int j = 1; j <= LINKS; j++) {
                int idx = o + j;
                float vx = t.px[idx] - t.px[idx - 1], vy = t.py[idx] - t.py[idx - 1];
                float vl = (float) Math.sqrt(vx * vx + vy * vy);
                if (vl < 1e-3f) {
                    vx = cosf(restA);
                    vy = sinf(restA);
                } else {
                    vx /= vl;
                    vy /= vl;
                }
                float jf = j / (float) LINKS;
                float fl = sinf(f.phase - j * 0.85f + r * 0.45f + t.phase) * flutAmp * jf
                        + sinf(time * 1.7f + r * 0.9f + j * 0.6f + t.phase) * 0.05f * jf;
                float ra = restA + fl;
                float k = linkK[j - 1];
                vx += (cosf(ra) - vx) * k;
                vy += (sinf(ra) - vy) * k;
                float nl = (float) Math.sqrt(vx * vx + vy * vy);
                if (nl < 1e-4f) {
                    vx = cosf(ra);
                    vy = sinf(ra);
                    nl = 1f;
                }
                t.px[idx] = t.px[idx - 1] + vx / nl * linkLen;
                t.py[idx] = t.py[idx - 1] + vy / nl * linkLen;
                veins.lineTo(t.px[idx], t.py[idx]);
            }
        }

        outline.reset();
        outline.moveTo(rootX, rootY);
        for (int j = 1; j <= LINKS; j++) outline.lineTo(t.px[j], t.py[j]);
        for (int r = 0; r < RAYS - 1; r++) {
            float ax = t.px[r * s + LINKS], ay = t.py[r * s + LINKS];
            float cx = t.px[(r + 1) * s + LINKS], cy = t.py[(r + 1) * s + LINKS];
            float mx = (ax + cx) * 0.5f, my = (ay + cy) * 0.5f;
            outline.quadTo(mx + (mx - rootX) * 0.10f, my + (my - rootY) * 0.10f, cx, cy);
        }
        for (int j = LINKS - 1; j >= 1; j--) outline.lineTo(t.px[(RAYS - 1) * s + j], t.py[(RAYS - 1) * s + j]);
        outline.close();
    }

    /** Smooth closed ribbon along a polyline with per-point half widths (reuses the given path). */
    private void ribbon(Path path, float[] xs, float[] ys, float[] ws, int n, float noseExt, float tailExt) {
        for (int i = 0; i < n; i++) {
            int a = i == 0 ? 0 : i - 1;
            int b = i == n - 1 ? n - 1 : i + 1;
            float dx = xs[a] - xs[b], dy = ys[a] - ys[b];
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d < 1e-4f) {
                dx = 1f;
                dy = 0f;
                d = 1f;
            }
            uX[i] = dx / d;
            uY[i] = dy / d;
        }
        int m = 0;
        oX[m] = xs[0] + uX[0] * noseExt;
        oY[m] = ys[0] + uY[0] * noseExt;
        m++;
        for (int i = 0; i < n; i++) {
            oX[m] = xs[i] - uY[i] * ws[i];
            oY[m] = ys[i] + uX[i] * ws[i];
            m++;
        }
        oX[m] = xs[n - 1] - uX[n - 1] * tailExt;
        oY[m] = ys[n - 1] - uY[n - 1] * tailExt;
        m++;
        for (int i = n - 1; i >= 0; i--) {
            oX[m] = xs[i] + uY[i] * ws[i];
            oY[m] = ys[i] - uX[i] * ws[i];
            m++;
        }
        path.reset();
        path.moveTo((oX[m - 1] + oX[0]) * 0.5f, (oY[m - 1] + oY[0]) * 0.5f);
        for (int k = 0; k < m; k++) {
            int k2 = (k + 1) % m;
            path.quadTo(oX[k], oY[k], (oX[k] + oX[k2]) * 0.5f, (oY[k] + oY[k2]) * 0.5f);
        }
        path.close();
    }

    private void buildBodyPaths(Goldfish f) {
        for (int i = 0; i < N; i++) {
            tX[i] = f.rx[i];
            tY[i] = f.ry[i];
            tW[i] = f.hw[i] * 1.06f;
        }
        ribbon(f.body, tX, tY, tW, N, f.hw[0] * 0.85f, f.hw[N - 1] * 0.5f);

        // dorsal crest: thin swaying ribbon along the back (joints 3..9)
        final int dn = 7;
        for (int k = 0; k < dn; k++) {
            int i = 3 + k;
            float sway = sinf(time * 2.0f * (0.6f + f.sn) - i * 0.7f) * f.hw[i] * 0.28f * (k / (float) (dn - 1) + 0.2f);
            tX[k] = f.rx[i] - f.dy[i] * sway;
            tY[k] = f.ry[i] + f.dx[i] * sway;
            tW[k] = f.hw[i] * (0.06f + 0.16f * sinf(PI * (k + 0.5f) / dn));
        }
        ribbon(f.dorsal, tX, tY, tW, dn, 0f, 0f);

        // specular lens (light from the top-left): joints 1..8
        final int sn = 8;
        for (int k = 0; k < sn; k++) {
            int i = k + 1;
            tX[k] = f.rx[i] - 0.30f * f.hw[i];
            tY[k] = f.ry[i] - 0.42f * f.hw[i];
            tW[k] = f.hw[i] * 0.17f * sinf(PI * (k + 0.7f) / (sn + 0.7f));
        }
        ribbon(f.spec, tX, tY, tW, sn, 0f, 0f);
    }

    private void updatePellets(float dt) {
        float floor = h - unit * 0.08f;
        for (Pellet p : pellets) {
            if (!p.on) continue;
            p.life -= dt;
            if (p.life <= 0f) {
                p.on = false;
                continue;
            }
            if (p.y < floor) p.y += p.vy * dt; else p.life -= dt * 1.5f;
            p.x += sinf(time * 2f + p.phase) * unit * 0.010f * dt * 8f;
        }
    }

    private void updateBubbles(float dt) {
        for (Bubble b : bubbles) {
            b.y -= b.vy * dt;
            b.x += sinf(time * 1.3f + b.phase) * density * 7f * dt;
            if (b.y < -b.r * 2f) {
                b.y = h + b.r * 2f;
                b.x = rand(0f, w);
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    /** Two drifting caustic layers. Positioned with the canvas matrix, so the shader is never mutated. */
    private void causticLayer(Canvas c, BitmapShader sh, float scale, float rotDeg, float ox, float oy, int alpha) {
        layerPaint.setShader(sh);
        layerPaint.setAlpha(alpha);
        c.save();
        c.rotate(rotDeg);
        c.scale(scale, scale);
        c.translate(ox, oy);
        float ext = (w + h) * 1.6f / scale;
        c.drawRect(-ext, -ext, ext, ext, layerPaint);
        c.restore();
    }

    private void drawBackdrop(Canvas c) {
        c.drawBitmap(backdrop, 0f, 0f, bitmapPaint);

        float s1 = unit * 0.55f / 160f;
        causticLayer(c, causticA, s1, 8f, (time * 8f) % 160f, (time * 4.5f) % 160f, 76);
        causticLayer(c, causticB, s1 * 0.68f, -17f, (-time * 6f) % 160f, (time * 7.5f) % 160f, 50);

        fill.setStyle(Paint.Style.FILL);
        fill.setShader(beamShader);
        for (int i = 0; i < 4; i++) {                                   // soft light rays from the surface
            float cx = w * (0.12f + 0.24f * i) + sinf(time * 0.15f + i * 1.7f) * w * 0.05f;
            float topW = w * 0.045f * (1f + 0.4f * sinf(time * 0.3f + i));
            float botW = w * 0.15f;
            float shift = h * 0.22f;
            beamPath.reset();
            beamPath.moveTo(cx - topW, 0f);
            beamPath.lineTo(cx + topW, 0f);
            beamPath.lineTo(cx + shift + botW, h * 0.85f);
            beamPath.lineTo(cx + shift - botW, h * 0.85f);
            beamPath.close();
            fill.setAlpha(120 + (int) (100f * (0.5f + 0.5f * sinf(time * 0.4f + i * 2.1f))));
            c.drawPath(beamPath, fill);
        }
        fill.setAlpha(255);
        fill.setShader(depthShader);
        c.drawRect(0f, 0f, w, h, fill);
        fill.setShader(vignetteShader);
        c.drawRect(0f, 0f, w, h, fill);
        fill.setShader(null);
    }

    private void drawBubbles(Canvas c) {
        int tint = loveMode ? 0xFFFFD0E8 : 0xFFCFF4FF;
        line.setShader(null);
        fill.setShader(null);
        for (Bubble b : bubbles) {
            fill.setColor(argb(tint, 16));
            c.drawCircle(b.x, b.y, b.r, fill);
            line.setStrokeWidth(density * 0.9f);
            line.setColor(argb(tint, 80));
            c.drawCircle(b.x, b.y, b.r, line);
            fill.setColor(argb(0xFFFFFF, 130));
            c.drawCircle(b.x - b.r * 0.35f, b.y - b.r * 0.38f, Math.max(0.8f, b.r * 0.2f), fill);
        }
    }

    /** Soft layered drop shadow on the pebble bed; deeper-looking offset than the fish body. */
    private void drawShadow(Canvas c, Goldfish f) {
        c.save();
        c.translate(f.len * 0.13f, f.len * 0.20f);
        fill.setShader(null);
        line.setShader(null);
        line.setStrokeWidth(f.len * 0.11f);
        line.setColor(0x10000012);
        c.drawPath(f.body, line);
        fill.setColor(0x30000012);
        c.drawPath(f.body, fill);
        fill.setColor(0x1C000012);
        c.drawPath(f.tailPathA, fill);
        c.restore();
    }

    private void drawFish(Canvas c, Goldfish f) {
        final int p = f.palette;
        final float len = f.len;
        fill.setShader(null);
        fill.setStyle(Paint.Style.FILL);
        line.setShader(null);

        // 1) double-veil caudal fin: long pale layer, then a shorter saturated layer
        fill.setColor(argb(FIN2[p], 92));
        c.drawPath(f.tailPathA, fill);
        fill.setColor(argb(FIN[p], 112));
        c.drawPath(f.tailPathB, fill);
        line.setStrokeWidth(density * 0.9f);
        line.setColor(argb(FIN[p], 80));
        c.drawPath(f.veinA, line);
        line.setColor(argb(EDGE[p], 70));
        c.drawPath(f.veinB, line);
        line.setStrokeWidth(density * 1.2f);
        line.setColor(argb(0xFFFFFF, 34));
        c.drawPath(f.tailPathA, line);

        // 2) pelvic fins sculling independently, tucked under the body
        float pelBase = 0.78f - 0.18f * Math.min(1f, f.sn);
        drawFin(c, f, 4, 1, 0.70f, pelBase + 0.30f * sinf(f.pelPhaseL), len * 0.15f, f.pelPhaseL + 1f, p, 0.9f);
        drawFin(c, f, 4, -1, 0.70f, pelBase + 0.30f * sinf(f.pelPhaseR), len * 0.15f, f.pelPhaseR + 2f, p, 0.9f);

        // 3) body: dark rim silhouette, then 3D-shaded discs from tail to head
        fill.setShader(null);
        fill.setColor(EDGE[p]);
        c.drawPath(f.body, fill);

        fill.setShader(bodyShader[p]);
        fill.setAlpha(255);
        for (int k = (N - 1) * 3; k >= 0; k--) {
            float u = k / 3f;
            int i = (int) u;
            if (i >= N - 1) i = N - 2;
            float t = u - i;
            float cx = f.rx[i] + (f.rx[i + 1] - f.rx[i]) * t;
            float cy = f.ry[i] + (f.ry[i + 1] - f.ry[i]) * t;
            float r = (f.hw[i] + (f.hw[i + 1] - f.hw[i]) * t) * 0.94f;
            c.save();
            c.translate(cx, cy);
            c.scale(r, r);
            c.drawCircle(0f, 0f, 1f, fill);
            c.restore();
        }

        // colour blotches (calico / tancho)
        for (int b = 0; b < f.blotches; b++) {
            float u = f.bj[b];
            int i = (int) u;
            if (i >= N - 1) i = N - 2;
            float t = u - i;
            float hwHere = f.hw[i] + (f.hw[i + 1] - f.hw[i]) * t;
            float cx = f.rx[i] + (f.rx[i + 1] - f.rx[i]) * t - f.dy[i] * f.bo[b] * hwHere;
            float cy = f.ry[i] + (f.ry[i + 1] - f.ry[i]) * t + f.dx[i] * f.bo[b] * hwHere;
            fill.setShader(f.bc[b] == 1 ? blotchRed : blotchOrange);
            fill.setAlpha(225);
            c.save();
            c.translate(cx, cy);
            c.scale(f.bs[b] * hwHere, f.bs[b] * hwHere);
            c.drawCircle(0f, 0f, 1f, fill);
            c.restore();
        }
        fill.setShader(null);

        // scale crescents (joints 2..8)
        line.setStrokeWidth(density * 0.9f);
        line.setColor(argb(EDGE[p], 44));
        for (int i = 2; i <= 8; i++) {
            float hwI = f.hw[i];
            float r = Math.min(len / (N - 1) * 0.62f, hwI * 0.40f);
            int cnt = (i & 1) == 0 ? 3 : 2;
            float backDeg = (float) Math.toDegrees(Math.atan2(-f.dy[i], -f.dx[i]));
            for (int s = 0; s < cnt; s++) {
                float off = cnt == 3 ? (s - 1) * 0.58f : (s - 0.5f) * 0.58f;
                float cx = f.rx[i] - f.dy[i] * off * hwI;
                float cy = f.ry[i] + f.dx[i] * off * hwI;
                arcRect.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(arcRect, backDeg - 68f, 136f, false, line);
            }
        }

        // specular lens + catch-light on the head
        fill.setColor(argb(0xFFFFFF, 88));
        c.drawPath(f.spec, fill);
        fill.setColor(argb(0xFFFFFF, 170));
        c.drawCircle(f.rx[2] - 0.30f * f.hw[2], f.ry[2] - 0.42f * f.hw[2], f.hw[2] * 0.13f, fill);

        // gill cover
        float nx2 = -f.dy[2], ny2 = f.dx[2];
        float gx = f.rx[3] + (f.rx[3] - f.rx[2]) * 0.4f, gy = f.ry[3] + (f.ry[3] - f.ry[2]) * 0.4f;
        scratch.reset();
        scratch.moveTo(f.rx[2] + nx2 * f.hw[2] * 0.88f, f.ry[2] + ny2 * f.hw[2] * 0.88f);
        scratch.quadTo(gx, gy, f.rx[2] - nx2 * f.hw[2] * 0.88f, f.ry[2] - ny2 * f.hw[2] * 0.88f);
        line.setStrokeWidth(density * 1.2f);
        line.setColor(argb(EDGE[p], 78));
        c.drawPath(scratch, line);

        // dorsal crest
        fill.setColor(argb(FIN2[p], 125));
        c.drawPath(f.dorsal, fill);
        line.setStrokeWidth(density * 0.8f);
        line.setColor(argb(FIN[p], 150));
        c.drawPath(f.dorsal, line);

        // 4) pectoral fins on top of the body edge, sculling independently
        float pecBase = 1.02f - 0.42f * Math.min(1f, f.sn);
        drawFin(c, f, 2, 1, 0.92f, pecBase + 0.40f * sinf(f.pecPhaseL), len * 0.21f, f.pecPhaseL + 0.6f, p, 1f);
        drawFin(c, f, 2, -1, 0.92f, pecBase + 0.40f * sinf(f.pecPhaseR), len * 0.21f, f.pecPhaseR + 1.7f, p, 1f);

        // 5) eyes
        final int ei = 1;
        for (int s = -1; s <= 1; s += 2) {
            float ex = f.rx[ei] - f.dy[ei] * s * f.hw[ei] * 0.80f;
            float ey = f.ry[ei] + f.dx[ei] * s * f.hw[ei] * 0.80f;
            float er = f.hw[ei] * 0.24f;
            fill.setColor(argb(EDGE[p], 190));
            c.drawCircle(ex, ey, er * 1.35f, fill);
            fill.setColor(0xFF140A10);
            c.drawCircle(ex, ey, er, fill);
            line.setStrokeWidth(er * 0.22f);
            line.setColor(0xFFE0A030);
            c.drawCircle(ex, ey, er * 0.92f, line);
            fill.setColor(0xF0FFFFFF);
            c.drawCircle(ex - er * 0.32f, ey - er * 0.38f, er * 0.30f, fill);
        }
    }

    private void drawFin(Canvas c, Goldfish f, int j, int side, float lat, float ang, float length,
                         float flut, int p, float alphaMul) {
        float bx = -f.dx[j], by = -f.dy[j];
        float sx = -f.dy[j] * side, sy = f.dx[j] * side;
        float hwj = f.hw[j];
        float rx = f.rx[j] + sx * hwj * lat, ry = f.ry[j] + sy * hwj * lat;

        float a0 = ang - 0.46f, a2 = ang + 0.42f;
        float e0x = rx + (bx * cosf(a0) + sx * sinf(a0)) * length * 0.80f;
        float e0y = ry + (by * cosf(a0) + sy * sinf(a0)) * length * 0.80f;
        float d1x = bx * cosf(ang) + sx * sinf(ang);
        float d1y = by * cosf(ang) + sy * sinf(ang);
        float flutter = sinf(flut) * length * 0.10f;
        float e1x = rx + d1x * length - d1y * flutter;
        float e1y = ry + d1y * length + d1x * flutter;
        float e2x = rx + (bx * cosf(a2) + sx * sinf(a2)) * length * 0.84f;
        float e2y = ry + (by * cosf(a2) + sy * sinf(a2)) * length * 0.84f;

        float ka = ang - 0.23f, kb = ang + 0.21f;
        float k0x = rx + (bx * cosf(ka) + sx * sinf(ka)) * length * 1.05f;
        float k0y = ry + (by * cosf(ka) + sy * sinf(ka)) * length * 1.05f;
        float k1x = rx + (bx * cosf(kb) + sx * sinf(kb)) * length * 1.05f;
        float k1y = ry + (by * cosf(kb) + sy * sinf(kb)) * length * 1.05f;

        scratch.reset();
        scratch.moveTo(rx, ry);
        scratch.lineTo(e0x, e0y);
        scratch.quadTo(k0x, k0y, e1x, e1y);
        scratch.quadTo(k1x, k1y, e2x, e2y);
        scratch.close();
        fill.setShader(null);
        fill.setColor(argb(FIN2[p], (int) (128f * alphaMul)));
        c.drawPath(scratch, fill);

        line.setShader(null);
        line.setStrokeWidth(density * 0.8f);
        line.setColor(argb(FIN[p], 96));
        c.drawLine(rx, ry, e0x, e0y, line);
        c.drawLine(rx, ry, (e0x + e1x) * 0.5f, (e0y + e1y) * 0.5f, line);
        c.drawLine(rx, ry, e1x, e1y, line);
        c.drawLine(rx, ry, (e1x + e2x) * 0.5f, (e1y + e2y) * 0.5f, line);
        c.drawLine(rx, ry, e2x, e2y, line);
    }

    private void drawPellets(Canvas c) {
        float r = unit * 0.0085f;
        for (Pellet p : pellets) {
            if (!p.on) continue;
            float a = Math.min(1f, p.life / 1.5f);
            fill.setShader(null);
            fill.setColor(argb(0x000010, (int) (46f * a)));
            c.drawCircle(p.x + r * 0.9f, p.y + r * 1.3f, r, fill);
            fill.setShader(pelletShader);
            fill.setAlpha((int) (255f * a));
            c.save();
            c.translate(p.x, p.y);
            c.scale(r, r);
            c.drawCircle(0f, 0f, 1f, fill);
            c.restore();
        }
        fill.setShader(null);
    }

    // ------------------------------------------------------------------ math helpers

    private float rand(float lo, float hi) {
        return lo + rnd.nextFloat() * (hi - lo);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float wrapAngle(float a) {
        while (a > PI) a -= TWO_PI;
        while (a < -PI) a += TWO_PI;
        return a;
    }

    private static float sinf(float a) {
        return (float) Math.sin(a);
    }

    private static float cosf(float a) {
        return (float) Math.cos(a);
    }

    private static int argb(int rgb, int alpha) {
        return (alpha << 24) | (rgb & 0xFFFFFF);
    }
}
