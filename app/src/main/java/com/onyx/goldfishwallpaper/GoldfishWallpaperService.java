package com.onyx.goldfishwallpaper;

import android.app.WallpaperManager;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

/**
 * Goldfish live wallpaper.
 *
 * Frame pacing and flicker control
 *  - The loop is driven by Choreographer, i.e. the display's own VSync (60 / 90 / 120 Hz), not by a
 *    drifting Handler timer. Motion uses the real elapsed time (dtScale = dt * 60), so swimming
 *    speed is identical at every refresh rate.
 *  - Frames are drawn with lockHardwareCanvas() (GPU, API 26+) and fall back to lockCanvas() if the
 *    device refuses it. The surface is validated before every frame and unlocked in a finally block.
 *  - DevicePerformanceProfile picks how many VSyncs to skip between drawn frames; a small governor
 *    skips one more if frames still cannot be produced in time. Visual detail is never reduced.
 */
public class GoldfishWallpaperService extends WallpaperService {

    public static final String PREFS = "goldfish_prefs";
    public static final String KEY_MODE = "theme_mode";
    public static final String MODE_LOVE = "love";
    public static final String MODE_POND = "pond";

    private static final String TAG = "GoldfishWallpaper";

    @Override
    public Engine onCreateEngine() {
        return new AquariumEngine();
    }

    private final class AquariumEngine extends Engine
            implements Choreographer.FrameCallback, SharedPreferences.OnSharedPreferenceChangeListener {

        private static final long DOUBLE_TAP_MS = 360L;
        private static final int GOVERNOR_WINDOW = 90;     // drawn frames per governor decision

        private SharedPreferences prefs;
        private AquariumScene scene;
        private DevicePerformanceProfile profile;

        private boolean visible;
        private boolean scheduled;
        private boolean hardwareCanvasWorks = Build.VERSION.SDK_INT >= 26;
        private int width, height;
        private float density = 2f;

        private long lastFrameNanos;
        private int vsyncCounter;
        private int vsyncDivisor = 1;
        private long workNanosAccum;
        private int workFrames;

        private long lastTapMs;
        private float lastTapX, lastTapY;
        private long lastTouchMs = -10_000L;

        // ---------------------------------------------------------------- lifecycle

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            setTouchEventsEnabled(true);
            surfaceHolder.setFormat(PixelFormat.RGBX_8888);          // opaque surface: no blending, no flash

            density = getResources().getDisplayMetrics().density;
            profile = DevicePerformanceProfile.detect(GoldfishWallpaperService.this);
            vsyncDivisor = profile.vsyncDivisor;
            Log.i(TAG, profile.toString());

            prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            scene = new AquariumScene(readLoveMode());
            prefs.registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onDestroy() {
            visible = false;
            unschedule();
            if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
            super.onDestroy();
        }

        private boolean readLoveMode() {
            return !MODE_POND.equals(prefs.getString(KEY_MODE, MODE_LOVE));
        }

        /** Mode switched in the app: an already running wallpaper follows immediately. */
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            if (KEY_MODE.equals(key) && scene != null) scene.setLoveMode(readLoveMode());
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            super.onSurfaceChanged(holder, format, w, h);
            width = w;
            height = h;
            scene.resize(w, h, density);
            drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            unschedule();
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onVisibilityChanged(boolean isVisible) {
            visible = isVisible;
            if (isVisible) {
                scene.setLoveMode(readLoveMode());
                lastFrameNanos = 0L;                 // never integrate the time spent hidden
                vsyncCounter = 0;
                schedule();
            } else {
                unschedule();
            }
        }

        // ---------------------------------------------------------------- VSync driven loop

        private void schedule() {
            if (visible && !scheduled) {
                scheduled = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        private void unschedule() {
            Choreographer.getInstance().removeFrameCallback(this);
            scheduled = false;
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            scheduled = false;
            if (!visible) return;

            if (++vsyncCounter < vsyncDivisor) {      // frame pacing: skip this refresh
                schedule();
                return;
            }
            vsyncCounter = 0;

            float dt = lastFrameNanos == 0L ? 1f / 60f : (frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
            dt = Math.min(0.033f, Math.max(0.001f, dt));
            lastFrameNanos = frameTimeNanos;
            float dtScale = dt * 60f;                 // 1.0 at 60 FPS: motion is refresh-rate independent

            long t0 = System.nanoTime();
            scene.update(dt, dtScale);
            drawFrame();
            governor(System.nanoTime() - t0);

            schedule();
        }

        /** If frames keep taking longer than their budget, skip one more VSync. Quality is untouched. */
        private void governor(long workNanos) {
            workNanosAccum += workNanos;
            if (++workFrames < GOVERNOR_WINDOW) return;
            double avgMs = workNanosAccum / (double) workFrames / 1_000_000.0;
            double budgetMs = 1000.0 / profile.refreshRate * vsyncDivisor;
            if (avgMs > budgetMs * 0.85 && vsyncDivisor < 4 && profile.refreshRate / (vsyncDivisor + 1) >= 28f) {
                vsyncDivisor++;
                Log.i(TAG, "governor: " + String.format(java.util.Locale.US, "%.1f", avgMs)
                        + " ms/frame over budget, now every " + vsyncDivisor + " vsync");
            }
            workNanosAccum = 0L;
            workFrames = 0;
        }

        private void drawFrame() {
            final SurfaceHolder holder = getSurfaceHolder();
            if (!visible || holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) return;

            Canvas canvas = null;
            try {
                if (hardwareCanvasWorks) {
                    try {
                        canvas = holder.lockHardwareCanvas();     // GPU path, API 26+
                    } catch (Exception e) {
                        hardwareCanvasWorks = false;               // OEM refused: fall back for good
                        canvas = null;
                    }
                }
                if (canvas == null) canvas = holder.lockCanvas();
                if (canvas != null) scene.renderAquarium(canvas);
            } catch (Exception ignored) {
                // surface vanished mid-frame; the next callback recovers
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Exception ignored) {
                        // surface already released
                    }
                }
            }
        }

        // ---------------------------------------------------------------- input

        @Override
        public void onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    long now = SystemClock.uptimeMillis();
                    lastTouchMs = now;
                    float x = e.getX(), y = e.getY();
                    float slop = 90f * density;
                    float dx = x - lastTapX, dy = y - lastTapY;
                    if (now - lastTapMs < DOUBLE_TAP_MS && dx * dx + dy * dy < slop * slop) {
                        scene.feed(x, y);                         // double tap drops food pellets
                        lastTapMs = 0L;
                    } else {
                        lastTapMs = now;
                        lastTapX = x;
                        lastTapY = y;
                        scene.touchDown(x, y);                    // water ripple; fish scatter or come to look
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE:
                    lastTouchMs = SystemClock.uptimeMillis();
                    scene.touchMove(e.getX(), e.getY());
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    scene.touchUp();
                    break;
                default:
                    break;
            }
        }

        /** Launchers that do not forward raw touches still report taps as wallpaper commands. */
        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras, boolean resultRequested) {
            if (WallpaperManager.COMMAND_TAP.equals(action) && SystemClock.uptimeMillis() - lastTouchMs > 600L) {
                scene.touchDown(x, y);
                scene.touchUp();
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }
    }
}
