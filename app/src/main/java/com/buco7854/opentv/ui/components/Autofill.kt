package com.buco7854.opentv.ui.components

import androidx.compose.runtime.Composable
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
 * Lets a password manager / the system fill a text field. Registers an
 * [AutofillNode] with the platform autofill tree, reports its on-screen bounds,
 * and asks for a fill suggestion while the field has focus.
 *
 * Compose 1.8 replaces this with `semantics { contentType = ... }`; until we
 * bump the Compose BOM, this is the supported way to opt a field into autofill.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Modifier.autofill(
    types: List<AutofillType>,
    onFill: (String) -> Unit,
): Modifier {
    val autofill = LocalAutofill.current
    val node = AutofillNode(autofillTypes = types, onFill = onFill)
    LocalAutofillTree.current += node

    return this
        .onGloballyPositioned { node.boundingBox = it.boundsInWindow() }
        .onFocusChanged { focusState ->
            autofill?.run {
                if (focusState.isFocused) requestAutofillForNode(node)
                else cancelAutofillForNode(node)
            }
        }
}
