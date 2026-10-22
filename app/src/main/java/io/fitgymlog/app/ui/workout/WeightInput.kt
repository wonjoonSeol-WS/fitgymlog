package io.fitgymlog.app.ui.workout

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.fitgymlog.app.R
import io.fitgymlog.app.model.Equipment
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.Numerals
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.fromDisplayWeight
import io.fitgymlog.app.ui.lift
import io.fitgymlog.app.ui.toDisplayWeight
import io.fitgymlog.app.workout.Plates
import kotlin.math.max
import kotlin.math.roundToLong

private data class PlateLook(val color: Color, val label: Color, val height: Float, val width: Float, val outlined: Boolean = false)

/** Competition plate colours, so a 20 looks like the blue 20 on the gym floor. */
private fun plateLook(plate: Double, unit: WeightUnit): PlateLook = if (unit == WeightUnit.KG) {
    when (plate) {
        25.0 -> PlateLook(Color(0xFFD8343C), Color.White, 1f, 20f)
        20.0 -> PlateLook(Color(0xFF2563EB), Color.White, 1f, 18f)
        15.0 -> PlateLook(Color(0xFFF2C200), Color(0xFF2A2200), 0.88f, 16f)
        10.0 -> PlateLook(Color(0xFF2E9E44), Color.White, 0.76f, 14f)
        5.0 -> PlateLook(Color.White, Color(0xFF15171A), 0.56f, 12f, outlined = true)
        2.5 -> PlateLook(Color(0xFF2B2D31), Color.White, 0.44f, 10f)
        else -> PlateLook(Color(0xFFB8BEC6), Color(0xFF15171A), 0.36f, 9f)
    }
} else {
    when (plate) {
        45.0 -> PlateLook(Color(0xFF2563EB), Color.White, 1f, 20f)
        35.0 -> PlateLook(Color(0xFFF2C200), Color(0xFF2A2200), 0.9f, 18f)
        25.0 -> PlateLook(Color(0xFF2E9E44), Color.White, 0.8f, 16f)
        10.0 -> PlateLook(Color.White, Color(0xFF15171A), 0.6f, 13f, outlined = true)
        5.0 -> PlateLook(Color(0xFF2B2D31), Color.White, 0.48f, 11f)
        else -> PlateLook(Color(0xFFB8BEC6), Color(0xFF15171A), 0.38f, 9f)
    }
}

private fun plateText(plate: Double): String =
    if (plate % 1.0 == 0.0) plate.toLong().toString() else plate.toString().trimEnd('0').trimEnd('.')

private fun roundDisplay(value: Double): Double = (value * 100).roundToLong() / 100.0

