/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.view.Menu;
import android.view.MenuItem;

import app.morphe.extension.facebook.settings.Settings;

/**
 * Where a post menu's save row goes. The row under a post holds only icons with counts and gives a
 * new child no room, so Hushfacebook's Download rows ride in the post's three-dot menu. With
 * {@link Settings#POST_MENU_DOWNLOAD_FIRST} on they sit above Facebook's rows, else below them.
 *
 * <p>Android sorts a menu by category first. Category 5 (0x50000) ranks ahead of every public
 * category, the one Facebook's own rows use included, so an item added there lands on top.
 */
final class MenuTop {
    private MenuTop() {}

    /** A category no public constant names, which Android's menu sorts before all the others. */
    static final int FIRST_CATEGORY = 0x50000;

    /** Adds [title] to [menu], on top when the switch is on and below Facebook's rows otherwise. */
    static MenuItem add(Menu menu, CharSequence title) {
        if (Settings.POST_MENU_DOWNLOAD_FIRST.get()) {
            return menu.add(Menu.NONE, Menu.NONE, FIRST_CATEGORY, title);
        }
        return menu.add(title);
    }
}
