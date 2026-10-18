/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Hide the status bar (#124 leftover): Android's status bar, the strip with the time and the
 * battery, goes from Facebook's main screen, the one with the feed and the tab bar, so Facebook has
 * the whole screen. A swipe down from the top edge shows it for a moment, as in any full screen
 * app. The bar is the system's, not a Facebook view, so it's asked away through the main screen's
 * window, and only there: reels, videos and photos already go full screen on their own, and chats
 * and the composer keep it.
 *
 * <p>Facebook shows the bar again when it leaves a full screen of its own, and a window opened over
 * the main screen, such as the comment sheet, has its own bar, so the check runs as the main screen
 * resumes, gets its focus back and lays itself out again, which a bar coming back makes it do. A
 * bar still showing then is asked away again. Asking away a bar already asked away changes nothing,
 * so one the person swiped in stays until Android hides it. Off or paused, the bar comes back on
 * the next check, and only on a screen this hid it on.
 *
 * <p>Any failure leaves that screen's bar as it is and stops the checks there.
 */
public final class HiddenStatusBar {
    /** Facebook's main screen, a manifest component, the one screen with the feed and the tab bar. */
    static final String MAIN_SCREEN = TranslucentBars.MAIN_SCREEN;

    /** Counted each time a showing bar is asked away. */
    static final String HIDDEN = "Status bar hidden";
    /** Counted each time the switch going off or a pause brings the bar back. */
    static final String GIVEN_BACK = "Status bar given back";

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    private static boolean watching;

    /** Each main screen's window and its checks. */
    static final Map<Window, Checks> WINDOWS = new WeakHashMap<>();

    private HiddenStatusBar() {
    }

    /**
     * Called once per Facebook start, from the settings entry's onApplicationCreate, with the tab bar
     * patch in the build. Each main screen gets its checks as it resumes; the switch is asked at each.
     */
    public static synchronized void watch(Context context) {
        try {
            if (watching || !(context instanceof Application)) return;
            ((Application) context).registerActivityLifecycleCallbacks(new Screens());
            watching = true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "hidden status bar", failure);
        }
    }

    /** Whether the bar goes now: the switch on, nothing paused, the settings ready. */
    public static boolean on() {
        return Utils.settingsReady() && Settings.HIDE_STATUS_BAR.get();
    }

    /** The checks of [window], made once and asked again at each check. */
    static Checks attach(Window window) {
        synchronized (WINDOWS) {
            Checks checks = WINDOWS.get(window);
            if (checks == null) {
                checks = new Checks(new WindowBar(window));
                WINDOWS.put(window, checks);
                ViewTreeObserver observer = window.getDecorView().getViewTreeObserver();
                observer.addOnGlobalLayoutListener(checks);
                observer.addOnWindowFocusChangeListener(checks);
            }
            return checks;
        }
    }

    /** Gives each main screen its checks as it resumes, and runs one. */
    static final class Screens implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityResumed(Activity activity) {
            try {
                if (!MAIN_SCREEN.equals(activity.getClass().getName()) || activity.getWindow() == null) return;
                attach(activity.getWindow()).check();
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "hidden status bar", failure);
            }
        }

        @Override
        public void onActivityCreated(Activity activity, @Nullable Bundle state) {
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    /** One window's status bar. */
    interface Bar {
        /** Whether the bar shows now; null once its window is gone. */
        @Nullable
        Boolean showing();

        /** Asks the bar away, a swipe from the edge showing it for a moment. */
        void hide();

        /** Brings the bar back. */
        void show();

        /** Stops calling [checks]. */
        void forget(Checks checks);
    }

    /** A window's own status bar, through its insets. */
    static final class WindowBar implements Bar {
        private final WeakReference<Window> window;

        WindowBar(Window window) {
            this.window = new WeakReference<>(window);
        }

        @Nullable
        @Override
        public Boolean showing() {
            Window window = this.window.get();
            if (window == null) return null;
            WindowInsets insets = window.getDecorView().getRootWindowInsets();
            return insets == null || insets.isVisible(WindowInsets.Type.statusBars());
        }

        @Override
        public void hide() {
            WindowInsetsController controller = controller();
            if (controller == null) return;
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.statusBars());
        }

        @Override
        public void show() {
            WindowInsetsController controller = controller();
            if (controller != null) controller.show(WindowInsets.Type.statusBars());
        }

        @Override
        public void forget(Checks checks) {
            Window window = this.window.get();
            if (window == null) return;
            ViewTreeObserver observer = window.getDecorView().getViewTreeObserver();
            if (!observer.isAlive()) return;
            observer.removeOnGlobalLayoutListener(checks);
            observer.removeOnWindowFocusChangeListener(checks);
        }

        @Nullable
        private WindowInsetsController controller() {
            Window window = this.window.get();
            return window == null ? null : window.getInsetsController();
        }
    }

    /** One main screen's checks. */
    static final class Checks implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnWindowFocusChangeListener {
        private final Bar bar;
        /** Whether this asked the bar away and hasn't given it back. */
        boolean hid;
        private boolean stopped;

        Checks(Bar bar) {
            this.bar = bar;
        }

        @Override
        public void onGlobalLayout() {
            check();
        }

        /** The settings page or a sheet closing hands the window its focus back, with its own bar gone. */
        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            if (hasFocus) check();
        }

        /** Asks the bar away while on, or gives it back once when off. */
        void check() {
            if (stopped) return;
            try {
                if (on()) {
                    if (!Boolean.TRUE.equals(bar.showing())) return;
                    HookStatus.invoked(FAMILY);
                    bar.hide();
                    hid = true;
                    HookStatus.counted(FAMILY, HIDDEN);
                    Logger.printDebug(() -> "Hidden status bar: asked the status bar away");
                } else if (hid) {
                    hid = false;
                    bar.show();
                    HookStatus.counted(FAMILY, GIVEN_BACK);
                    Logger.printDebug(() -> "Hidden status bar: gave the status bar back");
                }
            } catch (Throwable failure) {
                HookStatus.threw(FAMILY, "hidden status bar", failure);
                stopped = true;
                try {
                    bar.forget(this);
                } catch (Throwable ignored) {
                    // Already failing; the checks stay stopped either way.
                }
            }
        }
    }
}
