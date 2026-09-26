package dev.jason.gboardpatches.patches.gboard.features.macbridge

import app.morphe.patcher.patch.resourcePatch
import dev.jason.gboardpatches.patches.gboard.features.featureflags.applyFeatureMarker
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardMacBridgeFeatureMarkerPatch = resourcePatch(
    description = "標記 Mac Bridge feature 已被打入 target APK。",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    finalize {
        applyFeatureMarker(MAC_BRIDGE_FEATURE_MARKER)
    }
}

internal const val MAC_BRIDGE_FEATURE_MARKER =
    "dev.jason.gboardpatches.feature.mac_bridge"
