package com.onyx.goldfishwallpaper;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;

import java.util.Locale;

/**
 * Detects what kind of phone we are running on (RAM, CPU cores, display refresh rate, density and
 * a coarse GPU hint) and chooses how the render loop is paced.
 *
 * It never removes visual detail. Every tier keeps all 12 spine segments, veil tails, paired fins,
 * dorsal fins, shadows, pebbles and caustics. The only knob is how many display refreshes are
 * skipped between drawn frames, and because fish motion is integrated with the real elapsed time
 * (dtScale = dt * 60) the swimming speed looks identical at 30, 45, 60, 90 or 120 FPS.
 */
final class DevicePerformanceProfile {

    enum Tier { LOW, MID, HIGH }

    final Tier tier;
    final int cpuCores;
    final long totalMemoryMb;
    final float refreshRate;
    final float density;
    final boolean weakGpuHint;
    /** Draw one frame every N display refreshes (1 = every vsync). */
    final int vsyncDivisor;

    private DevicePerformanceProfile(Tier tier, int cpuCores, long totalMemoryMb, float refreshRate,
                                     float density, boolean weakGpuHint, int vsyncDivisor) {
        this.tier = tier;
        this.cpuCores = cpuCores;
        this.totalMemoryMb = totalMemoryMb;
        this.refreshRate = refreshRate;
        this.density = density;
        this.weakGpuHint = weakGpuHint;
        this.vsyncDivisor = vsyncDivisor;
    }

    static DevicePerformanceProfile detect(Context context) {
        long memMb = 3072L;
        boolean lowRam = false;
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            memMb = mi.totalMem / (1024L * 1024L);
            lowRam = am.isLowRamDevice();
        }

        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());

        float refresh = 60f;
        try {
            DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (d != null) refresh = d.getRefreshRate();
        } catch (RuntimeException ignored) {
            // keep 60 Hz
        }
        if (refresh < 30f || refresh > 244f) refresh = 60f;

        float density = context.getResources().getDisplayMetrics().density;

        // MediaTek Helio / Unisoc class SoCs have small Mali GPUs: pace them conservatively.
        String soc = (Build.HARDWARE + " " + Build.BOARD).toLowerCase(Locale.US);
        boolean weakGpu = soc.contains("mt65") || soc.contains("mt67") || soc.contains("mt68")
                || soc.contains("ums") || soc.contains("sc98") || soc.contains("unisoc") || soc.contains("spreadtrum");

        Tier tier;
        if (lowRam || memMb < 3000L || cores <= 4 || weakGpu) {
            tier = Tier.LOW;
        } else if (memMb >= 7000L && cores >= 8) {
            tier = Tier.HIGH;
        } else {
            tier = Tier.MID;
        }

        // Highest frame rate this tier can hold steadily; pick the display divisor that stays under it.
        float cap = tier == Tier.LOW ? 60f : (tier == Tier.MID ? 90f : 120f);
        int divisor = 1;
        while (refresh / divisor > cap + 1f && divisor < 4) divisor++;

        return new DevicePerformanceProfile(tier, cores, memMb, refresh, density, weakGpu, divisor);
    }

    float targetFps() {
        return refreshRate / vsyncDivisor;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "tier=%s cores=%d ram=%dMB refresh=%.0fHz density=%.2f weakGpu=%b -> %.0f FPS (every %d vsync)",
                tier, cpuCores, totalMemoryMb, refreshRate, density, weakGpuHint, targetFps(), vsyncDivisor);
    }
}
