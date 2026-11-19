package com.onyx.goldfishwallpaper;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

/**
 * Launcher screen: a full-screen WebView running the live preview (assets/index.html) with the
 * HUD (Pond / Love, Feed Fish, Set Live Wallpaper).
 *
 * The WebView is kept alive across rotation and dark-mode switches (configChanges in the
 * manifest) so the preview never restarts or blinks.
 */
public class MainActivity extends Activity {

    private WebView webView;
    private SharedPreferences prefs;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        prefs = getSharedPreferences(GoldfishWallpaperService.PREFS, Context.MODE_PRIVATE);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setHapticFeedbackEnabled(false);
        webView.setLongClickable(false);
        webView.setOnLongClickListener(v -> true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);                 // file:///android_asset/ still loads
        s.setAllowContentAccess(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new JavaBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
        enterImmersive();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        webView.resumeTimers();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        webView.pauseTimers();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("Android");
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    private void enterImmersive() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController c = window.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private static String cleanMode(String mode) {
        return GoldfishWallpaperService.MODE_POND.equals(mode)
                ? GoldfishWallpaperService.MODE_POND : GoldfishWallpaperService.MODE_LOVE;
    }

    /**
     * Opens the system live wallpaper picker. Three stages so that no ROM ends up at "not supported":
     *  1. direct "set this wallpaper" preview (stock Android, One UI, most ColorOS / Realme UI / HyperOS)
     *  2. the generic live wallpaper chooser
     *  3. the generic wallpaper chooser, with a hint to pick Goldfish Live Wallpaper
     */
    private void launchLiveWallpaperPicker() {
        try {
            Intent direct = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            direct.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(MainActivity.this, GoldfishWallpaperService.class));
            direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(direct);
            return;
        } catch (Exception ignored) {
            // ActivityNotFoundException / SecurityException on some ROMs: try the next stage
        }
        try {
            Intent chooser = new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER);
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(chooser);
            Toast.makeText(this, R.string.pick_goldfish_hint, Toast.LENGTH_LONG).show();
            return;
        } catch (Exception ignored) {
            // the ROM removed the live wallpaper chooser
        }
        try {
            Intent generic = Intent.createChooser(new Intent(Intent.ACTION_SET_WALLPAPER), "Select Live Wallpaper");
            generic.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(generic);
            Toast.makeText(this, R.string.pick_goldfish_hint, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Exposed to the page as window.Android. WebView calls these on its own "JavaBridge" thread,
     * so anything that touches UI or starts an activity is marshalled to the main thread.
     */
    public final class JavaBridge {

        /** Saves the chosen mode synchronously, then opens the system wallpaper picker. */
        @JavascriptInterface
        public void applyLiveWallpaper(final String mode) {
            runOnUiThread(() -> {
                prefs.edit().putString(GoldfishWallpaperService.KEY_MODE, cleanMode(mode)).commit();
                launchLiveWallpaperPicker();
            });
        }

        /** Saves the mode without opening the picker; a running wallpaper updates immediately. */
        @JavascriptInterface
        public void saveMode(final String mode) {
            prefs.edit().putString(GoldfishWallpaperService.KEY_MODE, cleanMode(mode)).commit();
        }

        @JavascriptInterface
        public String getMode() {
            return cleanMode(prefs.getString(GoldfishWallpaperService.KEY_MODE, GoldfishWallpaperService.MODE_LOVE));
        }

        @JavascriptInterface
        public boolean isWallpaperActive() {
            try {
                android.app.WallpaperInfo info = WallpaperManager.getInstance(MainActivity.this).getWallpaperInfo();
                return info != null && getPackageName().equals(info.getPackageName());
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}
