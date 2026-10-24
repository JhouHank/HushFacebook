/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.PatchFamily;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Home header text (#230): words of your own at the top of Home in place of Facebook's wordmark.
 * The header calls {@link #dress} right after Facebook loads its wordmark. The text is drawn at the
 * wordmark's height and takes the same theme color Facebook gives the wordmark, so it follows dark
 * mode. Blank text, Pause and every failure leave Facebook's wordmark alone.
 */
public final class HeaderText {
    /** The most characters the header takes. */
    public static final int MAX_LENGTH = 30;

    /** The text's color until the header hands over its theme color: Facebook's blue. */
    static final int FACEBOOK_BLUE = 0xFF0866FF;

    /** The text's height when the wordmark gave none, in dp. */
    private static final int FALLBACK_HEIGHT_DP = 24;

    /** The widest the text gets, as a share of the screen's width. Wider text gets smaller. */
    private static final float MAX_WIDTH_SHARE = 0.55f;

    private HeaderText() {
    }

    /**
     * Puts the saved text in place of the wordmark Facebook just loaded into [logo], and puts it back
     * whenever Facebook loads its wordmark again. Never throws.
     */
    public static void dress(@Nullable View logo) {
        try {
            HookStatus.invoked(FamilyNames.HEADER_TEXT);
            if (!(logo instanceof ImageView) || !Utils.settingsReady()) return;
            String text = clean(Settings.HEADER_TEXT.get());
            if (text.isEmpty()) return;
            ImageView image = (ImageView) logo;
            Keeper keeper = new Keeper(image, put(image, text, null));
            image.getViewTreeObserver().addOnPreDrawListener(keeper);
            Logger.printDebug(() -> "Header text: put " + text.length() + " characters at the top of Home");
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.HEADER_TEXT, "header text", failure);
            Logger.printException(() -> "Header text: could not put the text at the top of Home", failure);
        }
    }

    /**
     * Whether the text takes the header's place, so the picked app icon stays out of it: the patch is
     * in this build and text is saved. Never throws.
     */
    public static boolean replacesLogo() {
        try {
            return Utils.settingsReady() && PatchFamily.HEADER_TEXT.inBuild()
                    && !clean(Settings.HEADER_TEXT.get()).isEmpty();
        } catch (Throwable failure) {
            Logger.printException(() -> "Header text: could not read the header text", failure);
            return false;
        }
    }

    /**
     * What the header shows for [typed]: one line, without control or invisible formatting
     * characters, trimmed, and at most {@link #MAX_LENGTH} characters. Empty keeps the wordmark.
     */
    public static String clean(@Nullable String typed) {
        if (typed == null) return "";
        StringBuilder line = new StringBuilder();
        boolean space = false;
        int count = 0;
        for (int at = 0; at < typed.length() && count < MAX_LENGTH; ) {
            int point = typed.codePointAt(at);
            at += Character.charCount(point);
            int type = Character.getType(point);
            if (Character.isWhitespace(point) || Character.isSpaceChar(point)) {
                space = line.length() > 0;
                continue;
            }
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.UNASSIGNED
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
                continue;
            }
            if (space) {
                // A space needs room for the character after it, so the text never ends on one.
                if (count + 1 >= MAX_LENGTH) break;
                line.append(' ');
                count++;
                space = false;
            }
            line.appendCodePoint(point);
            count++;
        }
        return line.toString();
    }

    /** Whether [typed] is saved as typed, with nothing for {@link #clean} to take out. */
    public static boolean isClean(@Nullable String typed) {
        return typed != null && clean(typed).equals(typed);
    }

    /** Swaps [image]'s drawable for the text, sized from the wordmark's height or [previous]'s. */
    private static TextMark put(ImageView image, String text, @Nullable TextMark previous) {
        Drawable wordmark = image.getDrawable();
        int height = wordmark == null ? -1 : wordmark.getIntrinsicHeight();
        if (height <= 0 && previous != null) height = previous.getIntrinsicHeight();
        if (height <= 0) height = image.getMeasuredHeight() - image.getPaddingTop() - image.getPaddingBottom();
        if (height <= 0 && image.getLayoutParams() != null && image.getLayoutParams().height > 0) {
            height = image.getLayoutParams().height - image.getPaddingTop() - image.getPaddingBottom();
        }
        float density = image.getResources().getDisplayMetrics().density;
        if (height <= 0) height = Math.round(FALLBACK_HEIGHT_DP * density);
        int maxWidth = Math.round(image.getResources().getDisplayMetrics().widthPixels * MAX_WIDTH_SHARE);
        TextMark mark = new TextMark(text, height, maxWidth);
        image.setImageDrawable(mark);
        image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return mark;
    }

    /**
     * Facebook loads its wordmark again on its own (a theme change reloads it), so before each draw
     * the header gets the text back if its wordmark came back. Holds the header weakly.
     */
    private static final class Keeper implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<ImageView> header;
        private TextMark mark;

        Keeper(ImageView image, TextMark mark) {
            this.header = new WeakReference<>(image);
            this.mark = mark;
        }

        @Override
        public boolean onPreDraw() {
            ImageView image = header.get();
            if (image == null || image.getDrawable() == mark) return true;
            try {
                mark = put(image, mark.text, mark);
                Logger.printDebug(() -> "Header text: Facebook loaded its wordmark again, put the text back");
                // This pass would draw Facebook's wordmark, so the next one draws the text.
                return false;
            } catch (Throwable failure) {
                HookStatus.threw(FamilyNames.HEADER_TEXT, "header text again", failure);
                Logger.printException(() -> "Header text: could not put the text back", failure);
                return true;
            }
        }
    }

    /**
     * The text as a drawable at the wordmark's height. The header colors its wordmark through a color
     * filter, which this takes, so the text gets Facebook's own color for light and dark mode.
     */
    static final class TextMark extends Drawable {
        final String text;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Rect ink = new Rect();
        private final int height;
        private final int width;
        private final float inkTop;
        private final float inkBottom;
        @Nullable private ColorStateList tint;
        private int color = FACEBOOK_BLUE;
        private int alpha = 255;

        TextMark(String text, int height, int maxWidth) {
            this.text = text;
            this.height = height;
            paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            paint.setColor(FACEBOOK_BLUE);
            // The text and a capital letter together set the size, so short words without capitals
            // or with descenders still sit at the wordmark's height.
            paint.setTextSize(100f);
            Rect reach = reach();
            float size = reach.height() > 0 ? 100f * height / reach.height() : height;
            paint.setTextSize(size);
            paint.getTextBounds(text, 0, text.length(), ink);
            if (maxWidth > 0 && ink.width() > maxWidth) {
                paint.setTextSize(size * maxWidth / ink.width());
                paint.getTextBounds(text, 0, text.length(), ink);
            }
            Rect fitted = reach();
            inkTop = fitted.top;
            inkBottom = fitted.bottom;
            width = Math.max(1, ink.width());
        }

        /** The text's ink with a capital H's, at the paint's size. */
        private Rect reach() {
            Rect union = new Rect();
            paint.getTextBounds(text, 0, text.length(), union);
            Rect capital = new Rect();
            paint.getTextBounds("H", 0, 1, capital);
            union.union(capital);
            return union;
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            float x = bounds.left + (bounds.width() - width) / 2f - ink.left;
            float y = bounds.exactCenterY() - (inkTop + inkBottom) / 2f;
            canvas.drawText(text, x, y, paint);
        }

        @Override
        public int getIntrinsicWidth() {
            return width;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }

        @Override
        public void setAlpha(int alpha) {
            this.alpha = alpha;
            paint();
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void setColorFilter(@Nullable ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Nullable
        @Override
        public ColorFilter getColorFilter() {
            return paint.getColorFilter();
        }

        @Override
        public void setTintList(@Nullable ColorStateList tint) {
            this.tint = tint;
            applyTint(getState());
        }

        @Override
        public boolean isStateful() {
            return tint != null && tint.isStateful();
        }

        @Override
        protected boolean onStateChange(@NonNull int[] state) {
            return applyTint(state);
        }

        private boolean applyTint(int[] state) {
            int tinted = tint == null ? FACEBOOK_BLUE : tint.getColorForState(state, tint.getDefaultColor());
            if (tinted == color) return false;
            color = tinted;
            paint();
            return true;
        }

        /** The paint takes the tint's color at the drawable's alpha. */
        private void paint() {
            paint.setColor(color);
            paint.setAlpha(Color.alpha(color) * alpha / 255);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
