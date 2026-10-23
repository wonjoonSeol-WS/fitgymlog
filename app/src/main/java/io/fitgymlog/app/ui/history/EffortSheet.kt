package io.fitgymlog.app.ui.history

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.fitgymlog.app.R
import io.fitgymlog.app.ui.NumberStyles
import kotlin.math.roundToInt

/**
 * How hard the whole workout felt, 1 to 10, rather than any one set: a workout of hard sets with long rests can feel
 * easier than it looks. Skip leaves it unrated; [onClear] is offered when there is a rating to take back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffortSheet(initial: Int?, onSave: (Int) -> Unit, onSkip: () -> Unit, onClear: (() -> Unit)? = null) {
    var value by rememberSaveable { mutableFloatStateOf((initial ?: DEFAULT_EFFORT).toFloat()) }
    val effort = value.roundToInt()
    ModalBottomSheet(onDismissRequest = onSkip, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.effort_question), style = MaterialTheme.typography.titleLarge)
            Text("$effort", style = NumberStyles.stat.copy(fontSize = MaterialTheme.typography.displayMedium.fontSize))
            Text(stringResource(effortLabel(effort)), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            val described = stringResource(R.string.effort_value, effort, stringResource(effortLabel(effort)))
            Slider(
                value = value,
                onValueChange = { value = it },
                valueRange = 1f..10f,
                steps = 8,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = described },
            )
            Text(stringResource(R.string.effort_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (onClear != null) TextButton(onClick = onClear) { Text(stringResource(R.string.effort_clear)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onSkip) { Text(stringResource(if (initial == null) R.string.skip else R.string.cancel)) }
                Button(onClick = { onSave(effort) }) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

/** The bands Apple's workout effort uses, so the number means the same in both. */
@StringRes
fun effortLabel(effort: Int): Int = when (effort) {
    in 1..3 -> R.string.effort_easy
    in 4..6 -> R.string.effort_moderate
    in 7..8 -> R.string.effort_hard
    else -> R.string.effort_all_out
}

private const val DEFAULT_EFFORT = 5
