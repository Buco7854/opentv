package com.buco7854.opentv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.AutofillNode
import androidx.compose.ui.autofill.AutofillType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalAutofill
import androidx.compose.ui.platform.LocalAutofillTree

/**
 * Opts a text field into platform autofill.
 *
 * Pre-Compose-1.8 approach; replaced by `semantics { contentType = ... }` once the BOM is bumped.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.autofill(
    types: List<AutofillType>,
    onFill: (String) -> Unit,
): Modifier {
    val autofill = LocalAutofill.current
    val tree = LocalAutofillTree.current
    val fill by rememberUpdatedState(onFill)
    val node = remember(types) {
        AutofillNode(autofillTypes = types, onFill = { fill(it) })
    }
    DisposableEffect(node) {
        tree += node
        onDispose { tree.children.remove(node.id) }
    }

    return this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { focusState ->
            autofill?.run {
                if (focusState.isFocused) requestAutofillForNode(node)
                else cancelAutofillForNode(node)
            }
        }
}
