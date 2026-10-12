/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide the tab bar while scrolling: with the switch on, Facebook's check of whether the bar at the
 * bottom slides away answers yes, every time it's asked, and each yes is counted. Off, paused, or
 * before the settings are ready, it answers no, so Facebook goes on to its own server settings.
 * The slide itself is Facebook's code, which this check turns on.
 *
 * Keep the tab bar on screen answers no to the same check before Facebook's server settings get a
 * say, unless the slide is switched on, which wins. Keep the top bar on screen hands back 0 for the
 * distance the top bar's entry is asked to move, and every other entry's distance untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TabBarScrollAwayTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.TAB_BAR_SCROLL_AWAY.resetToDefault();
        Settings.KEEP_TAB_BAR.resetToDefault();
        Settings.KEEP_TOP_BAR.resetToDefault();
        Settings.BOTTOM_TAB_BAR.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.BOTTOM_TAB_BAR + ":")) return line;
        }
        return null;
    }

    @Test
    public void onTheBarSlidesAwayEachTimeFacebookAsks() {
        Settings.TAB_BAR_SCROLL_AWAY.save(true);
        // The start, the floating button and the mini player each ask.
        for (int asked = 0; asked < 3; asked++) {
            assertTrue("the check answered no with the switch on", TabBarScrollAway.slidesAway());
        }
        assertEquals(FamilyNames.BOTTOM_TAB_BAR + ": invoked 3, 1 found, 0 missing. Counted: "
                + TabBarScrollAway.SLIDES_AWAY + " 3", statusLine());
    }

    @Test
    public void theSwitchWorksWithoutTabBarAtTheBottomsOwnSwitch() {
        // Accounts Facebook gives the bar at the bottom don't need the other switch.
        assertFalse(Settings.BOTTOM_TAB_BAR.get());
        Settings.TAB_BAR_SCROLL_AWAY.save(true);
        assertTrue("the slide waited for Tab bar at the bottom's switch", TabBarScrollAway.slidesAway());
    }

    @Test
    public void offOrPausedFacebookDecides() {
        assertFalse("the switch doesn't start off", Settings.TAB_BAR_SCROLL_AWAY.get());
        assertFalse("off, the bar slid away", TabBarScrollAway.slidesAway());

        Settings.TAB_BAR_SCROLL_AWAY.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " slid the bar away", TabBarScrollAway.slidesAway());
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() ->
                assertFalse("the bar slid away before the settings were ready", TabBarScrollAway.slidesAway()));

        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, the bar didn't slide away", TabBarScrollAway.slidesAway());
    }

    @Test
    public void theSwitchAsksForARestartAndTravelsWithTheBarsFamily() {
        assertEquals(true, Settings.TAB_BAR_SCROLL_AWAY.rebootApp);
        assertNull("nothing asks before the switch changes", Settings.TAB_BAR_SCROLL_AWAY.userDialogMessage);
        assertTrue("Pause and the report don't know the switch",
                PatchFamily.BOTTOM_TAB_BAR.switches.contains(Settings.TAB_BAR_SCROLL_AWAY));
    }

    @Test
    public void aKeptBarStaysPutEachTimeFacebookAsks() {
        assertFalse("the kept bar's switch doesn't start off", Settings.KEEP_TAB_BAR.get());
        assertFalse("off, the bar was kept", TabBarScrollAway.staysPut());

        Settings.KEEP_TAB_BAR.save(true);
        for (int asked = 0; asked < 2; asked++) {
            assertTrue("on, the bar wasn't kept", TabBarScrollAway.staysPut());
        }
        String line = statusLine();
        assertTrue(line, line != null && line.contains("Counted: " + TabBarScrollAway.STAYS_PUT + " 2"));
    }

    @Test
    public void hidingWhileScrollingWinsOverAKeptBar() {
        Settings.KEEP_TAB_BAR.save(true);
        assertTrue("the kept bar's switch is greyed out with the slide off", Settings.KEEP_TAB_BAR.isAvailable());
        Settings.TAB_BAR_SCROLL_AWAY.save(true);
        assertFalse("the kept bar's switch isn't greyed out under the slide", Settings.KEEP_TAB_BAR.isAvailable());
        assertFalse("both on, the bar was kept", TabBarScrollAway.staysPut());
        assertTrue("both on, the bar didn't slide away", TabBarScrollAway.slidesAway());
    }

    @Test
    public void aKeptTopBarHoldsStillAndTheOtherEntriesMove() {
        assertFalse("the top bar's switch doesn't start off", Settings.KEEP_TOP_BAR.get());
        assertEquals("off, the top bar was held", -120, TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));

        Settings.KEEP_TOP_BAR.save(true);
        assertEquals("the top bar slid away", 0, TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));
        assertEquals("the top bar with the tabs in it slid away", 0,
                TabBarScrollAway.topBarOffset(TabBarScrollAway.TITLE_AND_TABS, -80));
        assertEquals("a top bar back home moved", 0, TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, 0));
        assertEquals("the bottom bar's entry was held", 96, TabBarScrollAway.topBarOffset("bottom_tabs_container", 96));
        assertEquals("an entry with no name was held", -50, TabBarScrollAway.topBarOffset(null, -50));
        String line = statusLine();
        assertTrue(line, line != null && line.contains("Counted: " + TabBarScrollAway.TOP_BAR_HELD + " 2"));
    }

    @Test
    public void pausedOrNotReadyBothBarsAreFacebooks() {
        Settings.KEEP_TAB_BAR.save(true);
        Settings.KEEP_TOP_BAR.save(true);
        for (HushfacebookPause.Reason reason : new HushfacebookPause.Reason[] {
                HushfacebookPause.Reason.SWITCH, HushfacebookPause.Reason.CRASH_LOOP,
                HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(reason);
            assertFalse("a Hushfacebook paused by " + reason + " kept the bar", TabBarScrollAway.staysPut());
            assertEquals("a Hushfacebook paused by " + reason + " held the top bar", -120,
                    TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));
            PauseForTests.resume();
        }
        SettingsContextRule.withoutContext(() -> {
            assertFalse("the bar was kept before the settings were ready", TabBarScrollAway.staysPut());
            assertEquals("the top bar was held before the settings were ready", -120,
                    TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));
        });

        String line = statusLine();
        assertFalse("an answer that left it to Facebook was counted: " + line, line != null && line.contains("Counted"));
        assertTrue("on again after the pause, the bar wasn't kept", TabBarScrollAway.staysPut());
        assertEquals("on again after the pause, the top bar moved", 0,
                TabBarScrollAway.topBarOffset(TabBarScrollAway.APP_HEADER, -120));
    }

    @Test
    public void onlyTheKeptBarAsksForARestartAndBothTravelWithTheBarsFamily() {
        assertEquals("the bar's entry is built as Facebook starts", true, Settings.KEEP_TAB_BAR.rebootApp);
        assertEquals("the top bar is asked on every move", false, Settings.KEEP_TOP_BAR.rebootApp);
        assertTrue("Pause and the report don't know the switches", PatchFamily.BOTTOM_TAB_BAR.switches.containsAll(
                Arrays.asList(Settings.KEEP_TAB_BAR, Settings.KEEP_TOP_BAR)));
    }
}
