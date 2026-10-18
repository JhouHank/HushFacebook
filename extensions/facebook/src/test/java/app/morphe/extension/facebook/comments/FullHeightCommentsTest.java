/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Open comments at full height (#179): with its switch on, a sheet asked to open part way up opens
 * at one, the full height, each split-screen launcher is told to answer no, and a full-height sheet
 * is told it fills the screen, each counted. Facebook's own full height, and any share that isn't a
 * fraction, come back as they were. Off, paused, or before the settings are ready, nothing changes.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FullHeightCommentsTest {
    /** The share of the screen a reel's comment sheet asks for on 583. */
    private static final float REEL_SHEET = 0.66f;

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.OPEN_COMMENTS_FULL_HEIGHT.resetToDefault();
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.FULL_HEIGHT_COMMENTS + ":")) return line;
        }
        return null;
    }

    @Test
    public void onAPartSheetOpensAtFullHeightAndTheSplitScreenIsPassedOverEachCounted() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        assertEquals("a reel's sheet stayed part way up", 1f, FullHeightComments.sheetHeight(REEL_SHEET), 0f);
        assertEquals("a server share stayed part way up", 1f, FullHeightComments.sheetHeight(0.75f), 0f);
        assertEquals("Facebook's full height changed", 1f, FullHeightComments.sheetHeight(1f), 0f);
        assertEquals("a zero share was raised", 0f, FullHeightComments.sheetHeight(0f), 0f);
        assertEquals("a share over one was lowered", 1.5f, FullHeightComments.sheetHeight(1.5f), 0f);
        assertTrue("the split screen opened", FullHeightComments.skipSplitScreen());
        assertTrue("a full-height sheet was drawn as a box", FullHeightComments.fillsScreen(1f));
        assertFalse("a part sheet was made full screen", FullHeightComments.fillsScreen(REEL_SHEET));
        assertEquals(FamilyNames.FULL_HEIGHT_COMMENTS + ": invoked 8, 3 found, 0 missing. Counted: "
                + FullHeightComments.SHEET_RAISED + " 2, " + FullHeightComments.SPLIT_SCREEN_SKIPPED + " 1, "
                + FullHeightComments.SCREEN_FILLED + " 1",
                statusLine());
    }

    @Test
    public void notANumberIsNotFullScreen() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        assertFalse("NaN was made full screen", FullHeightComments.fillsScreen(Float.NaN));
    }

    @Test
    public void notANumberIsLeftAsItCame() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        assertTrue("NaN was changed", Float.isNaN(FullHeightComments.sheetHeight(Float.NaN)));
    }

    @Test
    public void offTheSheetKeepsFacebooksHeightAndTheSplitScreenRuns() {
        assertEquals(REEL_SHEET, FullHeightComments.sheetHeight(REEL_SHEET), 0f);
        assertFalse(FullHeightComments.skipSplitScreen());
        assertFalse(FullHeightComments.fillsScreen(1f));
    }

    @Test
    public void pausedTheSheetKeepsFacebooksHeightAndTheSplitScreenRuns() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertEquals(REEL_SHEET, FullHeightComments.sheetHeight(REEL_SHEET), 0f);
        assertFalse(FullHeightComments.skipSplitScreen());
        assertFalse(FullHeightComments.fillsScreen(1f));
    }

    @Test
    public void beforeTheSettingsAreReadyNothingChanges() {
        Settings.OPEN_COMMENTS_FULL_HEIGHT.save(true);
        SettingsContextRule.withoutContext(() -> {
            assertEquals(REEL_SHEET, FullHeightComments.sheetHeight(REEL_SHEET), 0f);
            assertFalse(FullHeightComments.skipSplitScreen());
            assertFalse(FullHeightComments.fillsScreen(1f));
        });
    }
}
