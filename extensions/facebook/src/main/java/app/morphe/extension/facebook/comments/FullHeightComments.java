/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Open comments at full height (#179): the comment sheet fills the screen instead of opening part
 * way up.
 *
 * <p>The sheet's height is a fraction of the screen that its parameters carry from their
 * constructor to the popover fragment: one, Facebook's default, makes a phone's sheet full screen,
 * and anything less sizes the window to that share of the screen's height at the bottom. Reels ask
 * for two thirds, videos and some posts for a share a server value sets. The patch hands
 * {@link #sheetHeight} that fraction first thing in the constructor, and while the switch is on a
 * share below one comes back as one.
 *
 * <p>A reel's or a feed video's Comment button first tries Facebook's split screen, the video on
 * top and the comments under it, through five launchers that each answer whether they opened it.
 * Each already answers no when its server gate is off, and the button then opens the regular
 * sheet. The patch asks {@link #skipSplitScreen} first in each, so with the switch on they answer
 * no and the regular sheet, at full height, opens instead.
 *
 * <p>A share of one still has to pass the sheet fragment's "fill the screen?" check, which can say
 * no from the screen's size against a server value and then draws a centered box 80% of the screen
 * tall. The patch hands {@link #fillsScreen} the share first thing in that check, and while the
 * switch is on a share of one fills the screen.
 */
public final class FullHeightComments {
    /** The member the report names once a comment sheet's height has been asked about. */
    static final String SHEET = "comment sheet height";

    /** The member the report names once a split-screen launcher has been asked about. */
    static final String SPLIT_SCREEN = "split-screen comments";

    /** What the report counts each time a sheet opens at full height instead of part way. */
    static final String SHEET_RAISED = "Comment sheet opened at full height";

    /** What the report counts each time the split screen is passed over for the regular sheet. */
    static final String SPLIT_SCREEN_SKIPPED = "Split screen passed over";

    /** The member the report names once the sheet's "fill the screen?" check has been asked about. */
    static final String FILLS = "comment sheet fills the screen";

    /** What the report counts each time that check is answered yes instead of Facebook's own way. */
    static final String SCREEN_FILLED = "Comment sheet kept full screen";

    private static final String FAMILY = FamilyNames.FULL_HEIGHT_COMMENTS;

    private FullHeightComments() {
    }

    /**
     * The hook, first thing where a comment sheet's parameters are built, handed the share of the
     * screen's height the sheet opens to. While Open comments at full height is on, a share between
     * zero and one comes back as one; otherwise, and for a throw, Facebook's own share comes back.
     */
    public static float sheetHeight(float height) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, SHEET);
            if (!(height > 0f && height < 1f) || !Utils.settingsReady()
                    || !Settings.OPEN_COMMENTS_FULL_HEIGHT.get()) return height;
            HookStatus.counted(FAMILY, SHEET_RAISED);
            return 1f;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "sheet height", failure);
            return height;
        }
    }

    /**
     * The hook, first thing in each launcher of Facebook's split-screen comments. True makes the
     * launcher answer that it didn't open, so the button opens the regular sheet, while Open
     * comments at full height is on; false lets the launcher run.
     */
    public static boolean skipSplitScreen() {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, SPLIT_SCREEN);
            if (!Utils.settingsReady() || !Settings.OPEN_COMMENTS_FULL_HEIGHT.get()) return false;
            HookStatus.counted(FAMILY, SPLIT_SCREEN_SKIPPED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "split screen", failure);
            return false;
        }
    }

    /**
     * The hook, first thing in the comment sheet's "fill the screen?" check, handed the share of the
     * screen's height the sheet opens to. True, while Open comments at full height is on and the
     * share is one or more, answers that the sheet fills the screen; false lets Facebook's check
     * decide.
     */
    public static boolean fillsScreen(float height) {
        try {
            HookStatus.invoked(FAMILY);
            HookStatus.bound(FAMILY, FILLS);
            if (!(height >= 1f) || !Utils.settingsReady() || !Settings.OPEN_COMMENTS_FULL_HEIGHT.get()) return false;
            HookStatus.counted(FAMILY, SCREEN_FILLED);
            return true;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "fills screen", failure);
            return false;
        }
    }
}
