/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.view.View;
import android.widget.ImageView;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * The home top bar's three optional hides (#181): the Messenger button, the count on it, and the
 * Facebook logo. The patch calls each hook where Facebook builds the thing it hides.
 *
 * <ul>
 *   <li>{@link #messengerButton} runs right after the bar adds the Messenger button. With the
 *       switch on the button is set to GONE, which takes it out of the bar's layout, so the buttons
 *       beside it close up.
 *   <li>{@link #messengerCount} runs first in the two methods that put an unread count on the
 *       button. With the switch on the count is 0, which Facebook draws as no badge.
 *   <li>{@link #facebookLogo} runs right after the bar builds the logo. With the switch on the
 *       logo is drawn fully transparent. It keeps its size and its touch listener, so the empty
 *       spot still opens Hushfacebook settings on a long press.
 * </ul>
 *
 * <p>Off, paused, before the settings are ready, or when anything here fails, Facebook's own
 * button, count and logo are left alone. The button and logo are built as the main screen starts,
 * so those two switches show after a restart. The count switch shows with the next count.
 */
public final class TopBarButtons {
    /** Counted under the patch's name each time one of the three is hidden. */
    static final String BUTTON_HIDDEN = "Messenger button hidden";
    static final String COUNT_HIDDEN = "Messenger count hidden";
    static final String LOGO_HIDDEN = "Facebook logo hidden";

    private static final String FAMILY = FamilyNames.BOTTOM_TAB_BAR;

    private TopBarButtons() {
    }

    /** Injection point, after the bar adds the Messenger button. Never throws. */
    public static void messengerButton(View button) {
        try {
            HookStatus.invoked(FAMILY);
            if (button == null || !Utils.settingsReady() || !Settings.HIDE_MESSENGER_BUTTON.get()) return;
            button.setVisibility(View.GONE);
            HookStatus.bound(FAMILY, "Messenger button");
            HookStatus.counted(FAMILY, BUTTON_HIDDEN);
            Logger.printDebug(() -> "Top bar: hid the Messenger button");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Messenger button", failure);
        }
    }

    /**
     * Injection point, first thing in each method that sets the Messenger button's unread count.
     * The count to show: 0 while the switch is on, the given count otherwise. Never throws.
     */
    public static int messengerCount(int count) {
        try {
            HookStatus.invoked(FAMILY);
            if (count == 0 || !Utils.settingsReady() || !Settings.HIDE_MESSENGER_COUNT.get()) return count;
            HookStatus.bound(FAMILY, "Messenger count");
            HookStatus.counted(FAMILY, COUNT_HIDDEN);
            return 0;
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Messenger count", failure);
            return count;
        }
    }

    /** Injection point, after the bar builds the Facebook logo. Never throws. */
    public static void facebookLogo(View logo) {
        try {
            HookStatus.invoked(FAMILY);
            if (logo == null || !Utils.settingsReady() || !Settings.HIDE_FACEBOOK_LOGO.get()) return;
            // Alpha keeps the view's size and its long press. The image alpha also survives a later
            // drawable swap, such as the app icon the Unlock app icons patch dresses the logo with.
            if (logo instanceof ImageView) {
                ((ImageView) logo).setImageAlpha(0);
            } else {
                logo.setAlpha(0f);
            }
            HookStatus.bound(FAMILY, "Facebook logo");
            HookStatus.counted(FAMILY, LOGO_HIDDEN);
            Logger.printDebug(() -> "Top bar: hid the Facebook logo");
        } catch (Throwable failure) {
            HookStatus.threw(FAMILY, "Facebook logo", failure);
        }
    }
}
