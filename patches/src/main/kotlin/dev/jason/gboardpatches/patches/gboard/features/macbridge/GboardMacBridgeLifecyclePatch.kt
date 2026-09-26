package dev.jason.gboardpatches.patches.gboard.features.macbridge

import app.morphe.patcher.patch.bytecodePatch
import dev.jason.gboardpatches.patches.gboard.shared.applyVoidExitLifecycleDelegate
import dev.jason.gboardpatches.patches.gboard.shared.findMutableMethodOrThrow
import dev.jason.gboardpatches.patches.gboard.shared.gboardPatchesExtensionCarrierPatch
import dev.jason.gboardpatches.patches.gboard.shared.runtimeabi.RuntimeCallId
import dev.jason.gboardpatches.patches.shared.Constants.COMPATIBILITY_GBOARD

internal val gboardMacBridgeLifecyclePatch = bytecodePatch(
    description = "在鍵盤顯示與隱藏時接入 Mac Bridge 連線 lifecycle。",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(gboardPatchesExtensionCarrierPatch)

    execute {
        GboardMacBridge1803Targets.validate(this)
        findMutableMethodOrThrow(GboardMacBridge1803Targets.onStartInputView)
            .applyVoidExitLifecycleDelegate(
                RuntimeCallId.MAC_BRIDGE_RUNTIME_ON_INPUT_VIEW_STARTED,
                "p0 .. p0",
            )
        findMutableMethodOrThrow(GboardMacBridge1803Targets.onWindowHidden)
            .applyVoidExitLifecycleDelegate(
                RuntimeCallId.MAC_BRIDGE_RUNTIME_ON_INPUT_WINDOW_HIDDEN,
                "",
            )
    }
}
