package io.gymlog.app.transfer

import io.gymlog.app.model.SetType
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HevyCsvTest {
    private val header =
        "\"title\",\"start_time\",\"end_time\",\"description\",\"exercise_title\",\"superset_id\",\"exercise_notes\",\"set_index\",\"set_type\",\"weight_kg\",\"reps\",\"distance_km\",\"duration_seconds\",\"rpe\""

    /** Same layout as a real Korean-language export: localized dates and names, a blank weight, no final newline. */
    private val koreanKg = listOf(
        header,
        "\"Strength 1\",\"19 10월 2026, 19:38\",\"19 10월 2026, 22:05\",\"\",\"스쿼트 (바벨)\",,\"\",0,\"normal\",60,8,,,",
        "\"Strength 1\",\"19 10월 2026, 19:38\",\"19 10월 2026, 22:05\",\"\",\"스쿼트 (바벨)\",,\"\",1,\"normal\",60,8,,,",
        "\"Strength 1\",\"19 10월 2026, 19:38\",\"19 10월 2026, 22:05\",\"\",\"오버헤드 프레스 (바벨)\",,\"\",0,\"normal\",30,4,,,",
        "\"Strength 1\",\"19 10월 2026, 19:38\",\"19 10월 2026, 22:05\",\"\",\"오버헤드 프레스 (바벨)\",,\"\",1,\"normal\",,8,,,",
        "\"Strength 1\",\"15 10월 2026, 21:08\",\"15 10월 2026, 22:40\",\"\",\"스쿼트 (바벨)\",,\"\",0,\"warmup\",40,8,,,",
    ).joinToString("\n")

    @Test
    fun `groups rows into workouts and exercises`() {
        val result = HevyCsv.parse(koreanKg)

        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.workouts.size)
        val first = result.workouts[0]
        assertEquals(LocalDateTime.of(2026, 10, 19, 19, 38), first.startedAt)
        assertEquals(LocalDateTime.of(2026, 10, 19, 22, 5), first.endedAt)
        assertEquals(listOf("스쿼트 (바벨)", "오버헤드 프레스 (바벨)"), first.exercises.map { it.name })
        assertEquals(listOf(listOf(8, 8), listOf(4, 8)), first.exercises.map { exercise -> exercise.sets.map { it.reps } })
    }

    @Test
    fun `keeps a blank weight as not recorded instead of zero`() {
        val press = HevyCsv.parse(koreanKg).workouts[0].exercises[1]

        assertEquals(30.0, press.sets[0].weightKg)
        assertNull(press.sets[1].weightKg)
        assertEquals(8, press.sets[1].reps)
    }

    @Test
    fun `reads set types`() {
        assertEquals(SetType.WARMUP, HevyCsv.parse(koreanKg).workouts[1].exercises[0].sets[0].type)
    }

    @Test
    fun `converts pounds when the export uses weight_lbs`() {
        val pounds = """
            title,start_time,end_time,description,exercise_title,superset_id,exercise_notes,set_index,set_type,weight_lbs,reps,distance_miles,duration_seconds,rpe
            Day A,"Oct 19, 2026, 7:38 PM","Oct 19, 2026, 9:05 PM",,Squat (Barbell),,,0,normal,135,5,,,8.5
        """.trimIndent()

        val set = HevyCsv.parse(pounds).workouts.single().exercises.single().sets.single()

        assertEquals(61.235, set.weightKg!!, 0.001)
        assertEquals(8.5, set.rpe)
    }

    @Test
    fun `reports unreadable rows without dropping the rest`() {
        val broken = koreanKg + "\n\"Strength 1\",\"someday\",\"\",\"\",\"스쿼트 (바벨)\",,\"\",0,\"normal\",60,8,,,"

        val result = HevyCsv.parse(broken)

        assertEquals(2, result.workouts.size)
        assertEquals(7, result.errors.single().rowNumber)
    }

    @Test
    fun `an exported file reads back with the same sets`() {
        val workouts = HevyCsv.parse(koreanKg).workouts.map { ExportWorkout(it.title, it.startedAt, it.endedAt, it.notes, it.exercises) }

        val reread = HevyCsv.parse(HevyCsv.write(workouts)).workouts

        assertEquals(workouts.map { it.exercises }, reread.map { it.exercises })
        assertEquals(workouts.map { it.startedAt }, reread.map { it.startedAt })
    }

    @Test
    fun `recognizes its own header`() {
        assertTrue(HevyCsv.looksLike(koreanKg))
        assertEquals(false, StrongCsv.looksLike(koreanKg))
    }
}
