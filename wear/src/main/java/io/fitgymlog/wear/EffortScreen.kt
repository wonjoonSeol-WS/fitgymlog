package io.fitgymlog.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Picker
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.rememberPickerState

/**
 * After finishing on the watch: how hard the whole workout felt, 1 to 10, on the crown. The ✕ beside the dial, or
 * swiping back, skips it; it sits beside rather than below so the dial keeps its height on a 192 dp watch.
 */
@Composable
fun EffortScreen(onRate: (Int) -> Unit, onSkip: () -> Unit) {
    BackHandler(onBack = onSkip)
    val screen = rememberScreen()
    val dial = rememberPickerState(initialNumberOfOptions = 10, initiallySelectedIndex = DEFAULT_EFFORT - 1, shouldRepeatOptions = false)
    // Read through derivedStateOf so the screen redraws when the number changes, not on every frame of a turn.
    val effort by remember { derivedStateOf { dial.selectedOptionIndex + 1 } }
    val skip = stringResource(R.string.effort_skip)
    Box(Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(top = screen.timeTextSpace, bottom = screen.edgeButtonSpace),
        ) {
            Text(stringResource(R.string.effort_question), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(screen.gap),
                modifier = Modifier.weight(1f),
            ) {
                FilledTonalIconButton(onClick = onSkip, modifier = Modifier.size(screen.smallButton).semantics { contentDescription = skip }) {
                    Text("✕", style = MaterialTheme.typography.labelLarge)
                }
                Picker(
                    state = dial,
                    contentDescription = { "$effort" },
                    modifier = Modifier.fillMaxHeight().width(screen.weightDial),
                ) { index -> Text("${index + 1}", style = WatchType.dial, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                // As wide as the ✕, so the number stays in the middle of the screen.
                Spacer(Modifier.size(screen.smallButton))
            }
            Text(stringResource(effortLabel(effort)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        EdgeButton(onClick = { onRate(effort) }, modifier = Modifier.align(Alignment.BottomCenter)) { Text("✓", style = WatchType.step) }
    }
}

/** The same bands as the phone, which are Apple's workout effort bands. */
private fun effortLabel(effort: Int): Int = when (effort) {
    in 1..3 -> R.string.effort_easy
    in 4..6 -> R.string.effort_moderate
    in 7..8 -> R.string.effort_hard
    else -> R.string.effort_all_out
}

private const val DEFAULT_EFFORT = 5
