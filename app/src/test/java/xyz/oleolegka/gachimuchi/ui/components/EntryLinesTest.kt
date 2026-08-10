package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Test
import xyz.oleolegka.gachimuchi.domain.ActivityEvent
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.ui.ScreenTest
import xyz.oleolegka.gachimuchi.ui.exerciseRef

/**
 * The one thing on a set's row that says it does not count towards a record: the "Warm-up"
 * badge.
 *
 * Before this the flag was real - domain/Records.kt already leaves a warm-up set out of
 * every record it judges - but invisible: a list read back "60 x 5, 60 x 8, 65 x 5" with
 * nothing on screen to say why the first one won nothing. Owner: "ну давай ему маленькую
 * плашку сделаем".
 */
class EntryLinesTest : ScreenTest() {

    private val bench = exerciseRef(1, "Bench press")

    private fun event(id: Long, warmup: Boolean, weightKg: Double = 60.0, reps: Int = 5): ActivityEvent {
        val form = strengthSetOf(bench, "2026-08-10", reps = reps, weightKg = weightKg, warmup = warmup)
        return ActivityEvent(
            id = id,
            ts = "2026-08-10T09:${10 + id}:00",
            authorId = 1,
            type = form.type,
            opDate = "2026-08-10",
            key = null,
            form = form,
        )
    }

    @Test
    fun `a warm-up set carries the badge, a working set next to it does not`() {
        screen {
            EntryBlock(
                name = "Bench press",
                restSec = null,
                entries = listOf(event(1, warmup = true), event(2, warmup = false)),
                recordOf = emptyMap(),
            )
        }

        // exactly one badge, on the warm-up row and not on the working one beside it
        compose.onAllNodesWithText("Warm-up").assertCountEquals(1)
    }

    @Test
    fun `a workout with no warm-up set shows no badge at all`() {
        screen {
            EntryBlock(
                name = "Bench press",
                restSec = null,
                entries = listOf(event(1, warmup = false), event(2, warmup = false)),
                recordOf = emptyMap(),
            )
        }

        compose.onNodeWithText("Warm-up").assertDoesNotExist()
    }
}
