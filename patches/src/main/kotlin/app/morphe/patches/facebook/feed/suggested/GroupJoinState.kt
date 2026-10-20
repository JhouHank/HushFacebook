/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.facebook.feed.BASE_MODEL_WITH_TREE
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.fillStoryModelStub
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isModelEnumAccessor
import app.morphe.patches.facebook.feed.storyModelAccessors
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

/*
 * Posts from groups you aren't in (#195). Read from 583, 2026-10-20; the obfuscated names are for
 * reviewers, the code never writes one down.
 *
 * A friend's post in a public group reaches Home as an ordinary story with a Join button next to the
 * group's name. The story's `to` (GraphQLStory.A0i, key 0xe7b as Profile 0xcce99c59) is the group, and
 * answers getTypeName() "Group", which is how Facebook's own LX/CE5;->A00 tells a group post apart.
 * The profile model (arm64 LX/40B, a direct BaseModelWithTree) reads viewer_join_state (0x5b7a8189)
 * through getCachedEnum as LX/3u7, whose constants are UNSET_OR_UNRECOGNIZED_ENUM_VALUE,
 * CANNOT_JOIN_OR_REQUEST, CAN_JOIN, CAN_REQUEST, MEMBER and REQUESTED. Facebook's join flow
 * (LX/2lC;->A01) writes the new state into that same `to`, and its Join button rule (LX/CMJ;->A01)
 * shows for CAN_JOIN and CAN_REQUEST. Home asks for the state only while MobileConfig
 * 0x10109e50005571b says so (LX/1aT;->A01, "should_include_viewer_join_state"), so an account without
 * it reads the unset default and keeps every post.
 */

internal const val STORY_TARGET_FIELD = "to"
internal const val STORY_TARGET_TYPE = "Profile"
internal const val VIEWER_JOIN_STATE_FIELD = "viewer_join_state"

/**
 * Names the join state enum carries. The two Join button states are the extension's
 * `GroupPosts.NOT_JOINED`; MEMBER is the state that keeps your groups' posts.
 */
internal val JOIN_STATE_NAMES = listOf("CAN_JOIN", "CAN_REQUEST", "MEMBER")

/** The extension class that reads the join state, and the two accessors this patch fills in. */
internal const val GROUP_POSTS = "$EXTENSION_PACKAGE/feed/GroupPosts;"
internal const val STORY_TARGET_STUB = "storyTarget"
internal const val JOIN_STATE_STUB = "joinState"

/** Every accessor [story] declares of its `to` as a Profile. The patch wants exactly one. */
internal fun storyTargetAccessors(story: ClassDef): List<Method> =
    storyModelAccessors(story, STORY_TARGET_FIELD, STORY_TARGET_TYPE)

/** Every accessor [profile] declares of its join state. The patch wants exactly one. */
internal fun joinStateAccessors(profile: ClassDef): List<Method> =
    profile.methods.filter { isModelEnumAccessor(it, VIEWER_JOIN_STATE_FIELD) }

/** Whether [classDef] is the join state enum: an Enum whose static initializer names [JOIN_STATE_NAMES]. */
internal fun isJoinStateEnum(classDef: ClassDef): Boolean {
    if (classDef.superclass != "Ljava/lang/Enum;") return false
    val init = classDef.methods.firstOrNull { it.name == "<clinit>" } ?: return false
    return JOIN_STATE_NAMES.all { holdsString(init, it) }
}

/** The story's accessor of its `to`, and that profile's accessor of its join state. */
internal class GroupJoinAccessors(val target: Method, val joinState: Method)

/**
 * The two accessors, held to the evidence the rule rests on: one `to` accessor on GraphQLStory, its
 * profile a tree model the extension can ask for a type name, one join state accessor on that
 * profile, and an enum answering it that names the states. Throws a [PatchException] otherwise.
 */
internal fun BytecodePatchContext.groupJoinAccessors(): GroupJoinAccessors {
    val targets = storyTargetAccessors(classDefBy(GRAPHQL_STORY))
    val target = targets.singleOrNull() ?: throw PatchException(
        "GraphQLStory has ${targets.size} accessors of $STORY_TARGET_FIELD as $STORY_TARGET_TYPE, expected one: " +
            targets.joinToString { it.name },
    )
    val profile = classDefByOrNull(target.returnType)
        ?: throw PatchException("${target.returnType}, GraphQLStory's $STORY_TARGET_FIELD, isn't in this Facebook build")
    if (profile.superclass != BASE_MODEL_WITH_TREE) {
        throw PatchException("${profile.type} isn't a BaseModelWithTree, so its type name can't be read")
    }
    val states = joinStateAccessors(profile)
    val state = states.singleOrNull() ?: throw PatchException(
        "${profile.type} has ${states.size} accessors of $VIEWER_JOIN_STATE_FIELD, expected one: " +
            states.joinToString { it.name },
    )
    val enum = classDefByOrNull(state.returnType)
    if (enum == null || !isJoinStateEnum(enum)) {
        throw PatchException("${state.returnType} isn't an enum naming ${JOIN_STATE_NAMES.joinToString()}")
    }
    return GroupJoinAccessors(target, state)
}

/** Fills in the extension's two GroupPosts stubs with the accessors [groupJoinAccessors] finds. */
internal fun BytecodePatchContext.fillGroupJoinStubs() {
    val found = groupJoinAccessors()
    fillStoryModelStub(GROUP_POSTS, STORY_TARGET_STUB, found.target)
    fillStoryModelStub(GROUP_POSTS, JOIN_STATE_STUB, found.joinState)
}
