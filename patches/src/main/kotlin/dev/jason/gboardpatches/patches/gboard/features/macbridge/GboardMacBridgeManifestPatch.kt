package dev.jason.gboardpatches.patches.gboard.features.macbridge

import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.resourcePatch
import dev.jason.gboardpatches.patches.gboard.shared.childElements
import dev.jason.gboardpatches.patches.gboard.shared.ensureManifestUsesPermission
import dev.jason.gboardpatches.patches.gboard.shared.manifestAndroidAttribute
import dev.jason.gboardpatches.patches.gboard.shared.setManifestAndroidAttribute
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD
import org.w3c.dom.Document
import org.w3c.dom.Element

internal val gboardMacBridgeManifestPatch = resourcePatch(
    description = "注入 Mac Bridge 所需的區域網路、麥克風 permissions、語音辨識 query 與工具列圖示。",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        document("AndroidManifest.xml").use(::applyGboardMacBridgeManifest)
        MAC_BRIDGE_TOOLBAR_DRAWABLES.forEach { name -> copyMacBridgeToolbarDrawable(name) }
    }
}

internal fun applyGboardMacBridgeManifest(document: Document) {
    val manifest = document.documentElement
    check(manifest.childElements("application").count() == 1) {
        "Expected exactly one application element in AndroidManifest.xml"
    }
    MAC_BRIDGE_PERMISSIONS.forEach { permissionName ->
        ensureManifestUsesPermission(document, manifest, permissionName)
    }
    ensureRecognitionServiceQuery(document, manifest)
}

/** Android 11+ package visibility: SpeechRecognizer must see the recognition service. */
private fun ensureRecognitionServiceQuery(document: Document, manifest: Element) {
    val queries = manifest.childElements("queries").firstOrNull()
        ?: document.createElement("queries").also { created ->
            val application = manifest.childElements("application").firstOrNull()
            if (application == null) {
                manifest.appendChild(created)
            } else {
                manifest.insertBefore(created, application)
            }
        }
    val present = queries.childElements("intent").any { intent ->
        intent.childElements("action").any { action ->
            action.manifestAndroidAttribute("name") == RECOGNITION_SERVICE_ACTION
        }
    }
    if (!present) {
        val intent = document.createElement("intent").also(queries::appendChild)
        document.createElement("action").also { action ->
            action.setManifestAndroidAttribute("name", RECOGNITION_SERVICE_ACTION)
            intent.appendChild(action)
        }
    }
}

context(context: ResourcePatchContext)
private fun copyMacBridgeToolbarDrawable(name: String) = with(context) {
    val resourcePath = "$MAC_BRIDGE_RESOURCE_ROOT/drawable/$name.xml"
    val bytes = object {}.javaClass.classLoader.getResourceAsStream(resourcePath)?.use { stream ->
        stream.readBytes()
    } ?: error("Mac Bridge toolbar drawable resource \"$resourcePath\" not found")
    val targetFile = this["res/drawable/$name.xml", false]
    targetFile.parentFile?.mkdirs()
    targetFile.outputStream().use { output -> output.write(bytes) }
}

// No service or notification: the keyboard holds the Mac connection only while it is showing.
// Gboard already holds RECORD_AUDIO for its own voice typing; dictation to the Mac reuses it.
private val MAC_BRIDGE_PERMISSIONS = listOf(
    "android.permission.ACCESS_LOCAL_NETWORK",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.INTERNET",
    "android.permission.RECORD_AUDIO",
)

internal val MAC_BRIDGE_TOOLBAR_DRAWABLES = listOf(
    "gboard_patches_mac_bridge_send",
    "gboard_patches_mac_bridge_dictate",
)
private const val RECOGNITION_SERVICE_ACTION = "android.speech.RecognitionService"
private const val MAC_BRIDGE_RESOURCE_ROOT = "mac-bridge-res"
