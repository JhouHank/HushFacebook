/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.appicons.logoDressSite
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.CREATE_WORDMARK_VIEW
import app.morphe.patches.facebook.misc.settings.WORDMARK_NAVIGATION_BAR
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Hide the Messenger button, its count and the Facebook logo in the home top bar (#181). Read from
 * 583, 2026-10-17, arm64 and armv7; the obfuscated names here are for reviewers, the code never
 * writes one down.
 *
 * - The home top bar is WordmarkNavigationBar (a kept class). Its builder method (arm64 A0J, armv7
 *   A0I) holds the kept trace strings "WordmarkNavigationBar#setupMessengerGlyphView" and
 *   "#createWordmarkView".
 * - After the setupMessengerGlyphView string the builder makes the Messenger button: a FrameLayout
 *   subclass (arm64 LX/4Qi;, armv7 LX/4R7;) it keeps in a field of the bar and, further down, adds to
 *   the bar with ViewGroup.addView(View), the view loaded by the iget-object just before the call.
 *   The button hook goes right after that call, on the register the iget-object loaded.
 * - The button class has one (I)V instance method (arm64 F32) and one (FbUserSession, I, Z)V
 *   instance method (arm64 F33), both of which set the unread count, and a kept
 *   "badgableGlyphView" string. The count hooks go first in each and replace the int.
 * - The logo is dressed by the same site the Unlock app icons patch uses ([logoDressSite]): the hook
 *   goes right after the stock wordmark load, with the logo register that holds the touch listener.
 *
 * A missing anchor refuses the whole Tab bar at the bottom patch, like every other anchor in it.
 */

internal const val TOP_BAR_BUTTONS = "$EXTENSION_PACKAGE/navigation/TopBarButtons;"
internal const val HIDE_MESSENGER_BUTTON_HOOK = "$TOP_BAR_BUTTONS->messengerButton(Landroid/view/View;)V"
internal const val HIDE_MESSENGER_COUNT_HOOK = "$TOP_BAR_BUTTONS->messengerCount(I)I"
internal const val HIDE_FACEBOOK_LOGO_HOOK = "$TOP_BAR_BUTTONS->facebookLogo(Landroid/view/View;)V"

internal const val SETUP_MESSENGER_GLYPH_VIEW = "WordmarkNavigationBar#setupMessengerGlyphView"
internal const val BADGABLE_GLYPH_VIEW = "badgableGlyphView"
private const val FRAME_LAYOUT_INIT = "Landroid/widget/FrameLayout;-><init>(Landroid/content/Context;)V"
private const val ADD_VIEW = "Landroid/view/ViewGroup;->addView(Landroid/view/View;)V"
private const val USER_SESSION = "Lcom/facebook/auth/usersession/FbUserSession;"

private const val TOP_BAR = "Tab bar at the bottom"

private fun refuse(detail: String): Nothing = throw PatchException("$TOP_BAR: $detail")

/** The Messenger button's class, and where the bar adds it: the addView call's index and the register holding the button. */
internal class MessengerButtonSite(val buttonType: String, val addView: Int, val register: Int)

/** The bar's builder methods holding [string]. */
internal fun buildersHolding(bar: ClassDef, string: String): List<Method> = bar.methods.filter { holdsString(it, string) }

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

private fun Instruction.signature(): String? = (reference() as? MethodReference)?.let {
    "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
}

/**
 * Where [builder] (the bar's builder) adds the Messenger button. After the load of the
 * setupMessengerGlyphView string it makes one object whose constructor is a FrameLayout's, then
 * adds a field of that type to the bar with addView(View), the view loaded by the instruction right
 * before the call. Refuses unless that finds exactly one.
 */
internal fun messengerButtonSite(builder: Method): MessengerButtonSite {
    val where = "${builder.definingClass}->${builder.name}"
    val code = builder.implementation?.instructions?.toList() ?: refuse("$where has no body")
    val marks = code.indices.filter { (code[it].reference() as? StringReference)?.string == SETUP_MESSENGER_GLYPH_VIEW }
    val mark = marks.singleOrNull() ?: refuse("expected one \"$SETUP_MESSENGER_GLYPH_VIEW\" in $where, found ${marks.size}")
    val types = (mark until code.size - 1).filter { index ->
        code[index].opcode == Opcode.NEW_INSTANCE && code[index + 1].opcode == Opcode.INVOKE_DIRECT &&
            code[index + 1].signature() == FRAME_LAYOUT_INIT
    }.map { ((code[it].reference() as TypeReference).type) }.distinct()
    val buttonType = types.singleOrNull()
        ?: refuse("expected one FrameLayout subclass made after \"$SETUP_MESSENGER_GLYPH_VIEW\" in $where, found $types")
    val adds = (mark + 1 until code.size).filter { index ->
        if (code[index].opcode != Opcode.INVOKE_VIRTUAL || code[index].signature() != ADD_VIEW) return@filter false
        val load = code[index - 1]
        val field = load.reference() as? FieldReference
        load.opcode == Opcode.IGET_OBJECT && field != null && field.type == buttonType &&
            field.definingClass == builder.definingClass &&
            (code[index] as FiveRegisterInstruction).let { it.registerCount == 2 && it.registerD == (load as OneRegisterInstruction).registerA }
    }
    val add = adds.singleOrNull() ?: refuse("expected one addView of the $buttonType field in $where, found ${adds.size}")
    return MessengerButtonSite(buttonType, add, (code[add - 1] as OneRegisterInstruction).registerA)
}

/** The register of an instance method's int parameter at [at], counted from the parameters. */
internal class CountMethod(val name: String, val parameters: List<String>, val intRegister: Int)

/**
 * The two methods of the button class that set its count: the one `(I)V` and the one
 * `(FbUserSession, int, boolean)V` instance method. Refuses unless the class holds the kept
 * badgableGlyphView string and has exactly one of each.
 */
internal fun countMethods(button: ClassDef): List<CountMethod> {
    if (button.methods.none { holdsString(it, BADGABLE_GLYPH_VIEW) }) refuse("${button.type} no longer holds \"$BADGABLE_GLYPH_VIEW\"")
    val instance = button.methods.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" }
    val shapes = listOf(listOf("I"), listOf(USER_SESSION, "I", "Z"))
    return shapes.map { shape ->
        val found = instance.filter { it.parameterTypes.map(CharSequence::toString) == shape }
        val method = found.singleOrNull() ?: refuse("expected one (${shape.joinToString("")})V instance method in ${button.type}, found ${found.size}")
        // p0 is the button; the int is parameter number [shape.indexOf("I")] after it.
        CountMethod(method.name, shape, 1 + shape.indexOf("I"))
    }
}

/** Puts the Messenger button's hook after the addView call. */
internal fun MutableMethod.hideMessengerButtonAfter(site: MessengerButtonSite) {
    addInstructions(site.addView + 1, "invoke-static/range { v${site.register} .. v${site.register} }, $HIDE_MESSENGER_BUTTON_HOOK")
}

/** Sends the count through the extension first thing, so a hidden count reaches Facebook as 0. */
internal fun MutableMethod.hideCountFirst(register: Int) {
    addInstructions(
        0,
        """
            invoke-static/range { p$register .. p$register }, $HIDE_MESSENGER_COUNT_HOOK
            move-result p$register
        """,
    )
}

/** Puts the logo's hook after the stock wordmark load, on the register that holds the logo. */
internal fun MutableMethod.hideLogoAfter(stockLoad: Int, logo: Int) {
    addInstructions(stockLoad + 1, "invoke-static/range { v$logo .. v$logo }, $HIDE_FACEBOOK_LOGO_HOOK")
}

/**
 * The three hooks of #181, carried by Tab bar at the bottom (its family holds the switches). The
 * settings dependency has to run first: the logo site is found after it has sent the logo's touch
 * listener through the settings entry.
 */
internal fun BytecodePatchContext.hideTopBarButtons() {
    run {
        val bar = mutableClassDefByOrNull(WORDMARK_NAVIGATION_BAR)
            ?: refuse("this Facebook has no $WORDMARK_NAVIGATION_BAR, the home feed's top bar")
        val glyphBuilders = buildersHolding(bar, SETUP_MESSENGER_GLYPH_VIEW)
        val glyphBuilder = glyphBuilders.singleOrNull()
            ?: refuse("expected one method of $WORDMARK_NAVIGATION_BAR holding \"$SETUP_MESSENGER_GLYPH_VIEW\", found ${glyphBuilders.size}")
        val logoBuilders = buildersHolding(bar, CREATE_WORDMARK_VIEW)
        val logoBuilder = logoBuilders.singleOrNull()
            ?: refuse("expected one method of $WORDMARK_NAVIGATION_BAR holding \"$CREATE_WORDMARK_VIEW\", found ${logoBuilders.size}")

        val button = messengerButtonSite(glyphBuilder)
        val buttonClass = mutableClassDefByOrNull(button.buttonType)
            ?: refuse("this Facebook has no ${button.buttonType}, the Messenger button")
        val counts = countMethods(buttonClass)
        logoDressSite(logoBuilder)

        bar.findMutableMethodOf(glyphBuilder).hideMessengerButtonAfter(button)
        counts.forEach { count ->
            buttonClass.methods.single {
                it.name == count.name && it.parameterTypes.map(CharSequence::toString) == count.parameters
            }.hideCountFirst(count.intRegister)
        }
        // The logo builder may be the glyph builder, which now holds more instructions: find the site again.
        val logoMethod = bar.methods.single {
            it.name == logoBuilder.name && it.parameterTypes.map(CharSequence::toString) == logoBuilder.parameterTypes.map(CharSequence::toString)
        }
        val site = logoDressSite(logoMethod)
        logoMethod.hideLogoAfter(site.stockLoad, site.logo)
    }
}

internal val topBarButtonsPatch = bytecodePatch {
    dependsOn(settingsPatch, facebookExtensionPatch)

    execute { hideTopBarButtons() }
}
