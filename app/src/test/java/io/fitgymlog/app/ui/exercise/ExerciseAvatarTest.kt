package io.fitgymlog.app.ui.exercise

import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseAvatarTest {
    @Test
    fun `uses the whole first character of an emoji name`() {
        assertEquals("💪", avatarInitial("💪 Curl"))
    }

    @Test
    fun `uses the first letter in upper case`() {
        assertEquals("S", avatarInitial("squat"))
        assertEquals("벤", avatarInitial("벤치 프레스"))
        assertEquals("", avatarInitial(""))
    }
}
