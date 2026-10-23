package io.fitgymlog.app.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.fitgymlog.app.R
import io.fitgymlog.app.coach.ChangeAction
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.restValue

data class ChangeRow(val title: String, val detail: String, val checkable: Boolean, val applied: Boolean)

/** Rows that can still be applied start ticked; [footer] shows when none can. */
@Composable
fun ChangeChecklist(title: String, rows: List<ChangeRow>, footer: String?, onApply: (Set<Int>) -> Unit) {
    var unchecked by rememberSaveable { mutableStateOf(emptySet<Int>()) }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 12.dp))
            rows.forEachIndexed { index, row ->
                val checked = row.checkable && index !in unchecked
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (row.checkable) Modifier.toggleable(value = checked, role = Role.Checkbox) { unchecked = if (it) unchecked - index else unchecked + index } else Modifier)
                        .padding(horizontal = 4.dp),
                ) {
                    when {
                        row.checkable -> Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.minimumInteractiveComponentSize())
                        row.applied -> Icon(
                            Icons.Default.Check,
                            stringResource(R.string.coach_applied),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        )
                        else -> Spacer(Modifier.minimumInteractiveComponentSize())
                    }
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(row.title)
                        if (row.detail.isNotEmpty()) Text(row.detail, style = NumberStyles.cell)
                    }
                }
            }
            val chosen = rows.indices.filter { rows[it].checkable && it !in unchecked }.toSet()
            when {
                rows.any { it.checkable } -> Button(enabled = chosen.isNotEmpty(), onClick = { onApply(chosen) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.coach_apply))
                }
                footer != null -> Text(footer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
    }
}

@Composable
fun changeTitle(action: ChangeAction, name: String): String = when (action) {
    ChangeAction.ADD -> stringResource(R.string.coach_change_add, name)
    ChangeAction.REMOVE -> stringResource(R.string.coach_change_remove, name)
    ChangeAction.ADJUST -> name
}

@Composable
fun planText(sets: Int?, reps: Int?, minutes: Int?, weight: String? = null, restSeconds: Int? = null): String = listOfNotNull(
    sets?.let { pluralStringResource(R.plurals.coach_sets, it, it) },
    weight,
    reps?.let { pluralStringResource(R.plurals.reps, it, it) },
    minutes?.let { stringResource(R.string.minutes_value, it) },
    restSeconds?.let { stringResource(R.string.rest_label, restValue(it)) },
).joinToString(", ")
