package io.fitgymlog.app.ui

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.fitgymlog.app.R
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import java.time.Duration
import java.util.Locale

private const val KG_PER_LB = 0.45359237

fun formatDuration(resources: Resources, seconds: Int, kind: ExerciseKind): String = when {
    kind != ExerciseKind.CARDIO -> resources.getString(R.string.duration_seconds, seconds)
    seconds % 60 == 0 -> resources.getString(R.string.duration_minutes, seconds / 60)
    else -> resources.getString(R.string.duration_minutes_seconds, seconds / 60, seconds % 60)
}

fun Exercise.name(context: Context): String = nameRes?.let(context::getString) ?: customName ?: englishName

@Composable
fun Exercise.localizedName(): String = nameRes?.let { stringResource(it) } ?: customName ?: englishName

@Composable
fun setTypeName(type: SetType): String = stringResource(
    when (type) {
        SetType.NORMAL -> R.string.set_type_normal
        SetType.WARMUP -> R.string.set_type_warmup
        SetType.DROP -> R.string.set_type_drop
        SetType.FAILURE -> R.string.set_type_failure
    },
)

fun toDisplayWeight(kg: Double, unit: WeightUnit): Double = if (unit == WeightUnit.LB) kg / KG_PER_LB else kg

fun fromDisplayWeight(value: Double, unit: WeightUnit): Double = if (unit == WeightUnit.LB) value * KG_PER_LB else value

/** Trims trailing zeros: 60.0 shows as "60", 61.25 as "61.25", pounds to one decimal. */
fun formatWeightNumber(kg: Double, unit: WeightUnit): String {
    val value = toDisplayWeight(kg, unit)
    val decimals = if (unit == WeightUnit.LB) 1 else 2
    return "%.${decimals}f".format(Locale.ROOT, value).trimEnd('0').trimEnd('.')
}

private val WeightUnit.labelRes: Int get() = if (this == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb

fun unitLabel(context: Context, unit: WeightUnit): String = context.getString(unit.labelRes)

@Composable
fun unitLabel(unit: WeightUnit): String = stringResource(unit.labelRes)

fun formatWeight(context: Context, kg: Double, unit: WeightUnit): String =
    "${formatWeightNumber(kg, unit)} ${unitLabel(context, unit)}"

fun formatClock(duration: Duration): String {
    val seconds = duration.seconds.coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, rest) else "%d:%02d".format(Locale.ROOT, minutes, rest)
}

fun formatRest(seconds: Int): String = "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
