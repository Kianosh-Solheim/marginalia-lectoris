package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which tool last touched the text: the pen or a finger. Set where a selection starts
 * ([TextSelectionGestureDetector]), read by menus that open from a selection, so they are
 * compact for the pen and finger-sized otherwise. Readers that do not report it (Readium's
 * web view) leave it on finger, the safe size.
 */
object LastInputTool {
    var isStylus by mutableStateOf(false)
}

/** The last highlight colour used, so the next Note… and the bar start from it. */
object LastHighlightColour {
    var hex by mutableStateOf("#FFEB3B")
}
