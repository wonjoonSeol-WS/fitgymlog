package io.fitgymlog.app.coach

import org.junit.Assert.assertEquals
import org.junit.Test

class CoachSettingsTest {
    @Test
    fun `a task without its own model uses the main one`() {
        val settings = CoachSettings(model = "main", taskModels = mapOf(CoachTask.CHAT to "chatty", CoachTask.REVIEW to " "))

        assertEquals(listOf("main", "main", "chatty"), CoachTask.entries.map(settings::modelFor))
    }
}
