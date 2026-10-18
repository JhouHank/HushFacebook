/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.requireLocals
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method

/*
 * Open feed videos on their own (#161). Read from 583, 2026-10-18, arm64; the obfuscated names here
 * are for reviewers, the code never writes one down.
 *
 * - A video tapped in the feed (arm64 LX/86Q;->A04, the in-feed video click) goes on to Facebook's
 *   "Warion" step (LX/86Q;->A02), the one method holding the onWarionEligibleStoryClick trace. It
 *   seeds the full-screen player with that one video and launches it. Its other callers (LX/8By;
 *   and a posted LX/9hU;) are the same hop from elsewhere in a feed video.
 * - The player's reels feed asks for more through the method holding the
 *   VideoHomeDataControllerImpl.fetchMoreDataInternally trace (LX/5Kv;->A00(LX/5cy;String)Z), an
 *   instance method answering whether a fetch went out. Deep links to the viewer
 *   (fb://fb_shorts/viewer) don't pass through the Warion step at all.
 * - The player a feed reel opens (ImmersiveActivity with WatchFeedOrWarionFragment, S22 2026-10-18)
 *   passes neither: its reels come through the client-side ranking loader, whose fetch more
 *   (LX/5Xw;->A0X(LX/1jT;II)Z, AbstractVideoHomeCSRDataLoaderAdapter.onFetchMore) already answers
 *   false for a seed video when Facebook's own data source says so, logging "onFetchMore skipped:
 *   suppressing tail load for seed video".
 *
 * The Warion step tells the extension a feed video is opening. Both fetch mores ask it first, with
 * the object asking, and answer false (nothing fetched) when the extension says so.
 */

internal const val FEED_VIDEO_ALONE = "$EXTENSION_PACKAGE/reels/FeedVideoAlone;"
internal const val FEED_VIDEO_TAPPED = "$FEED_VIDEO_ALONE->feedVideoTapped()V"
internal const val HOLDS_BACK = "$FEED_VIDEO_ALONE->holdsBack(Ljava/lang/Object;)Z"

/** The trace only Facebook's Warion step, a feed video opening its player, holds. */
internal const val WARION_CLICK = "onWarionEligibleStoryClick"

/** The trace only the reels feed's fetch more holds. */
internal const val FETCH_MORE = "VideoHomeDataControllerImpl.fetchMoreDataInternally"

/** The line only the client-side ranking loader's fetch more logs, when Facebook keeps a seed video alone. */
internal const val SEED_TAIL = "onFetchMore skipped: suppressing tail load for seed video"

private const val PATCH = "Clean up Reels"

/** Whether [method] can be the Warion step: it holds the trace and answers nothing. */
internal fun isWarionStep(method: Method): Boolean = method.returnType == "V" && holdsString(method, WARION_CLICK)

/** Whether [method] can be the fetch more: an instance method holding the trace and answering a boolean. */
internal fun isFetchMore(method: Method): Boolean =
    method.returnType == "Z" && !AccessFlags.STATIC.isSet(method.accessFlags) && holdsString(method, FETCH_MORE)

/** Whether [method] can be the loader's fetch more: an instance method holding its line and answering a boolean. */
internal fun isLoaderFetchMore(method: Method): Boolean =
    method.returnType == "Z" && !AccessFlags.STATIC.isSet(method.accessFlags) && holdsString(method, SEED_TAIL)

/** The one method in the app, outside the extension, that [matches] among the classes holding [anchor]. */
private fun BytecodePatchContext.oneMethodHolding(anchor: String, what: String, matches: (Method) -> Boolean): MutableMethod {
    val found = classDefByStrings(anchor, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
        .flatMap { owner -> owner.methods.filter(matches).map { owner to it } }
    val (owner, method) = found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $what holding \"$anchor\", found ${found.size}")
    return mutableClassDefBy(owner.type).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }
}

/**
 * Answers false before the fetch runs when the extension says so. At the first instruction no local
 * holds anything yet, so v0 is free, and `this` is copied down to it for the call, which names its
 * operand in four bits.
 */
internal fun MutableMethod.holdBackWhen(hook: String) {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            invoke-static { v0 }, $hook
            move-result v0
            if-eqz v0, :facebooks_fetch
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("facebooks_fetch", getInstruction(0)),
    )
}

/** Hooks the Warion step and both reels fetch mores. Refuses unless each anchor finds one method. */
internal fun BytecodePatchContext.keepFeedVideosAlone() {
    oneMethodHolding(WARION_CLICK, "Warion step answering void", ::isWarionStep)
        .addInstruction(0, "invoke-static { }, $FEED_VIDEO_TAPPED")
    oneMethodHolding(FETCH_MORE, "instance fetch more answering a boolean", ::isFetchMore)
        .holdBackWhen(HOLDS_BACK)
    oneMethodHolding(SEED_TAIL, "instance loader fetch more answering a boolean", ::isLoaderFetchMore)
        .holdBackWhen(HOLDS_BACK)
}
