/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.fullheight

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.facebook.misc.extension.requireStatusMethod
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Open comments at full height"

/**
 * The comment sheet's parameters, a kept class. The one constructor taking a float takes the
 * share of the screen's height the sheet opens to.
 */
internal const val POPOVER_PARAMS = "Lcom/facebook/ufiservices/flyout/params/PopoverParams;"

/** The kept factory building the Intent of Facebook's split-screen comments, the video above the sheet. */
internal const val SPLIT_SCREEN_FACTORY = "Lcom/facebook/feedback/splitscreen/SplitScreenFeedbackContainerFragmentFactory;"

/** The surface name the reel viewer's footer logs, held by the classes of two split-screen launchers. */
internal const val REEL_FOOTER = "fb_shorts_viewer_footer"

/** The reason the feed's split-screen launcher logs when the post's view has no size yet, held by its class. */
internal const val UNIT_NOT_MEASURED = "unit_view_not_measured"

/** The reason the two watch-and-comment launchers log when they don't open the split screen. */
internal const val NOT_ENTERED = "split_screen_not_entered"

private const val INTENT = "Landroid/content/Intent;"

internal const val FULL_HEIGHT_COMMENTS = "$EXTENSION_PACKAGE/comments/FullHeightComments;"
internal const val SHEET_HEIGHT = "$FULL_HEIGHT_COMMENTS->sheetHeight(F)F"
internal const val SKIP_SPLIT_SCREEN = "$FULL_HEIGHT_COMMENTS->skipSplitScreen()Z"

/**
 * Open comments at full height (#179).
 *
 * The comment sheet is a popover whose parameters, [POPOVER_PARAMS], carry a share of the screen's
 * height from the one constructor taking a float to the sheet's fragment (583 `PopoverParams.A00`,
 * read into `SimpleUFIPopoverFragment.A02`). One, the default the class builds for itself, makes
 * a phone's sheet full screen; anything less sizes the dialog's window to that share of the
 * screen's height at the bottom. Reels ask for two thirds, and videos and some posts for a share a
 * server value sets. First thing in that constructor, the extension is handed the share and its
 * answer goes back into the same register, so with the switch on a share below one becomes one.
 * Post, video, reel and story comment sheets all build their parameters there.
 *
 * A reel's or a feed video's Comment button first tries Facebook's split screen, the video on top
 * and the comments under it, through five launchers that each answer whether they opened it. Each
 * already answers false when its server gate is off, and the button then opens the regular sheet.
 * Three are found as the boolean methods building [SPLIT_SCREEN_FACTORY]'s Intent in the classes
 * holding [REEL_FOOTER] or [UNIT_NOT_MEASURED] (583 `LX/Xv5;->A00`, `LX/XRV;->A00`,
 * `LX/4Uk;->A08`), and two as the boolean methods holding [NOT_ENTERED] (583 `LX/Xv4;->A00`,
 * `LX/Xys;->A01`). First thing in each, the extension is asked, and a yes answers false.
 *
 * In the default selection with its switch off, so nothing changes until it's turned on.
 */
