/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Whether the tab bar at the bottom slides away while the feed scrolls down. Facebook has this
 * built in: as its main screen starts, it adds the bottom bar to the views that scroll away with
 * the feed, the way the top bar does, when one check answers yes. That check passes only with
 * scroll-away running on the phone and the bar at the bottom, and then asks two server settings
 * that turn the experiment on for some accounts. The hook sits between those two parts. Its yes
 * stands in for the server settings, and Facebook's own code then slides the bar out scrolling
 * down, brings it back scrolling up or at the top, and keeps the feed clear of it. The feed's
 * floating button, the mini player and the screens that make room for the bar ask the same check,
 * so they move with it. A kept bar answers no there instead, so it stays put on the accounts the
 * server settings give the experiment to.
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, the answer is no and
 * Facebook asks its server settings as before. With the bar at the top the hook isn't reached. The
 * bar's entry is made as the main screen starts, so a change shows after a restart.
 *
 * <p>The top bar is a scroll-away entry of its own, added whatever the server settings say. Each
 * move of an entry asks {@link #topBarOffset}, which keeps a kept top bar where it rests. Its entry
 * keeps its place in Facebook's insets, so the feed still starts below it.
 */
public final class TabBarScrollAway {
    /** Counted under the patch's name each time the check is answered yes. */
    static final String SLIDES_AWAY = "Tab bar set to slide away";
    /** Counted under the patch's name each time the check is answered no for a kept bar. */
    static final String STAYS_PUT = "Tab bar kept on screen";
    /** Counted under the patch's name each time a kept top bar is held where it rests. */
    static final String TOP_BAR_HELD = "Top bar held on screen";

    /** The name Facebook's main screen gives the top bar's scroll-away entry. */
    static final String APP_HEADER = "app_header_container";
    /** The entry's name when the tabs sit at the top, under the top bar, and move with it. */
    static final String TITLE_AND_TABS = "title_bar_tab_bar_container";

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    private TabBarScrollAway() {
    }

    /**
     * The hook, right after Facebook's check finds the tab bar at the bottom. True lets the bar
     * slide away; false leaves the answer to Facebook's server settings.
     */
    public static boolean slidesAway() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.TAB_BAR_SCROLL_AWAY.get()) return false;
            HookStatus.bound(FAMILY, "tab bar scroll-away");
            HookStatus.counted(FAMILY, SLIDES_AWAY);
            Logger.printDebug(() -> "Tab bar scroll-away: the bar at the bottom slides away");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "tab bar scroll-away", failure);
            return false;
        }
    }

    /**
     * Asked just ahead of {@link #slidesAway()}. True answers Facebook's check no outright, so the
     * bar keeps its place whatever the server settings say. Hide the tab bar while scrolling wins
     * when both are on, the way the settings page greys this switch out while that one is on.
     */
    public static boolean staysPut() {
        try {
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || Settings.TAB_BAR_SCROLL_AWAY.get() || !Settings.KEEP_TAB_BAR.get()) {
                return false;
            }
            HookStatus.bound(FAMILY, "tab bar kept");
            HookStatus.counted(FAMILY, STAYS_PUT);
            Logger.printDebug(() -> "Tab bar scroll-away: the bar at the bottom stays put");
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "tab bar kept", failure);
            return false;
        }
    }

    /**
     * The hook at the start of each method that moves a scroll-away view, with the entry's name and
     * the distance Facebook is about to move it from where it rests. A kept top bar gets 0, so it
     * stays where it rests; every other entry, and the top bar while the switch is off, keeps
     * Facebook's distance.
     */
    public static int topBarOffset(@Nullable String entry, int offset) {
        try {
            if (offset == 0 || !(APP_HEADER.equals(entry) || TITLE_AND_TABS.equals(entry))) return offset;
            HookStatus.invoked(FAMILY);
            if (!Utils.settingsReady() || !Settings.KEEP_TOP_BAR.get()) return offset;
            HookStatus.bound(FAMILY, "top bar kept");
            HookStatus.counted(FAMILY, TOP_BAR_HELD);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "top bar kept", failure);
            return offset;
        }
    }
}
