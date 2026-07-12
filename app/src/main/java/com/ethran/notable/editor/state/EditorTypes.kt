package com.ethran.notable.editor.state

/**
 * Drawing mode for the editor.
 */
enum class Mode {
    Draw, Erase, Select, Line, Text, Link
}

/**
 * Placement mode for selection operations.
 * If state is Move then applySelectionDisplace() will delete original strokes and images.
 */
enum class PlacementMode {
    Move, Paste
}
