package dev.jason.gboardpatches.patches.gboard.registry

import com.google.gson.JsonParser
import dev.jason.gboardpatches.patches.gboard.features.macbridge.MAC_BRIDGE_FEATURE_MARKER
import dev.jason.gboardpatches.patches.gboard.features.macbridge.applyGboardMacBridgeManifest
import dev.jason.gboardpatches.patches.gboard.features.macbridge.gboardMacBridgeFeatureMarkerPatch
import dev.jason.gboardpatches.patches.gboard.features.macbridge.gboardMacBridgeLifecyclePatch
import dev.jason.gboardpatches.patches.gboard.features.macbridge.gboardMacBridgeManifestPatch
import dev.jason.gboardpatches.patches.gboard.shared.accesspoint.gboardAccessPointContributions1803Patch
import dev.jason.gboardpatches.patches.gboard.shared.gboardPatchesSettingsPatch
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GboardMacBridgePatchContractTest {
    private val repositoryRoot = repositoryRoot()

    @Test
    fun `public patch has exact identity dependencies and target compatibility`() {
        val patch = gboardMacBridgePatch

        assertEquals("Mac Bridge", patch.name)
        assertTrue(patch.default)
        assertTrue(patch.options.isEmpty())
        assertEquals(
            setOf(
                gboardPatchesSettingsPatch,
                gboardMacBridgeFeatureMarkerPatch,
                gboardMacBridgeManifestPatch,
                gboardMacBridgeLifecyclePatch,
                gboardAccessPointContributions1803Patch,
            ),
            patch.dependencies,
        )
        val publicNames = GboardPublishedPatchCatalog.morpheRegistrations
            .mapNotNull { it.name }
            .toSet()
        assertTrue(patch.dependencies.mapNotNull { it.name }.none { it in publicNames })

        val compatibility = checkNotNull(patch.compatibility).single()
        assertSame(COMPATIBILITY_GBOARD, compatibility)
    }

    @Test
    fun `product catalog declares one dedicated contribution with the lifecycle and toolbar calls`() {
        val catalog = JsonParser.parseString(
            Files.readString(repositoryRoot.resolve(CATALOG_PATH), StandardCharsets.UTF_8),
        ).asJsonObject
        val feature = catalog.getAsJsonArray("features").map { it.asJsonObject }.single {
            it["feature_id"].asString == "mac_bridge"
        }

        assertEquals("Mac Bridge", feature["public_patch_name"].asString)
        assertEquals(MAC_BRIDGE_FEATURE_MARKER, feature["feature_marker"].asString)
        assertEquals("version-sensitive", feature["migration_scope"].asString)
        assertFalse(feature.has("depends_on_feature_ids"))
        val contribution = feature.getAsJsonArray("contributions").single().asJsonObject
        assertEquals("dedicated_bytecode", contribution["anchor_family_id"].asString)
        assertTrue(contribution.getAsJsonArray("required_bindings").isEmpty)
        assertEquals(
            setOf(
                "ACCESS_POINT_CONTRIBUTIONS_1803_AFTER_CONTROLLER_CREATED",
                "ACCESS_POINT_CONTRIBUTIONS_1803_INCLUDE_ORDER_CATALOG",
                "MAC_BRIDGE_RUNTIME_ON_INPUT_VIEW_STARTED",
                "MAC_BRIDGE_RUNTIME_ON_INPUT_WINDOW_HIDDEN",
            ),
            contribution.getAsJsonArray("runtime_calls").map { it.asString }.toSet(),
        )
    }

    @Test
    fun `manifest adds only network permissions and no background components`() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(MINIMAL_MANIFEST.toByteArray(StandardCharsets.UTF_8)))

        applyGboardMacBridgeManifest(document)
        applyGboardMacBridgeManifest(document)

        val permissions = (0 until document.getElementsByTagName("uses-permission").length)
            .map { index ->
                document.getElementsByTagName("uses-permission").item(index).attributes
                    .getNamedItemNS(ANDROID_NS, "name").nodeValue
            }
        assertEquals(
            listOf(
                "android.permission.INTERNET",
                "android.permission.ACCESS_LOCAL_NETWORK",
                "android.permission.ACCESS_NETWORK_STATE",
            ),
            permissions,
        )
        listOf("service", "receiver", "provider", "activity").forEach { tag ->
            assertEquals(0, document.getElementsByTagName(tag).length)
        }
    }

    @Test
    fun `published inventory contains exactly one Mac Bridge patch`() {
        val rows = generatedPublishedPatches().filter { row ->
            row["name"].asString == "Mac Bridge"
        }

        assertEquals(1, rows.size)
        assertTrue(rows.single()["use"].asBoolean)
    }

    private fun repositoryRoot(): Path {
        val workingDirectory = Path.of("").toAbsolutePath().normalize()
        return generateSequence(workingDirectory) { it.parent }
            .first { Files.isRegularFile(it.resolve("settings.gradle.kts")) }
    }

    private companion object {
        const val CATALOG_PATH =
            "patches/src/main/resources/gboard/gboard-port-product-catalog.json"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val MINIMAL_MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.google.android.inputmethod.latin">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:label="Gboard" />
</manifest>"""
    }
}