@Suppress("unused")
val fullHeightCommentsPatch = bytecodePatch(
    // The README table check reads this literal; PATCH carries the same text for the messages.
    name = "Open comments at full height",
    description = "Opens the comment sheet at the full height of the screen instead of part way up, on posts, " +
        "videos, reels and stories. On reels and videos, Comment opens that sheet instead of the split view. " +
        "Starts off. Turn it on in Hushfacebook settings > Comments.",
) {
    category("Comments")
    dependsOn(settingsPatch, facebookExtensionPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        // Everything is found before anything changes, so a build missing one part is left as it was.
        requireStatusMethod("fullHeightComments")
        val params = classDefByOrNull(POPOVER_PARAMS) ?: refuse("$POPOVER_PARAMS isn't in this build")
        val sheet = sheetConstructor(params)
        val height = floatRegister(sheet)
        val launchers = splitScreenLaunchers(
            (holders(REEL_FOOTER) + holders(UNIT_NOT_MEASURED)).distinctBy { it.type },
            holders(NOT_ENTERED),
        )

        mutableClassDefBy(POPOVER_PARAMS).methods.single { it.descriptor() == sheet.descriptor() }
            .raiseSheet(height)
        for (launcher in launchers) {
            mutableClassDefBy(launcher.definingClass).methods.single { it.descriptor() == launcher.descriptor() }
                .skipSplitScreen()
        }
        enableStatus("fullHeightComments")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun BytecodePatchContext.holders(string: String) =
    classDefByStrings(string, StringComparisonType.EQUALS).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

/** The one constructor of [params], the comment sheet's parameters, that takes a float: the sheet's height. */
internal fun sheetConstructor(params: ClassDef): Method {
    val constructors = params.methods.filter { method ->
        method.name == "<init>" && method.parameterTypes.count { it.toString() == "F" } == 1
    }
    return constructors.singleOrNull()
        ?: refuse("expected one constructor of ${params.type} taking one float, found ${constructors.size}")
}

private fun width(type: String) = if (type == "J" || type == "D") 2 else 1

/**
 * The register [method]'s one float parameter arrives in. The parameters fill the last registers,
 * after `this`, so the first one sits as far from the end as the parameters are wide.
 */
internal fun floatRegister(method: Method): Int {
    val types = method.parameterTypes.map(CharSequence::toString)
    val registers = method.implementation?.registerCount ?: refuse("${method.descriptor()} has no code")
    var register = registers - types.sumOf(::width)
    for (type in types) {
        if (type == "F") return register
        register += width(type)
    }
    refuse("${method.descriptor()} takes no float")
}

/** Whether [method] answers with a boolean and builds the Intent of [SPLIT_SCREEN_FACTORY]. */
internal fun buildsSplitScreen(method: Method): Boolean =
    method.returnType == "Z" && method.implementation?.instructions?.any { instruction ->
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        instruction.opcode.name.startsWith("invoke") && call?.definingClass == SPLIT_SCREEN_FACTORY &&
            call.returnType == INTENT
    } == true

/**
 * The five split-screen launchers: the three boolean methods among [factoryHolders], the classes
 * holding [REEL_FOOTER] or [UNIT_NOT_MEASURED], that build the factory's Intent, and the two
 * boolean methods among [notEnteredHolders] that hold [NOT_ENTERED]. Each needs a local register
 * for the hook.
 */
internal fun splitScreenLaunchers(factoryHolders: List<ClassDef>, notEnteredHolders: List<ClassDef>): List<Method> {
    val built = factoryHolders.flatMap { it.methods }.filter(::buildsSplitScreen).distinctBy { it.descriptor() }
    if (built.size != 3) {
        refuse(
            "expected three boolean methods building $SPLIT_SCREEN_FACTORY's Intent beside \"$REEL_FOOTER\" or " +
                "\"$UNIT_NOT_MEASURED\", found ${built.size}: ${built.joinToString { it.descriptor() }}",
        )
    }
    val logged = notEnteredHolders.flatMap { methodsHolding(it, NOT_ENTERED) }
        .filter { it.returnType == "Z" }.distinctBy { it.descriptor() }
    if (logged.size != 2) {
        refuse(
            "expected two boolean methods logging \"$NOT_ENTERED\", found ${logged.size}: " +
                logged.joinToString { it.descriptor() },
        )
    }
    val launchers = (built + logged).distinctBy { it.descriptor() }
    if (launchers.size != 5) refuse("the split-screen launchers overlap: ${launchers.joinToString { it.descriptor() }}")
    for (launcher in launchers) {
        if (launcher.localRegisterCount() < 1) refuse("${launcher.descriptor()} has no local register for the hook")
    }
    return launchers
}

/**
 * First thing in the sheet's constructor: hand the extension the height in [register] and keep its
 * answer there. The call takes it as a range, so any register number works.
 */
internal fun MutableMethod.raiseSheet(register: Int) {
    addInstructions(
        0,
        """
            invoke-static/range { v$register .. v$register }, $SHEET_HEIGHT
            move-result v$register
        """,
    )
}

/** First thing in a split-screen launcher: while the extension says so, answer that it didn't open. */
internal fun MutableMethod.skipSplitScreen() {
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $SKIP_SPLIT_SCREEN
            move-result v0
            if-eqz v0, :launch
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("launch", getInstruction(0)),
    )
}
