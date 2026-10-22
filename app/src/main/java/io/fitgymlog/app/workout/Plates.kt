package io.fitgymlog.app.workout

import kotlin.math.roundToLong

data class PlateLoad(val side: List<Double>, val leftover: Double)

/** Barbell maths in the display unit: which plates go on each side for a total weight, and back. */
object Plates {
    val KG_PLATES = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)
    val LB_PLATES = listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5)
    val KG_BARS = listOf(20.0, 15.0, 10.0)
    val LB_BARS = listOf(45.0, 35.0, 25.0)

    /**
     * Heaviest plates first, as you would load them. Weight no plate pair can make, such as the
     * odd kilogram in 61 kg on a 20 kg bar, is reported as leftover per side.
     */
    fun perSide(total: Double, bar: Double, plates: List<Double>): PlateLoad {
        var remaining = rounded((total - bar) / 2)
        if (remaining <= 0) return PlateLoad(emptyList(), 0.0)
        val loaded = mutableListOf<Double>()
        for (plate in plates.sortedDescending()) {
            while (remaining >= plate) {
                loaded += plate
                remaining = rounded(remaining - plate)
            }
        }
        return PlateLoad(loaded, remaining)
    }

    fun total(bar: Double, side: List<Double>): Double = rounded(bar + 2 * side.sum())

    private fun rounded(value: Double): Double = (value * 1000).roundToLong() / 1000.0
}
