package io.fitgymlog.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.fitgymlog.app.R
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.WeightUnit
import java.time.Duration
import java.util.Locale

private const val KG_PER_LB = 0.45359237

fun Exercise.name(context: Context): String = nameRes?.let(context::getString) ?: customName ?: englishName

@Composable
fun Exercise.localizedName(): String = nameRes?.let { stringResource(it) } ?: customName ?: englishName

fun toDisplayWeight(kg: Double, unit: WeightUnit): Double = if (unit == WeightUnit.LB) kg / KG_PER_LB else kg

fun fromDisplayWeight(value: Double, unit: WeightUnit): Double = if (unit == WeightUnit.LB) value * KG_PER_LB else value

/** Trims trailing zeros: 60.0 shows as "60", 61.25 as "61.25", pounds to one decimal. */
fun formatWeightNumber(kg: Double, unit: WeightUnit): String {
    val value = toDisplayWeight(kg, unit)
    val decimals = if (unit == WeightUnit.LB) 1 else 2
    return "%.${decimals}f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
}

fun unitLabel(context: Context, unit: WeightUnit): String =
    context.getString(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)

fun formatWeight(context: Context, kg: Double, unit: WeightUnit): String =
    "${formatWeightNumber(kg, unit)} ${unitLabel(context, unit)}"

fun formatClock(duration: Duration): String {
    val seconds = duration.seconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

fun formatRest(seconds: Int): String = if (seconds % 60 == 0) "${seconds / 60}:00" else "%d:%02d".format(seconds / 60, seconds % 60)
