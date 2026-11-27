package com.example.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which tool last touched the text: the pen or a finger. Set where a selection starts
 * ([TextSelectionGestureDetector] in the PDF and classic readers, the touch-observing frame around
 * Readium's web view), read by menus that open from a selection, so they are compact for the pen
 * and finger-sized otherwise.
 */
object LastInputTool {
    var isStylus by mutableStateOf(false)
}

/** The last highlight colour used, so the next Note… and the bar start from it. */
object LastHighlightColour {
    var hex by mutableStateOf("#FFEB3B")
}
