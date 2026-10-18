/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.reels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Open feed videos on their own (#161): the first new reels feed to ask for more soon after a feed
 * video tap is that video's player and gets no, every time; a feed that asked before the tap, or one
 * asking long after it, keeps Facebook's answer; off or paused, nothing is held back.
 */
@RunWith(RobolectricTestRunner.class)
public class FeedVideoAloneTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final AtomicLong clock = new AtomicLong(1_000_000);

    @Before
    public void start() {
        HookStatus.clear();
        FeedVideoAlone.forgetForTests();
        FeedVideoAlone.clockForTests = clock::get;
        Settings.FEED_VIDEOS_ALONE.save(true);
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.FEED_VIDEOS_ALONE.resetToDefault();
        FeedVideoAlone.forgetForTests();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.REEL_DECLUTTER + ":")) return line;
        }
        return null;
    }

    @Test
    public void theSwitchStartsOffAndBelongsToCleanUpReels() {
        Settings.FEED_VIDEOS_ALONE.resetToDefault();
        assertFalse(Settings.FEED_VIDEOS_ALONE.get());
        assertFalse("asked as each player asks, so no restart", Settings.FEED_VIDEOS_ALONE.rebootApp);
        assertTrue(PatchFamily.REEL_DECLUTTER.switches.contains(Settings.FEED_VIDEOS_ALONE));
    }

    @Test
    public void theTappedVideosPlayerGetsNoMoreReelsEveryTime() {
        Object reelsTab = new Object();
        assertFalse("a feed asking with no tap keeps Facebook's answer", FeedVideoAlone.holdsBack(reelsTab));

        FeedVideoAlone.feedVideoTapped();
        clock.addAndGet(800);
        assertFalse("the Reels tab asked before the tap", FeedVideoAlone.holdsBack(reelsTab));
        Object player = new Object();
        assertTrue(FeedVideoAlone.holdsBack(player));
        clock.addAndGet(60_000);
        assertTrue("later asks of the same player", FeedVideoAlone.holdsBack(player));
        assertFalse(FeedVideoAlone.holdsBack(reelsTab));

        String line = statusLine();
        assertTrue(line, line.contains(FeedVideoAlone.OPENED + " 1"));
        assertTrue(line, line.contains(FeedVideoAlone.HELD_BACK + " 2"));
    }

    @Test
    public void oneTapMakesOnePlayer() {
        FeedVideoAlone.feedVideoTapped();
        assertTrue(FeedVideoAlone.holdsBack(new Object()));
        assertFalse("the tap was used by the first player", FeedVideoAlone.holdsBack(new Object()));

        FeedVideoAlone.feedVideoTapped();
        assertTrue("a second tap opens a second player alone", FeedVideoAlone.holdsBack(new Object()));
    }

    /** Equal to every other one, the way a Facebook class with its own equals might be. */
    private static final class EqualFeed {
        @Override
        public boolean equals(Object other) {
            return other instanceof EqualFeed;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    @Test
    public void feedsAreToldApartByIdentityNotEquals() {
        Object reelsTab = new EqualFeed();
        assertFalse(FeedVideoAlone.holdsBack(reelsTab));
        FeedVideoAlone.feedVideoTapped();
        Object player = new EqualFeed();
        assertTrue("an equal but different feed is a new feed", FeedVideoAlone.holdsBack(player));
        assertFalse("the Reels tab keeps its own answer", FeedVideoAlone.holdsBack(reelsTab));
        assertTrue(FeedVideoAlone.holdsBack(player));
    }

    @Test
    public void aFeedFirstAskingLongAfterTheTapKeepsFacebooksAnswer() {
        FeedVideoAlone.feedVideoTapped();
        clock.addAndGet(FeedVideoAlone.WINDOW_MS + 1);
        assertFalse(FeedVideoAlone.holdsBack(new Object()));
    }

    @Test
    public void offOrPausedNothingIsHeldBack() {
        Object player = new Object();
        FeedVideoAlone.feedVideoTapped();
        assertTrue(FeedVideoAlone.holdsBack(player));

        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP}) {
            PauseForTests.pause(why);
            assertFalse("paused by " + why, FeedVideoAlone.holdsBack(player));
            FeedVideoAlone.feedVideoTapped();
            PauseForTests.resume();
            assertFalse("a tap while paused arms nothing", FeedVideoAlone.holdsBack(new Object()));
        }

        Settings.FEED_VIDEOS_ALONE.save(false);
        assertFalse(FeedVideoAlone.holdsBack(player));
        FeedVideoAlone.feedVideoTapped();
        Settings.FEED_VIDEOS_ALONE.save(true);
        assertFalse("a tap while off arms nothing", FeedVideoAlone.holdsBack(new Object()));
        assertTrue("on again, the player is still held", FeedVideoAlone.holdsBack(player));
        assertFalse("nobody asking is left alone", FeedVideoAlone.holdsBack(null));
    }
}
