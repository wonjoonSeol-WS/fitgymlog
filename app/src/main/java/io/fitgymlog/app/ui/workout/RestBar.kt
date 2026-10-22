package io.fitgymlog.app.ui.workout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.fitgymlog.app.R
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.formatClock
import io.fitgymlog.app.ui.formatRest
import io.fitgymlog.app.ui.lift
import io.fitgymlog.app.ui.rememberNow
import io.fitgymlog.app.workout.ActiveWorkout
import java.time.Duration

@Composable
internal fun RestBar(workout: ActiveWorkout, onAdjust: (Long) -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val clock = workout.restClock(rememberNow())
    val remaining = clock?.left ?: Duration.ZERO
    val totalSeconds = clock?.length?.seconds?.toInt() ?: 0
    val colors = MaterialTheme.lift
    Surface(
        color = colors.restBar,
        contentColor = colors.onRestBar,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 8.dp)) {
                Text(formatClock(remaining), style = NumberStyles.timer, modifier = Modifier.width(112.dp))
                Text(
                    stringResource(R.string.rest_label, formatRest(totalSeconds)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onRestBar.copy(alpha = 0.7f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f).hideWhenClipped(),
                )
                RestButton("−15") { onAdjust(-15) }
                RestButton("+15") { onAdjust(15) }
                IconButton(onClick = onSkip) { Icon(Icons.Default.Close, stringResource(R.string.skip)) }
            }
            val progress = if (totalSeconds > 0) (remaining.seconds.toFloat() / totalSeconds).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                color = MaterialTheme.colorScheme.primary,
                trackColor = colors.onRestBar.copy(alpha = 0.15f),
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
    }
}

/** Drops the text entirely when the row is too narrow for it, rather than wrapping it a letter per line. */
private fun Modifier.hideWhenClipped(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(maxWidth = Constraints.Infinity))
    val width = constraints.maxWidth
    layout(width, placeable.height) { if (placeable.width <= width) placeable.place(0, 0) }
}

@Composable
private fun RestButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.lift.onRestBar)) {
        Text(text, style = NumberStyles.cell.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize))
    }
}
