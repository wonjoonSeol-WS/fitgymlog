package io.fitgymlog.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExerciseMatcherTest {
    private val matcher = ExerciseMatcher(
        mapOf(
            "pull_up_weighted" to listOf("Pull Up (Weighted)", "풀 업 (중량)"),
            "pull_up" to listOf("Pull Up", "풀 업"),
        ),
    )

    @Test
    fun `ignores case, spacing and punctuation in any language`() {
        assertEquals("pull_up_weighted", matcher.match("pull-up (weighted)"))
        assertEquals("pull_up_weighted", matcher.match("풀업 (중량)"))
        assertEquals("pull_up", matcher.match("PULL UP"))
    }

    @Test
    fun `returns null for unknown names`() {
        assertNull(matcher.match("Zercher Squat"))
    }
}
