package io.fitgymlog.app.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.pow

data class ChartPoint(val date: LocalDate, val value: Double)

/** Points are spaced by date, or evenly when they share one; with a [trend], they become dots under its line. [description] is read out instead. */
@Composable
fun LineChart(points: List<ChartPoint>, label: (Double) -> String, description: String, minStep: Double = 0.0, trend: List<ChartPoint> = emptyList()) {
    if (points.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale) }
    val ticks = remember(points, trend, minStep) { (points + trend).let { all -> axisTicks(all.minOf { it.value }, all.maxOf { it.value }, minStep) } }
    val top = ticks.first()
    val bottom = ticks.last()
    val sorted = points.sortedBy { it.date }
    val first = sorted.first().date.toEpochDay()
    val days = sorted.last().date.toEpochDay() - first

    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
        val tickLabels = ticks.map { measurer.measure(label(it), textStyle) }
        val left = tickLabels.maxOf { it.size.width } + 8.dp.toPx()
        val firstDate = measurer.measure(points.minBy { it.date }.date.format(dateFormat), textStyle)
        val lastDate = measurer.measure(points.maxBy { it.date }.date.format(dateFormat), textStyle)
        val plotBottom = size.height - firstDate.size.height - 6.dp.toPx()
        val plotTop = tickLabels.first().size.height / 2f
        val plotWidth = size.width - left - 8.dp.toPx()
        fun y(value: Double) = (plotBottom - (value - bottom) / (top - bottom) * (plotBottom - plotTop)).toFloat()
        fun x(date: LocalDate) = left + (date.toEpochDay() - first).toFloat() / days * plotWidth
        fun x(index: Int) = when {
            sorted.size == 1 -> left + plotWidth / 2
            days == 0L -> left + index.toFloat() / (sorted.size - 1) * plotWidth
            else -> x(sorted[index].date)
        }

        ticks.zip(tickLabels).forEach { (value, text) ->
            drawLine(grid, Offset(left, y(value)), Offset(size.width, y(value)), strokeWidth = 1.dp.toPx())
            drawText(text, topLeft = Offset(0f, y(value) - text.size.height / 2f))
        }
        drawText(firstDate, topLeft = Offset(left, size.height - firstDate.size.height))
        if (points.size > 1) drawText(lastDate, topLeft = Offset(size.width - lastDate.size.width, size.height - lastDate.size.height))

        val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (trend.size > 1 && days > 0) {
            sorted.forEachIndexed { index, point -> drawCircle(line.copy(alpha = 0.35f), radius = 2.5.dp.toPx(), center = Offset(x(index), y(point.value))) }
            val path = Path()
            trend.sortedBy { it.date }.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(x(point.date), y(point.value)) else path.lineTo(x(point.date), y(point.value))
            }
            drawPath(path, line, style = stroke)
            drawCircle(line, radius = 5.dp.toPx(), center = Offset(x(trend.maxBy { it.date }.date), y(trend.maxBy { it.date }.value)))
        } else {
            val path = Path()
            sorted.forEachIndexed { index, point ->
                if (index == 0) path.moveTo(x(index), y(point.value)) else path.lineTo(x(index), y(point.value))
            }
            drawPath(path, line, style = stroke)
            sorted.forEachIndexed { index, point -> drawCircle(line, radius = 3.dp.toPx(), center = Offset(x(index), y(point.value))) }
            drawCircle(line, radius = 5.dp.toPx(), center = Offset(x(sorted.lastIndex), y(sorted.last().value)))
        }
    }
}

/** One bar per point, evenly spaced, from zero; [description] is read out instead of the drawing. */
@Composable
fun BarChart(bars: List<ChartPoint>, label: (Double) -> String, description: String, minStep: Double = 0.0) {
    if (bars.isEmpty()) return
    val measurer = rememberTextMeasurer()
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val textStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale) }
    val ticks = remember(bars, minStep) { axisTicks(0.0, bars.maxOf { it.value }, minStep) }

    Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = description }) {
        val tickLabels = ticks.map { measurer.measure(label(it), textStyle) }
        val left = tickLabels.maxOf { it.size.width } + 8.dp.toPx()
        val firstDate = measurer.measure(bars.first().date.format(dateFormat), textStyle)
        val lastDate = measurer.measure(bars.last().date.format(dateFormat), textStyle)
        val plotBottom = size.height - firstDate.size.height - 6.dp.toPx()
        val plotTop = tickLabels.first().size.height / 2f
        fun y(value: Double) = (plotBottom - (value - ticks.last()) / (ticks.first() - ticks.last()) * (plotBottom - plotTop)).toFloat()

        ticks.zip(tickLabels).forEach { (value, text) ->
            drawLine(grid, Offset(left, y(value)), Offset(size.width, y(value)), strokeWidth = 1.dp.toPx())
            drawText(text, topLeft = Offset(0f, y(value) - text.size.height / 2f))
        }
        val slot = (size.width - left) / bars.size
        val width = slot * 0.6f
        bars.forEachIndexed { index, bar ->
            if (bar.value <= 0) return@forEachIndexed
            val x = left + slot * index + (slot - width) / 2
            drawRoundRect(color, Offset(x, y(bar.value)), Size(width, plotBottom - y(bar.value)), CornerRadius(3.dp.toPx()))
        }
        drawText(firstDate, topLeft = Offset(left, size.height - firstDate.size.height))
        drawText(lastDate, topLeft = Offset(size.width - lastDate.size.width, size.height - lastDate.size.height))
    }
}

/**
 * Three round values, top first, an equal step apart, spanning [low] to [high] and never below zero for values that never are;
 * whole steps from a [minStep] of one up, with 3 and 4 so a small count still fills the chart.
 */
internal fun axisTicks(low: Double, high: Double, minStep: Double = 0.0): List<Double> {
    val (from, to) = if (high > low) low to high else (low - maxOf(1.0, minStep)) to (high + maxOf(1.0, minStep))
    val floorAt = if (low >= 0) 0.0 else Double.NEGATIVE_INFINITY
    fun bottomFor(step: Double) = maxOf(floor(from / step) * step, floorAt)
    val step = (if (minStep >= 1) WHOLE_STEPS else STEPS).firstOrNull { it >= minStep && bottomFor(it) + 2 * it >= to }
        ?: return listOf(to, (from + to) / 2, from)
    val bottom = bottomFor(step)
    return listOf(bottom + 2 * step, bottom + step, bottom)
}

private val STEPS = (-2..12).flatMap { power -> listOf(1.0, 2.0, 2.5, 5.0).map { it * 10.0.pow(power) } }

private val WHOLE_STEPS = (0..12).flatMap { power -> listOf(1.0, 2.0, 3.0, 4.0, 5.0).map { it * 10.0.pow(power) } }
