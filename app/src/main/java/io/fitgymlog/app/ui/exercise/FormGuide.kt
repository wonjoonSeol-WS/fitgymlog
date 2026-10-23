package io.fitgymlog.app.ui.exercise

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.links.PreviewParser
import io.fitgymlog.app.links.WebLinks
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.ui.lift
import io.fitgymlog.app.ui.localizedName
import kotlinx.coroutines.launch

fun openLink(context: Context, url: String) {
    val link = url.trim()
    val opened = WebLinks.isWeb(link) &&
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    if (!opened) Toast.makeText(context, R.string.link_cannot_open, Toast.LENGTH_SHORT).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormGuideSheet(exercise: Exercise, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(exercise.localizedName(), style = MaterialTheme.typography.titleLarge)
            FormGuide(exercise)
        }
    }
}

/** Videos first as large thumbnails, then articles as link cards, then a way to add your own. */
@Composable
fun FormGuide(exercise: Exercise) {
    val context = LocalContext.current
    val app = context.container
    var adding by rememberSaveable { mutableStateOf(false) }
    var playing by rememberSaveable { mutableStateOf<String?>(null) }
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val (videos, pages) = remember(exercise.links, exercise.userLinks) {
        WebLinks.distinct(exercise.allLinks).partition { PreviewParser.youtubeIdOf(it) != null }
    }
    val remove = { url: String -> app.scope.launch { app.exercises.removeLink(exercise.id, url) } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (exercise.allLinks.isEmpty()) {
            Text(stringResource(R.string.no_form_links), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        videos.forEach { url ->
            key(url) {
                VideoCard(url, removable = url in exercise.userLinks, onRemove = { remove(url) }) {
                    if (settings.playVideosInApp) playing = url else openLink(context, url)
                }
            }
        }
        pages.forEach { url ->
            key(url) {
                PageCard(url, removable = url in exercise.userLinks, onRemove = { remove(url) }) { openLink(context, url) }
            }
        }
        TextButton(onClick = { adding = true }) {
            Icon(Icons.Default.Add, null)
            Text(stringResource(R.string.add_link), modifier = Modifier.padding(start = 6.dp))
        }
    }

    playing?.let { url ->
        PreviewParser.youtubeIdOf(url)?.let { id -> VideoPlayerDialog(url, id, onDismiss = { playing = null }) }
    }

    if (adding) {
        AddLinkDialog(
            existing = exercise.allLinks,
            onAdd = { url ->
                app.scope.launch { app.exercises.addLink(exercise.id, url) }
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun VideoCard(url: String, removable: Boolean, onRemove: () -> Unit, onOpen: () -> Unit) {
    val id = PreviewParser.youtubeIdOf(url) ?: return
    val thumbnail by rememberYoutubeStill(id)
    val preview by rememberLinkPreview(url)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.lift.field)
                .clickable(onClick = onOpen),
        ) {
            thumbnail?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape),
            ) {
                Icon(Icons.Default.PlayArrow, stringResource(R.string.play_video), tint = Color.White, modifier = Modifier.size(36.dp))
            }
            if (removable) RemoveButton(onRemove, Modifier.align(Alignment.TopEnd))
        }
        Text(
            preview?.title ?: stringResource(R.string.link_video),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PageCard(url: String, removable: Boolean, onRemove: () -> Unit, onOpen: () -> Unit) {
    val preview by rememberLinkPreview(url)
    val image by rememberRemoteImage(preview?.imageUrl)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.lift.field)
            .clickable(onClick = onOpen)
            .padding(10.dp),
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f)) {
            Text(
                preview?.title ?: url.toUri().host.orEmpty(),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(url.toUri().host.orEmpty().removePrefix("www."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (removable) RemoveButton(onRemove)
    }
}

@Composable
private fun RemoveButton(onRemove: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onRemove, modifier = modifier) {
        Icon(Icons.Default.Close, stringResource(R.string.remove_link), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AddLinkDialog(existing: List<String>, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val link = WebLinks.normalize(text)
    val error = when {
        text.isBlank() -> null
        link == null -> R.string.link_invalid
        WebLinks.isDuplicate(link, existing) -> R.string.link_duplicate
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_link_help), style = MaterialTheme.typography.bodyMedium)
                LinkField(text, { text = it }, error, placeholder = { Text("https://") })
            }
        },
        confirmButton = {
            TextButton(enabled = link != null && error == null, onClick = { link?.let(onAdd) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun LinkField(
    value: String,
    onValueChange: (String) -> Unit,
    @StringRes error: Int?,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        isError = error != null,
        supportingText = error?.let { { Text(stringResource(it)) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
    )
}
