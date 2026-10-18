/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.postprompts

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.layout.theme.INLINE_REACTIONS
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide post prompts' Hide the reaction preview on posts hook (#158) on every Facebook build the
 * bundle declares (arm64 and armv7): FDSInlineReactions has one static factory returning a Drawable
 * subclass with a draw(Canvas), PillsBlingBarView has one onDraw(Canvas), and the patch puts the
 * extension's question in front of both. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and
 * skips without it.
 */
class ReactionPreviewFixtureTest {
    private fun ClassDef.method(name: String) =
        methods.single { isDrawMethod(it, name) }

    @Test
    fun `each declared build's two reaction drawers answer through the extension`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        val abis = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val components = FixtureDex.classesHolding(bundle, INLINE_REACTIONS)
                    .filterNot { it.type.startsWith(EXTENSION_PACKAGE) }.distinctBy { it.type }
                assertEquals("$name: components named $INLINE_REACTIONS", 1, components.size)
                val component = components.single()

                // The factory's candidates are every type a static method returns; only one is a Drawable.
                val candidates = facepileTypes(component) { true }
                assertTrue("$name: the component has no static factory", candidates.isNotEmpty())
                val loaded = FixtureDex.classes(bundle, candidates.toSet())
                val faces = facepileTypes(component) { type ->
                    loaded[type]?.let { it.superclass == DRAWABLE && it.methods.any { m -> isDrawMethod(m, "draw") } } == true
                }
                assertEquals("$name: Drawables built by the component's static factory", 1, faces.size)
                val facepile = FixtureDex.classes(bundle, setOf(faces.single())).getValue(faces.single())
                // Negative control: a type the factory returns that isn't a Drawable is not taken.
                assertTrue("$name: nothing was rejected, so the Drawable check proves nothing",
                    candidates.size == 1 || candidates.any { it != facepile.type })

                val bar = FixtureDex.classes(bundle, setOf(PILLS_BLING_BAR))[PILLS_BLING_BAR]
                assertNotNull("$name: no $PILLS_BLING_BAR", bar)
                assertEquals("$name: onDraw(Canvas) methods on the bling bar", 1, bar!!.methods.count { isDrawMethod(it, "onDraw") })

                val context = PatchContexts.of(listOf(component, facepile, bar))
                context.hideReactionPreview()
                for ((original, label) in listOf(facepile.method("draw") to "facepile", bar.method("onDraw") to "bling bar")) {
                    val type = original.definingClass
                    val patched = context.mutableClassDefBy(type).methods.single {
                        it.name == original.name && it.parameterTypes.map(CharSequence::toString) == original.parameterTypes.map(CharSequence::toString)
                    }.implementation!!.instructions.toList()
                    val before = original.implementation!!.instructions.toList()
                    assertEquals("$name: $label: four instructions added", before.size + 4, patched.size)
                    assertEquals("$name: $label: the extension is asked first", HIDE_REACTION_PREVIEW,
                        ((patched[0] as ReferenceInstruction).reference as MethodReference).toString())
                    assertEquals("$name: $label: shape of the early return",
                        listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.RETURN_VOID),
                        patched.take(4).map { it.opcode })
                    assertEquals("$name: $label: the rest is Facebook's", before.map { it.opcode }, patched.drop(4).map { it.opcode })
                }
                checked += version
                abis += name.substringAfter("-$version-").substringBefore(".apkm")
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("an ABI went unchecked", setOf("arm64-v8a", "armeabi-v7a"), abis)
    }

    @Test
    fun `the extension has the hook`() {
        val type = HIDE_REACTION_PREVIEW.substringBefore("->")
        val hook = ExtensionDex.classDef(type).methods.singleOrNull { it.name == "hideReactionPreview" }
        assertNotNull("the extension has no PostPrompts.hideReactionPreview", hook)
        assertEquals("hideReactionPreview's shape", HIDE_REACTION_PREVIEW,
            "${hook!!.definingClass}->${hook.name}(${hook.parameterTypes.joinToString("")})${hook.returnType}")
        assertTrue("hideReactionPreview isn't static", AccessFlags.STATIC.isSet(hook.accessFlags))
    }
}
