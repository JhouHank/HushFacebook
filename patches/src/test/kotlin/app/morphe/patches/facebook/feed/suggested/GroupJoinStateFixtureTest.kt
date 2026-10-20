/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.BASE_MODEL_WITH_TREE
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.isStoryModelAccessor
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide posts from groups you're not in (#195) on every Facebook build the bundle declares: one
 * GraphQLStory accessor of `to` as a Profile, its model a BaseModelWithTree with one accessor of
 * viewer_join_state, and the enum answering it naming every state the extension reads. The patch
 * fills the extension's two stubs with calls to those accessors. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class GroupJoinStateFixtureTest {
    /** Every name of Facebook 583's join state enum, the two that hide a post and the four that keep it. */
    private val allStates = listOf(
        "CAN_JOIN", "CAN_REQUEST", "MEMBER", "REQUESTED", "CANNOT_JOIN_OR_REQUEST", "UNSET_OR_UNRECOGNIZED_ENUM_VALUE",
    )

    private fun ClassDef.stub(name: String): Method = methods.single { it.name == name }

    /** The first two instructions of a filled stub: the cast to the accessor's class, and the call. */
    private fun Method.castAndCall(): Pair<String, String> {
        val code = implementation!!.instructions.toList()
        assertEquals("$name: cast first", Opcode.CHECK_CAST, code[0].opcode)
        assertEquals("$name: call second", Opcode.INVOKE_VIRTUAL, code[1].opcode)
        val cast = ((code[0] as ReferenceInstruction).reference as TypeReference).type
        val call = (code[1] as ReferenceInstruction).reference as MethodReference
        return cast to "${call.definingClass}->${call.name}()${call.returnType}"
    }

    @Test
    fun `each declared build has one group target and one join state, and the stubs call them`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val story = FixtureDex.classes(bundle, setOf(GRAPHQL_STORY))[GRAPHQL_STORY]
                    ?: throw AssertionError("$name has no $GRAPHQL_STORY")
                val targets = storyTargetAccessors(story)
                assertEquals("$name: ${targets.map { it.name }}", 1, targets.size)
                val target = targets.single()
                // The control: the story's other accessors of the same model class load other keys,
                // so only `to` matches.
                val siblings = story.methods.filter {
                    it.name != target.name && it.parameterTypes.isEmpty() && it.returnType == target.returnType
                }
                for (sibling in siblings) {
                    assertFalse("$name: ${sibling.name}() matches too",
                        isStoryModelAccessor(sibling, STORY_TARGET_FIELD, STORY_TARGET_TYPE))
                }

                val profile = FixtureDex.classes(bundle, setOf(target.returnType))[target.returnType]
                    ?: throw AssertionError("$name has no ${target.returnType}")
                assertEquals("$name: ${profile.type} answers getTypeName as a tree model", BASE_MODEL_WITH_TREE,
                    profile.superclass)
                val states = joinStateAccessors(profile)
                assertEquals("$name: ${states.map { it.name }}", 1, states.size)
                val state = states.single()
                val enum = FixtureDex.classes(bundle, setOf(state.returnType))[state.returnType]
                    ?: throw AssertionError("$name has no ${state.returnType}")
                assertTrue("$name: ${enum.type} isn't the join state enum", isJoinStateEnum(enum))
                val init = enum.methods.single { it.name == "<clinit>" }
                for (constant in allStates) {
                    assertTrue("$name: ${enum.type} doesn't name $constant", holdsString(init, constant))
                }

                val context = PatchContexts.of(listOf(story, profile, enum, ExtensionDex.classDef(GROUP_POSTS)))
                context.fillGroupJoinStubs()
                val filled = context.mutableClassDefBy(GROUP_POSTS)
                assertEquals("$name: storyTarget",
                    GRAPHQL_STORY to "$GRAPHQL_STORY->${target.name}()${target.returnType}",
                    filled.stub(STORY_TARGET_STUB).castAndCall())
                assertEquals("$name: joinState",
                    profile.type to "${profile.type}->${state.name}()${state.returnType}",
                    filled.stub(JOIN_STATE_STUB).castAndCall())
                checked[version] = "${target.name}()${target.returnType} -> ${state.name}()${state.returnType}"
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }

    /** The field names and the group type the extension reads by are the ones the patch finds by. */
    @Test
    fun `the extension reads the names the patch anchors on`() {
        assertEquals(STORY_TARGET_FIELD, ExtensionDex.stringConstant(GROUP_POSTS, "TARGET_FIELD"))
        assertEquals(VIEWER_JOIN_STATE_FIELD, ExtensionDex.stringConstant(GROUP_POSTS, "JOIN_STATE_FIELD"))
        assertEquals("Group", ExtensionDex.stringConstant(GROUP_POSTS, "GROUP_TYPE"))
        val stubs = ExtensionDex.classDef(GROUP_POSTS).methods.map { it.name }
        assertTrue("the extension has no $STORY_TARGET_STUB", STORY_TARGET_STUB in stubs)
        assertTrue("the extension has no $JOIN_STATE_STUB", JOIN_STATE_STUB in stubs)
    }
}
