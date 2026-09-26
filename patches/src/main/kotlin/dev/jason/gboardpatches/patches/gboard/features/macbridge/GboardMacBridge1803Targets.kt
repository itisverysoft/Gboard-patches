package dev.jason.gboardpatches.patches.gboard.features.macbridge

import app.morphe.patcher.patch.BytecodePatchContext
import dev.jason.gboardpatches.patches.gboard.shared.GboardMethodTarget
import dev.jason.gboardpatches.patches.gboard.shared.mutableClass

/** Reviewed 18.0.3 IME lifecycle shape. Connection policy lives in the extension. */
internal object GboardMacBridge1803Targets {
    private const val OWNER = "Loup;"
    private const val INPUT_METHOD_SERVICE = "Landroid/inputmethodservice/InputMethodService;"

    val onStartInputView = GboardMethodTarget(
        classType = OWNER,
        name = "onStartInputView",
        parameterTypes = listOf("Landroid/view/inputmethod/EditorInfo;", "Z"),
        returnType = "V",
    )
    val onWindowHidden = GboardMethodTarget(
        classType = OWNER,
        name = "onWindowHidden",
        parameterTypes = emptyList(),
        returnType = "V",
    )

    fun validate(context: BytecodePatchContext) {
        var owner: String? = OWNER
        val seen = mutableSetOf<String>()
        while (owner != null && owner != INPUT_METHOD_SERVICE && seen.add(owner)) {
            owner = context.mutableClass(owner).superclass
        }
        check(owner == INPUT_METHOD_SERVICE) {
            "Mac Bridge lifecycle owner must extend InputMethodService"
        }
        listOf(onStartInputView, onWindowHidden).forEach { target ->
            check(target.resolve(context).implementation != null) {
                "Missing body: ${target.reference}"
            }
        }
    }
}