/** One side of the bar, loaded heaviest plate first from the collar. Tapping a plate takes that pair off. */
@Composable
private fun BarbellView(side: List<Double>, unit: WeightUnit, onRemove: (Int) -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val barColor = MaterialTheme.colorScheme.outline
    val sleeveColor = MaterialTheme.colorScheme.onSurfaceVariant
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    var hitAreas by remember { mutableStateOf(emptyList<Rect>()) }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .pointerInput(side) {
                detectTapGestures { tap -> hitAreas.indexOfFirst { it.contains(tap) }.takeIf { it >= 0 }?.let(onRemove) }
            },
    ) {
        val centerY = size.height / 2
        val maxPlate = size.height * 0.92f
        val collarX = size.width * 0.22f
        drawRoundRect(barColor, Offset(0f, centerY - 4.dp.toPx()), Size(collarX, 8.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawRoundRect(sleeveColor, Offset(collarX, centerY - 13.dp.toPx()), Size(8.dp.toPx(), 26.dp.toPx()), CornerRadius(2.dp.toPx()))
        drawRoundRect(barColor, Offset(collarX + 8.dp.toPx(), centerY - 6.dp.toPx()), Size(size.width - collarX - 8.dp.toPx(), 12.dp.toPx()), CornerRadius(6.dp.toPx()))

        val areas = mutableListOf<Rect>()
        var x = collarX + 10.dp.toPx()
        side.forEach { plate ->
            val look = plateLook(plate, unit)
            val width = look.width.dp.toPx()
            val height = maxPlate * look.height
            val topLeft = Offset(x, centerY - height / 2)
            drawRoundRect(look.color, topLeft, Size(width, height), CornerRadius(3.dp.toPx()))
            if (look.outlined) drawRoundRect(outline, topLeft, Size(width, height), CornerRadius(3.dp.toPx()), style = Stroke(1.dp.toPx()))
            val label = measurer.measure(plateText(plate), TextStyle(fontFamily = Numerals, fontSize = 11.sp, color = look.label))
            val center = Offset(x + width / 2, centerY)
            rotate(-90f, center) {
                drawText(label, topLeft = Offset(center.x - label.size.width / 2f, center.y - label.size.height / 2f))
            }
            areas += Rect(topLeft, Size(width + 2.dp.toPx(), height))
            x += width + 2.dp.toPx()
        }
        hitAreas = areas
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WeightSheet(
    title: String,
    initialKg: Double?,
    previousKg: Double?,
    equipment: Equipment,
    unit: WeightUnit,
    bar: Double,
    laterSetsMatch: Boolean,
    onBarChange: (Double) -> Unit,
    onSave: (weightKg: Double?, fromHere: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val plates = if (unit == WeightUnit.KG) Plates.KG_PLATES else Plates.LB_PLATES
    var value by remember { mutableStateOf(initialKg?.let { roundDisplay(toDisplayWeight(it, unit)) }) }
    // The plates the user put on, kept as tapped rather than re-derived, so two 20s stay two 20s.
    var side by remember { mutableStateOf(Plates.perSide(value ?: bar, bar, plates).side) }
    var fromHere by remember { mutableStateOf(laterSetsMatch) }
    var typing by remember { mutableStateOf(false) }
    val unitLabel = stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
    val barbell = equipment == Equipment.BARBELL

    fun tick() = haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)

    fun change(next: Double?) {
        value = next?.let { roundDisplay(max(0.0, it)) }
        side = Plates.perSide(value ?: bar, bar, plates).side
        tick()
    }

    fun load(nextSide: List<Double>, onBar: Double = bar) {
        side = nextSide
        value = Plates.total(onBar, nextSide)
        tick()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable { typing = !typing },
            ) {
                Text(value?.let { formatWeightNumber(fromDisplayWeight(it, unit), unit) } ?: "0", style = NumberStyles.hero)
                Text(unitLabel, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            }
            if (typing) {
                var text by remember { mutableStateOf(value?.toString().orEmpty()) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> if (input.matches(Regex("""\d{0,4}([.,]\d{0,3})?"""))) text = input },
                    singleLine = true,
                    label = { Text(stringResource(R.string.type_weight)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        change(text.replace(',', '.').toDoubleOrNull())
                        typing = false
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (barbell) {
                val leftover = roundDisplay(((value ?: bar) - Plates.total(bar, side)) / 2)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BarbellView(side, unit, onRemove = { index -> load(side.filterIndexed { i, _ -> i != index }) })
                    Text(
                        text = when {
                            leftover > 0 -> stringResource(R.string.plates_leftover, plateText(leftover), unitLabel)
                            side.isEmpty() -> stringResource(R.string.plates_empty_bar)
                            else -> stringResource(R.string.plates_per_side, plateText(side.sum()), unitLabel)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.add_plates), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    plates.forEach { plate ->
                        PlateButton(plate, unit) { load((side + plate).sortedDescending()) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.bar), style = MaterialTheme.typography.labelLarge)
                    (if (unit == WeightUnit.KG) Plates.KG_BARS else Plates.LB_BARS).forEach { option ->
                        Pill(text = "${plateText(option)} $unitLabel", selected = option == bar) {
                            onBarChange(option)
                            load(side, onBar = option)
                        }
                    }
                }
            } else {
                val steps = if (unit == WeightUnit.KG) listOf(-5.0, -2.5, -1.0, 1.0, 2.5, 5.0) else listOf(-10.0, -5.0, -2.5, 2.5, 5.0, 10.0)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    steps.forEach { step ->
                        Pill(text = (if (step > 0) "+" else "") + plateText(step).replace("-", "−"), selected = false) {
                            change((value ?: 0.0) + step)
                        }
                    }
                }
            }

            previousKg?.let { previous ->
                val previousText = formatWeightNumber(previous, unit)
                Pill(text = stringResource(R.string.same_as_last_time, "$previousText $unitLabel"), selected = false) {
                    change(roundDisplay(toDisplayWeight(previous, unit)))
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.apply_to_later_sets), modifier = Modifier.weight(1f))
                Switch(checked = fromHere, onCheckedChange = { fromHere = it })
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onSave(null, fromHere) }) { Text(stringResource(R.string.clear)) }
                Button(
                    onClick = { onSave(value?.let { fromDisplayWeight(it, unit) }, fromHere) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(52.dp),
                ) { Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium) }
            }
        }
    }
}

@Composable
private fun PlateButton(plate: Double, unit: WeightUnit, onClick: () -> Unit) {
    val look = plateLook(plate, unit)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp)
            .background(look.color, CircleShape)
            .then(if (look.outlined) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Text(plateText(plate), style = NumberStyles.cell.copy(fontSize = 17.sp), color = look.label)
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.lift.field,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp))
    }
}

/** A pad of large numbers, one tap to set reps (or seconds) without the keyboard. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NumberPadSheet(
    title: String,
    options: List<Int>,
    selected: Int?,
    target: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 5,
                modifier = Modifier.fillMaxWidth(),
            ) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.lift.field,
                                RoundedCornerShape(14.dp),
                            )
                            .then(
                                if (option == target && !isSelected) {
                                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
                                } else {
                                    Modifier
                                },
                            )
                            .clickable {
                                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onPick(option)
                            },
                    ) {
                        Text(
                            option.toString(),
                            style = NumberStyles.stat,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Row {
                TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.clear)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        }
    }
}
