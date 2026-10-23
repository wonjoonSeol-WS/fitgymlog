package io.fitgymlog.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Three rising bars; the core icon set has no chart. */
internal val StatsIcon: ImageVector = ImageVector.Builder("Stats", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        listOf(Triple(4f, 13f, 8f), Triple(10f, 8f, 14f), Triple(16f, 4f, 20f)).forEach { (left, top, right) ->
            moveTo(left, top)
            lineTo(right, top)
            lineTo(right, 20f)
            lineTo(left, 20f)
            close()
        }
    }
}.build()

/** An outlined speech bubble; the core icon set has none. */
internal val ChatIcon: ImageVector = ImageVector.Builder("Chat", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
        moveTo(6f, 3f)
        lineTo(18f, 3f)
        arcTo(3f, 3f, 0f, false, true, 21f, 6f)
        lineTo(21f, 14f)
        arcTo(3f, 3f, 0f, false, true, 18f, 17f)
        lineTo(11f, 17f)
        lineTo(7f, 21f)
        lineTo(7f, 17f)
        lineTo(6f, 17f)
        arcTo(3f, 3f, 0f, false, true, 3f, 14f)
        lineTo(3f, 6f)
        arcTo(3f, 3f, 0f, false, true, 6f, 3f)
        close()
        moveTo(6f, 5f)
        arcTo(1f, 1f, 0f, false, false, 5f, 6f)
        lineTo(5f, 14f)
        arcTo(1f, 1f, 0f, false, false, 6f, 15f)
        lineTo(18f, 15f)
        arcTo(1f, 1f, 0f, false, false, 19f, 14f)
        lineTo(19f, 6f)
        arcTo(1f, 1f, 0f, false, false, 18f, 5f)
        close()
    }
}.build()
