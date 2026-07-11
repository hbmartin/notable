package com.ethran.notable.editor.utils


import com.onyx.android.sdk.pen.style.StrokeStyle
import kotlinx.serialization.Serializable


enum class Pen(val penName: String) {
    BALLPEN("BALLPEN"),
    REDBALLPEN("REDBALLPEN"),
    GREENBALLPEN("GREENBALLPEN"),
    BLUEBALLPEN("BLUEBALLPEN"),
    PENCIL("PENCIL"),
    BRUSH("BRUSH"),
    MARKER("MARKER"),
    FOUNTAIN("FOUNTAIN"),
    DASHED("DASHED");

    companion object {
        fun fromString(name: String?): Pen {
            return entries.find { it.penName.equals(name, ignoreCase = true) } ?: BALLPEN
        }
    }

    val supportsPressure: Boolean
        get() = this == PENCIL || this == BRUSH || this == FOUNTAIN
}

fun penToStroke(pen: Pen): Int {
    return when (pen) {
        Pen.BALLPEN -> StrokeStyle.PENCIL
        Pen.REDBALLPEN -> StrokeStyle.PENCIL
        Pen.GREENBALLPEN -> StrokeStyle.PENCIL
        Pen.BLUEBALLPEN -> StrokeStyle.PENCIL
        Pen.PENCIL -> StrokeStyle.CHARCOAL
        Pen.BRUSH -> StrokeStyle.NEO_BRUSH
        Pen.MARKER -> StrokeStyle.MARKER
        Pen.FOUNTAIN -> StrokeStyle.FOUNTAIN
        Pen.DASHED -> StrokeStyle.DASH
    }
}


@Serializable
data class PenSetting(
    var strokeSize: Float,
    //TODO: Rename to strokeColor
    var color: Int,
    /** 1 is linear; values above 1 respond more quickly to light pressure. */
    var pressureSensitivity: Float = 1f,
    /** Minimum normalized pressure/width retained even for a very light stroke. */
    var minimumPressureRatio: Float = 0f,
)

typealias NamedSettings = Map<String, PenSetting>
