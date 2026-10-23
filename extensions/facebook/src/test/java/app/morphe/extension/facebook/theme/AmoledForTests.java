/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

/** The AMOLED theme's Background colour, set from tests in other packages. */
public final class AmoledForTests {
    private AmoledForTests() {
    }

    /** Puts {@code colour} in use with the lightest step the row gives it, as {@link AmoledTheme#load} does. */
    public static void useBackground(int colour) {
        AmoledTheme.use(colour, AmoledBackground.lightestStep(colour));
    }

    /** Back to the black the patch fills in. */
    public static void reset() {
        AmoledTheme.useBackground(0xFF000000);
    }
}
