package dev.jason.gboardpatches.patches.gboard.features.macbridge

import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import dev.jason.gboardpatches.patches.gboard.shared.childElements
import dev.jason.gboardpatches.patches.gboard.shared.ensureManifestUsesPermission
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD
import org.w3c.dom.Document

internal val gboardMacBridgeManifestPatch = resourcePatch(
    description = "注入 Mac Bridge 所需的區域網路 permissions 與工具列圖示。",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        document("AndroidManifest.xml").use(::applyGboardMacBridgeManifest)
        copyMacBridgeToolbarDrawable()
    }
}

internal fun applyGboardMacBridgeManifest(document: Document) {
    val manifest = document.documentElement
    check(manifest.childElements("application").size == 1) {
        "Expected exactly one application element in AndroidManifest.xml"
    }
    MAC_BRIDGE_PERMISSIONS.forEach { permissionName ->
        ensureManifestUsesPermission(document, manifest, permissionName)
    }
}

context(context: ResourcePatchContext)
private fun copyMacBridgeToolbarDrawable() = with(context) {
    val resourcePath = "$MAC_BRIDGE_RESOURCE_ROOT/drawable/$MAC_BRIDGE_TOOLBAR_DRAWABLE.xml"
    val bytes = object {}.javaClass.classLoader.getResourceAsStream(resourcePath)?.use { stream ->
        stream.readBytes()
    } ?: error("Mac Bridge toolbar drawable resource \"$resourcePath\" not found")
    val targetFile = this["res/drawable/$MAC_BRIDGE_TOOLBAR_DRAWABLE.xml", false]
    targetFile.parentFile?.mkdirs()
    targetFile.outputStream().use { output -> output.write(bytes) }
}

// No service or notification: the keyboard holds the Mac connection only while it is showing.
private val MAC_BRIDGE_PERMISSIONS = listOf(
    "android.permission.ACCESS_LOCAL_NETWORK",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.INTERNET",
)

internal const val MAC_BRIDGE_TOOLBAR_DRAWABLE = "gboard_patches_mac_bridge_send"
private const val MAC_BRIDGE_RESOURCE_ROOT = "mac-bridge-res"
