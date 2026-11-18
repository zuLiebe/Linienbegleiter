package com.example.linienbegleiter.data.routes

import java.util.Locale

/**
 * Palette for map routes, selected to remain distinguishable over common map backgrounds.
 *
 * Values are stable ARGB colors so they can be stored in Room and route export formats.
 */
object RouteColorPalette {
    val colors: List<Int> = listOf(
        0xFFC62835.toInt(),
        0xFF1565C0.toInt(),
        0xFF00897B.toInt(),
        0xFF7B1FA2.toInt(),
        0xFFEF6C00.toInt(),
        0xFF558B2F.toInt(),
        0xFF5D4037.toInt(),
        0xFF455A64.toInt(),
        0xFFD81B60.toInt(),
        0xFF283593.toInt()
    )

    /** Returns a repeatable palette color for routes that have no saved choice yet. */
    fun automaticFor(routeId: String): Int =
        colors[Math.floorMod(routeId.hashCode(), colors.size)]

    /** Converts ARGB to a CSS color accepted by MapLibre paint properties. */
    fun toMapLibreColor(argb: Int): String =
        String.format(Locale.US, "#%06X", argb and 0x00FFFFFF)
}
