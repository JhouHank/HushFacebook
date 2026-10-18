/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.LongSupplier;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Opens a video tapped in the feed on its own, with no reels to swipe to after it (#161).
 *
 * <p>A feed video opens in Facebook's full-screen player through one step Facebook calls Warion.
 * The player starts with that one video, then asks its reels feed for more, and what comes back is
 * the endless reels a swipe up leads to. The patch tells this class about each Warion step, and asks
 * it before every "fetch more" a reels feed makes.
 *
 * <p>With the switch on, the first reels feed to ask for more within {@link #WINDOW_MS} of a feed
 * video tap, if it never asked before, is taken to be that video's player. It gets no, then and on
 * every later ask, so the video stays alone. A feed that asked before the tap, the Reels tab's among
 * them, keeps Facebook's answer. Off, paused, before the settings are ready, or when anything here
 * fails, every feed gets Facebook's own answer.
 */
public final class FeedVideoAlone {
    /** Counted once for each player a feed video opened that this kept to that video. */
    static final String OPENED = "Feed video opened on its own";

    /** Counted each time that player asked for more reels and got none. */
    static final String HELD_BACK = "More reels held back";

    /** How long after a feed video tap its player's first ask for more is still taken as that player's. */
    static final long WINDOW_MS = 5000;

    private static final long NONE = Long.MIN_VALUE;

    private static final String FAMILY = FamilyNames.REEL_DECLUTTER;

    /** Every feed that asked while the switch was on, and whether it's a tapped video's player. */
    private static final Map<Object, Boolean> FEEDS = new WeakHashMap<>();

    private static volatile long tappedAt = NONE;

    /** Stands in for the uptime clock when a test sets it. */
    @Nullable
    static volatile LongSupplier clockForTests;

    private FeedVideoAlone() {
    }

    /** Whether the switch is on and the patch is running. */
    static boolean on() {
        // Settings mustn't load before the extension has its context.
        return Utils.settingsReady() && Settings.FEED_VIDEOS_ALONE.get();
    }

    /** Injection point, first thing in Facebook's Warion step, as a feed video opens its player. Never throws. */
    public static void feedVideoTapped() {
        try {
            HookStatus.invoked(FAMILY);
            if (!on()) {
                tappedAt = NONE;
                return;
            }
            HookStatus.bound(FAMILY, "feed video tap");
            tappedAt = now();
            Logger.printDebug(() -> "Feed videos on their own: a feed video is opening its player");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "feed video tap", failure);
        }
    }

    /**
     * Injection point, first thing in a reels feed's fetch more. True answers Facebook's caller that
     * nothing was fetched. Never throws.
     *
     * @param feed the reels feed asking, Facebook's own object behind the fetch.
     */
    public static boolean holdsBack(@Nullable Object feed) {
        try {
            HookStatus.invoked(FAMILY);
            if (feed == null || !on()) return false;
            HookStatus.bound(FAMILY, "reels fetch more");
            boolean alone;
            boolean first;
            synchronized (FEEDS) {
                Boolean known = FEEDS.get(feed);
                first = known == null;
                if (first) {
                    long tapped = tappedAt;
                    alone = tapped != NONE && now() - tapped <= WINDOW_MS;
                    if (alone) tappedAt = NONE;
                    FEEDS.put(feed, alone);
                } else {
                    alone = known;
                }
            }
            if (first) {
                if (alone) HookStatus.counted(FAMILY, OPENED);
                String id = Integer.toHexString(System.identityHashCode(feed));
                boolean player = alone;
                Logger.printDebug(() -> player
                        ? "Feed videos on their own: reels feed " + id + " is a tapped feed video's player, nothing comes after the video"
                        : "Feed videos on their own: reels feed " + id + " asked for more and keeps Facebook's answer");
            }
            if (alone) HookStatus.counted(FAMILY, HELD_BACK);
            return alone;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "reels fetch more", failure);
            return false;
        }
    }

    private static long now() {
        LongSupplier clock = clockForTests;
        return clock != null ? clock.getAsLong() : SystemClock.uptimeMillis();
    }

    /** Forgets every feed, any pending tap and the stand-in clock. Tests only. */
    static void forgetForTests() {
        synchronized (FEEDS) {
            FEEDS.clear();
        }
        tappedAt = NONE;
        clockForTests = null;
    }
}
