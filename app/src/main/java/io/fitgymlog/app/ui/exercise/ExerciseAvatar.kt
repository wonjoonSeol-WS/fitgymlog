package io.fitgymlog.app.ui.exercise

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.localizedName

/**
 * The exercise's own form video still, cropped to a circle; failing that the article's image,
 * and the first letter of the name when there is no link at all.
 */
@Composable
fun ExerciseAvatar(exercise: Exercise, modifier: Modifier = Modifier, size: Dp = 42.dp) {
    val image by rememberRemoteImage(exerciseImageUrl(exercise))
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        val loaded = image
        if (loaded != null) {
            Image(loaded, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                exercise.localizedName().take(1).uppercase(),
                style = NumberStyles.stat.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/** YouTube's 16:9 still has no letterbox bars, so its centre crops cleanly into a circle. */
@Composable
private fun exerciseImageUrl(exercise: Exercise): String? {
    exercise.allLinks.firstNotNullOfOrNull { PreviewParser.youtubeIdOf(it) }?.let { return "https://img.youtube.com/vi/$it/mqdefault.jpg" }
    val page = exercise.allLinks.firstOrNull() ?: return null
    val preview by rememberLinkPreview(page)
    return preview?.imageUrl
}
