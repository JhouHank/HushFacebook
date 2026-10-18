/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clean up Reels' Open feed videos on their own hooks (#161) on every Facebook build the bundle
 * declares (arm64 and armv7): one void method holds the Warion trace and tells the extension first,
 * and one instance fetch more holds its trace and answers false before fetching when the extension
 * says so. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FeedVideoAloneFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun called(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction) =
        ((instruction as ReferenceInstruction).reference as MethodReference).toString()

    @Test
    fun `each declared build's feed video tap and fetch more ask the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val warionOwners = FixtureDex.classesHolding(bundle, WARION_CLICK)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val warions = warionOwners.flatMap { owner -> owner.methods.filter(::isWarionStep).map { owner to it } }
                assertEquals("$name: void methods holding $WARION_CLICK", 1, warions.size)
                val fetchOwners = FixtureDex.classesHolding(bundle, FETCH_MORE)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                val fetches = fetchOwners.flatMap { owner -> owner.methods.filter(::isFetchMore).map { owner to it } }
                assertEquals("$name: instance Z methods holding $FETCH_MORE", 1, fetches.size)
                val (warionOwner, warion) = warions.single()
                val (fetchOwner, fetch) = fetches.single()

                // Positive control: the check turns down the trace's other holders of the same class, and
                // a static or void copy of the fetch more wouldn't pass it.
                assertTrue("$name: the fetch more is static", !AccessFlags.STATIC.isSet(fetch.accessFlags))
                assertTrue("$name: another method of the class passes the Warion check",
                    warionOwner.methods.filterNot { it.sameAs(warion) }.none(::isWarionStep))

                val context = PatchContexts.of(listOf(warionOwner, fetchOwner).distinctBy { it.type })
                context.keepFeedVideosAlone()

                val tapped = context.mutableClassDefBy(warionOwner.type).methods.single { it.sameAs(warion) }.code()
                assertEquals("$name: one instruction added to the Warion step", warion.code().size + 1, tapped.size)
                assertEquals("$name: the Warion step tells the extension first", FEED_VIDEO_TAPPED, called(tapped[0]))
                assertEquals("$name: the rest of the Warion step is Facebook's",
                    warion.code().map { it.opcode }, tapped.drop(1).map { it.opcode })

                val asked = context.mutableClassDefBy(fetchOwner.type).methods.single { it.sameAs(fetch) }.code()
                assertEquals("$name: six instructions added to the fetch more", fetch.code().size + 6, asked.size)
                assertEquals("$name: shape of the early answer",
                    listOf(Opcode.MOVE_OBJECT_FROM16, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
                        Opcode.CONST_4, Opcode.RETURN),
                    asked.take(6).map { it.opcode })
                assertEquals("$name: the fetch more asks the extension", HOLDS_BACK, called(asked[1]))
                assertEquals("$name: the rest of the fetch more is Facebook's",
                    fetch.code().map { it.opcode }, asked.drop(6).map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun `the extension has both hooks`() {
        for (hook in listOf(FEED_VIDEO_TAPPED, HOLDS_BACK)) {
            val type = hook.substringBefore("->")
            val name = hook.substringAfter("->").substringBefore("(")
            val method = ExtensionDex.classDef(type).methods.singleOrNull { it.name == name }
            assertNotNull("the extension has no $hook", method)
            assertEquals("$name's shape", hook,
                "${method!!.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}")
            assertTrue("$name isn't static", AccessFlags.STATIC.isSet(method.accessFlags))
        }
    }
}
