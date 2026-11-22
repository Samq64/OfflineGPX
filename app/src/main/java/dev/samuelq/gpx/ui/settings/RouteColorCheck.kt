package dev.samuelq.gpx.ui.settings

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What a custom route colour gives up, by the bars `RouteColors` was chosen to. Advice only. */
internal sealed interface ColorWarning {
    /** Under 2.5:1 on land, or 1.75:1 on water or vegetation. */
    data object LowContrast : ColorWarning

    /** Within the palette's separation of another slot. */
    data class CloseTo(val slot: Int) : ColorWarning
}

/** The map colours a route is drawn over, as ARGB. */
internal data class MapGround(val land: Int, val water: Int, val vegetation: Int)

/** The worst, if any: a line that can't be seen matters more than one like another. */
internal fun colorWarning(argb: Int, slot: Int, palette: List<Int>, dark: Boolean, ground: MapGround): ColorWarning? {
    if (contrast(argb, ground.land) < LAND_CONTRAST ||
        minOf(contrast(argb, ground.water), contrast(argb, ground.vegetation)) < AREA_CONTRAST
    ) {
        return ColorWarning.LowContrast
    }
    val apart = if (dark) DARK_SEPARATION else LIGHT_SEPARATION
    val lab = lab(argb)
    return palette.indices
        .filter { it != slot }
        .minByOrNull { deltaE2000(lab, lab(palette[it])) }
        ?.takeIf { deltaE2000(lab, lab(palette[it])) < apart }
        ?.let(ColorWarning::CloseTo)
}

/** WCAG's contrast ratio. */
internal fun contrast(a: Int, b: Int): Double {
    val (la, lb) = luminance(a) to luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private fun channels(argb: Int) = Triple(
    (argb shr 16 and 0xFF) / 255.0,
    (argb shr 8 and 0xFF) / 255.0,
    (argb and 0xFF) / 255.0,
)

private fun linear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

private fun luminance(argb: Int): Double {
    val (r, g, b) = channels(argb)
    return 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
}

/** CIELAB under D65. */
internal fun lab(argb: Int): DoubleArray {
    val (r, g, b) = channels(argb).let { (r, g, b) -> Triple(linear(r), linear(g), linear(b)) }
    fun f(t: Double) = if (t > 216.0 / 24389) cbrt(t) else (24389.0 / 27 * t + 16) / 116
    val fx = f((0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047)
    val fy = f(0.2126729 * r + 0.7151522 * g + 0.0721750 * b)
    val fz = f((0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883)
    return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
}

/** CIEDE2000, as Sharma, Wu and Dalal give it. */
internal fun deltaE2000(lab1: DoubleArray, lab2: DoubleArray): Double {
    val (l1, a1, b1) = lab1
    val (l2, a2, b2) = lab2
    val cBar = (hypot(a1, b1) + hypot(a2, b2)) / 2
    val g = 0.5 * (1 - sqrt(cBar.pow(7) / (cBar.pow(7) + 25.0.pow(7))))
    val a1p = (1 + g) * a1
    val a2p = (1 + g) * a2
    val c1p = hypot(a1p, b1)
    val c2p = hypot(a2p, b2)
    fun hue(b: Double, a: Double) = if (a == 0.0 && b == 0.0) 0.0 else Math.toDegrees(atan2(b, a)).mod(360.0)
    val h1p = hue(b1, a1p)
    val h2p = hue(b2, a2p)
    val chromatic = c1p * c2p != 0.0

    val dLp = l2 - l1
    val dCp = c2p - c1p
    val dhp = when {
        !chromatic -> 0.0
        abs(h2p - h1p) <= 180 -> h2p - h1p
        h2p - h1p > 180 -> h2p - h1p - 360
        else -> h2p - h1p + 360
    }
    val dHp = 2 * sqrt(c1p * c2p) * sin(Math.toRadians(dhp / 2))

    val lBarP = (l1 + l2) / 2
    val cBarP = (c1p + c2p) / 2
    val hBarP = when {
        !chromatic -> h1p + h2p
        abs(h1p - h2p) <= 180 -> (h1p + h2p) / 2
        h1p + h2p < 360 -> (h1p + h2p + 360) / 2
        else -> (h1p + h2p - 360) / 2
    }
    fun cosd(deg: Double) = cos(Math.toRadians(deg))
    val t = 1 - 0.17 * cosd(hBarP - 30) + 0.24 * cosd(2 * hBarP) +
        0.32 * cosd(3 * hBarP + 6) - 0.20 * cosd(4 * hBarP - 63)
    val dTheta = 30 * exp(-((hBarP - 275) / 25).pow(2))
    val rc = 2 * sqrt(cBarP.pow(7) / (cBarP.pow(7) + 25.0.pow(7)))
    val sl = 1 + 0.015 * (lBarP - 50).pow(2) / sqrt(20 + (lBarP - 50).pow(2))
    val sc = 1 + 0.045 * cBarP
    val sh = 1 + 0.015 * cBarP * t
    val rt = -sin(Math.toRadians(2 * dTheta)) * rc
    val (l, c, h) = Triple(dLp / sl, dCp / sc, dHp / sh)
    return sqrt(l * l + c * c + h * h + rt * c * h)
}

private const val LAND_CONTRAST = 2.5
private const val AREA_CONTRAST = 1.75
private const val DARK_SEPARATION = 25.0
private const val LIGHT_SEPARATION = 18.0
