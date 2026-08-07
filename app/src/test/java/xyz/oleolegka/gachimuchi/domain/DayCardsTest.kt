package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The day as a list of cards: what is on it, in what order, and what each card says.
 *
 * This is the whole of the Today screen and the whole of the calendar's selected day, which
 * is exactly why it lives in the domain — a Compose screen is the one part of this app that
 * nothing can test, so the part worth arguing about is kept out of it.
 */
class DayCardsTest {

    private var nextId = 1L

    private fun row(type: String, payload: String, ts: String, workoutId: Long? = null) =
        JournalEvent(nextId++, ts, 1, 1, type, payload, workoutId)

    private fun started(
        opDate: String,
        at: String = "09:00",
        slotId: Long? = null,
        slotUid: String? = null,
        /** The name snapshot the app takes at the moment "start" is pressed. */
        name: String? = null,
    ) = row(
        TYPE_WORKOUT_STARTED,
        payloadJson.encodeToString(WorkoutStarted(opDate, slotId, slotUid, name)),
        "${opDate}T$at:00",
    )

    /** A start event WRITTEN today for a day already gone — training typed up afterwards. */
    /**
     * "That workout is over."
     *
     * Needed by any fixture whose workout must NOT read as the one in progress: midnight no
     * longer closes one (§13), so a workout left open on a past day is still the open one and
     * would draw a "Continue" card on a day that is over.
     */
    private fun finished(workoutId: Long, opDate: String, at: String = "20:00") =
        row(
            TYPE_WORKOUT_FINISHED,
            payloadJson.encodeToString(WorkoutFinished(workoutId)),
            "${opDate}T$at:00",
            workoutId,
        )

    private fun startedLate(opDate: String, writtenOn: String, at: String = "21:00", slotId: Long? = null) =
        row(TYPE_WORKOUT_STARTED, payloadJson.encodeToString(WorkoutStarted(opDate, slotId)), "${writtenOn}T$at:00")

    private fun set(
        exercise: ExerciseRef,
        opDate: String,
        workoutId: Long? = null,
        at: String = "09:10",
        reps: Int = 5,
        weightKg: Double = 60.0,
        /** The day the row was WRITTEN on — the same as [opDate] unless it was typed later. */
        writtenOn: String = opDate,
    ) = strengthSetOf(exercise, opDate, reps = reps, weightKg = weightKg)
        .let { row(it.type, it.toPayload(), "${writtenOn}T$at:00", workoutId) }

    private fun weighIn(opDate: String, at: String = "07:30", kg: Double = 74.2) =
        bodyweightOf(opDate, kg).let { row(it.type, it.toPayload(), "${opDate}T$at:00") }

    private fun slot(id: Long, name: String, atTime: String?, day: String, uid: String? = null) =
        Slot(
            id = id, name = name, atTime = atTime, repeatRule = REPEAT_NONE, anchorDate = day,
            uid = uid,
        )

    private val bench = ExerciseRef(1, "Bench press", ExerciseForm.STRENGTH)
    private val squat = ExerciseRef(2, "Squat", ExerciseForm.STRENGTH)
    private val fingerboard = ExerciseRef(3, "Fingerboard 20 mm", ExerciseForm.STRENGTH)
    private val stretching = ExerciseRef(4, "Stretching", ExerciseForm.STRENGTH)

    private val today = LocalDate.parse("2026-08-07")
    private val yesterday = today.minusDays(1)
    private val tomorrow = today.plusDays(1)

    /** Noon of a day, which is the reading most of these are taken at. */
    private fun noon(day: LocalDate): LocalDateTime = day.atTime(12, 0)

    private fun cardsOf(
        events: List<JournalEvent>,
        slots: List<Slot> = emptyList(),
        date: LocalDate = today,
        now: LocalDateTime = noon(today),
    ) = dayCards(events, slots, date, today, now)

    // --- an empty day ------------------------------------------------------------------

    @Test
    fun `a day with nothing planned and nothing recorded has no cards at all`() {
        val day = cardsOf(emptyList())
        assertTrue(day.isEmpty)
        assertEquals(emptyList<DayCard>(), day.cards)
        // and it can still be trained: an empty today is the state the app opens in
        assertTrue(day.canRecord)
    }

    @Test
    fun `a day gone by with nothing on it is still recordable, because old training gets typed up`() {
        val day = cardsOf(emptyList(), date = yesterday, now = noon(today))
        assertTrue(day.isEmpty)
        assertTrue("typing up the past is the whole reason a workout carries a date", day.canRecord)
    }

    // --- a plan that nothing has answered ----------------------------------------------

    @Test
    fun `a planned session with no workout against it offers to start`() {
        val slots = listOf(slot(7, "Gym", "18:00", today.toString()))
        val day = cardsOf(emptyList(), slots)

        val card = day.cards.single()
        assertEquals(DayCardKind.PLANNED, card.kind)
        assertEquals("Gym", card.title)
        assertEquals("18:00", card.timeLabel)
        assertEquals("not started yet", card.subtitle)
        assertEquals(DayCardAction.START, card.action)
        assertEquals(7L, card.slotId)
        assertNull(card.workoutId)
    }

    @Test
    fun `a plan whose window has closed says it was missed, and still offers to start`() {
        val slots = listOf(slot(7, "Gym", "08:00", yesterday.toString()))
        // read the next day: the window (three hours) is long gone
        val day = cardsOf(emptyList(), slots, date = yesterday, now = noon(today))

        val card = day.cards.single()
        assertEquals(DayCardKind.PLANNED, card.kind)
        assertEquals("missed - nothing was recorded", card.subtitle)
        // the old rule refused to log against a past slot because logging wrote today's
        // date; a workout carries its own date now, so the offer is honest
        assertEquals(DayCardAction.START, card.action)
    }

    @Test
    fun `a workout started from the plan replaces its card rather than sitting beside it`() {
        val slots = listOf(slot(7, "Gym", "18:00", today.toString()))
        val start = started(today.toString(), at = "18:05", slotId = 7L, name = "Gym")
        val day = cardsOf(listOf(start, set(bench, today.toString(), start.id, at = "18:10")), slots)

        val card = day.cards.single()
        assertEquals(DayCardKind.RUNNING, card.kind)
        // and it wears the name it was started under, which is the point of starting from a plan
        assertEquals("Gym", card.title)
        assertEquals(7L, card.slotId)
    }

    @Test
    fun `renaming the plan does not rename the workouts already started from it`() {
        val start = started(today.toString(), at = "18:05", slotId = 7L, name = "Gym")
        val events = listOf(start, set(bench, today.toString(), start.id, at = "18:10"))

        // the plan is edited afterwards, which the app allows and the journal must not follow:
        // what the session was called on the day is a fact about that day
        val renamed = listOf(slot(7, "Powerlifting", "18:00", today.toString()))

        assertEquals("Gym", cardsOf(events, renamed).cards.single().title)
    }

    @Test
    fun `a workout nobody named is headed by its time rather than by any plan`() {
        // no snapshot: a workout started off-plan, and a workout whose start event predates
        // the snapshot and arrived from a journal this phone never migrated
        val start = started(today.toString(), at = "18:05", slotId = 7L)
        val events = listOf(start, set(bench, today.toString(), start.id, at = "18:10"))
        val slots = listOf(slot(7, "Gym", "07:00", today.toString()))

        val card = cardsOf(events, slots).cards.last()
        assertEquals("18:05 - 18:10", card.title)
        // and the time is not then said twice
        assertEquals("", card.timeLabel)
    }

    /*
     * The two below put the plan at 07:00 and the workout at 18:05 ON PURPOSE. The
     * time-based match (domain/Schedule.kt) closes a slot from an entry recorded near it, and
     * with plan and workout at the same hour it would close this one too — the plan card
     * would then be absent whether the explicit link worked or not, and the test would pass
     * for a reason that has nothing to do with what it is about.
     */

    @Test
    fun `a workout naming its plan by identity alone still replaces that plan's card`() {
        // what a journal written on another phone looks like: the plan link is a uid, and the
        // row number it used to be said with means nothing here
        val uid = "01930000-0000-7000-8000-0000000091a7"
        val slots = listOf(slot(7, "Gym", "07:00", today.toString(), uid = uid))
        val start = started(today.toString(), at = "18:05", slotUid = uid, name = "Gym")
        val day = cardsOf(listOf(start, set(bench, today.toString(), start.id, at = "18:10")), slots)

        val card = day.cards.single()
        assertEquals(DayCardKind.RUNNING, card.kind)
        assertEquals("Gym", card.title)
    }

    @Test
    fun `a workout naming another phone's plan does not swallow the card of this one`() {
        // the same row number, a different identity: falling back to the number here would
        // hide a plan that nothing has answered
        val slots = listOf(
            slot(7, "Gym", "07:00", today.toString(), uid = "01930000-0000-7000-8000-0000000091a7")
        )
        val start = started(
            today.toString(), at = "18:05",
            slotId = 7L, slotUid = "01930000-0000-7000-8000-00000000a7b0",
        )
        val day = cardsOf(listOf(start, set(bench, today.toString(), start.id, at = "18:10")), slots)

        assertEquals(
            listOf(DayCardKind.PLANNED, DayCardKind.RUNNING),
            day.cards.map { it.kind },
        )
        // and the workout wears no name it has no claim to
        assertEquals("18:05 - 18:10", day.cards.last().title)
    }

    @Test
    fun `a plan closed by sets logged without pressing start also stops offering to start`() {
        // the existing time-based match (domain/Schedule.kt), untouched: an entry near the
        // planned time closes the slot, so the plan is not shown as still outstanding
        val slots = listOf(slot(7, "Gym", "08:00", today.toString()))
        val day = cardsOf(listOf(set(bench, today.toString(), at = "08:10")), slots)

        assertEquals(listOf(DayCardKind.SINGLE), day.cards.map { it.kind })
    }

    // --- a day of loose entries only ---------------------------------------------------

    @Test
    fun `entries logged outside a workout are one card PER EXERCISE, not one per set`() {
        val iso = today.toString()
        val day = cardsOf(
            listOf(
                set(fingerboard, iso, at = "19:00"),
                set(fingerboard, iso, at = "19:05"),
                set(fingerboard, iso, at = "19:10"),
                set(fingerboard, iso, at = "19:15"),
                set(fingerboard, iso, at = "19:20"),
                set(stretching, iso, at = "20:00"),
            )
        )

        assertEquals(2, day.cards.size)
        val fingers = day.cards.first()
        assertEquals(DayCardKind.SINGLE, fingers.kind)
        assertEquals("Fingerboard 20 mm", fingers.title)
        // five sets, one card: without this the whole point of the screen is lost the first
        // time somebody logs a real set of sets
        assertEquals("outside a workout - 5 entries", fingers.subtitle)
        assertEquals("19:00 - 19:20", fingers.timeLabel)
        assertEquals(DayCardAction.OPEN, fingers.action)
        assertEquals(fingerboard.id, fingers.exerciseId)

        assertEquals("outside a workout - 1 entry", day.cards[1].subtitle)
        assertEquals("20:00", day.cards[1].timeLabel)
    }

    @Test
    fun `a weigh-in on its own gets a card and nothing to open, since it names no exercise`() {
        val day = cardsOf(listOf(weighIn(today.toString())))

        val card = day.cards.single()
        assertEquals(DayCardKind.SINGLE, card.kind)
        assertEquals("Body weight", card.title)
        assertNull(card.exerciseId)
        assertEquals(DayCardAction.NONE, card.action)
    }

    // --- two workouts on one day -------------------------------------------------------

    @Test
    fun `two workouts on one day are two cards, and only the later one is running`() {
        val iso = today.toString()
        val morning = started(iso, at = "08:00")
        val evening = started(iso, at = "19:00")
        val day = cardsOf(
            listOf(
                morning,
                set(bench, iso, morning.id, at = "08:10"),
                set(bench, iso, morning.id, at = "08:14"),
                set(squat, iso, morning.id, at = "08:30"),
                evening,
                set(fingerboard, iso, evening.id, at = "19:10"),
            )
        )

        assertEquals(listOf("workout:${morning.id}", "workout:${evening.id}"), day.cards.map { it.key })
        val (first, second) = day.cards

        assertEquals(DayCardKind.DONE, first.kind)
        // nobody named it, so it is shown by its time and the label is not repeated
        assertEquals("08:00 - 08:30", first.title)
        assertEquals("", first.timeLabel)
        assertEquals("2 exercises, 3 sets", first.subtitle)
        assertEquals(DayCardAction.OPEN, first.action)
        assertEquals(morning.id, first.workoutId)

        assertEquals(DayCardKind.RUNNING, second.kind)
        assertEquals("in progress - 1 exercise, 1 set", second.subtitle)
        assertEquals(DayCardAction.CONTINUE, second.action)
    }

    @Test
    fun `a workout with nothing in it yet says so instead of counting to zero`() {
        val start = started(today.toString(), at = "18:00")
        val card = cardsOf(listOf(start)).cards.single()

        assertEquals(DayCardKind.RUNNING, card.kind)
        assertEquals("in progress - nothing recorded yet", card.subtitle)
    }

    @Test
    fun `an exercise added to a workout and not yet done still counts towards its card`() {
        val start = started(today.toString(), at = "18:00")
        val added = row(
            TYPE_WORKOUT_EXERCISE_ADDED,
            payloadJson.encodeToString(WorkoutExerciseAdded(start.id, bench.id, 150)),
            "${today}T18:01:00",
            start.id,
        )
        val card = cardsOf(listOf(start, added)).cards.single()

        // the workout HAS the exercise (§13.2: the empty blocks are the feature), so the
        // subtitle says one exercise and no sets rather than pretending the workout is empty
        assertEquals("in progress - 1 exercise, 0 sets", card.subtitle)
    }

    // --- a mixed day -------------------------------------------------------------------

    @Test
    fun `a mixed day interleaves plans, workouts and loose entries by time`() {
        // the day the user described: a gym workout, and stretching and fingerboard done on
        // their own, all in one list
        val iso = yesterday.toString()
        val gym = started(iso, at = "10:00")
        val slots = listOf(slot(9, "Evening hangboard", "20:00", iso))
        val day = cardsOf(
            listOf(
                weighIn(iso, at = "07:30"),
                gym,
                set(bench, iso, gym.id, at = "10:10"),
                set(squat, iso, gym.id, at = "10:40"),
                set(stretching, iso, at = "13:00"),
                set(fingerboard, iso, at = "15:00"),
                set(fingerboard, iso, at = "15:06"),
                // it was closed at the time, which is what makes it a DONE card rather than
                // the one in progress — a workout nobody finishes stays open indefinitely now
                finished(gym.id, iso, at = "11:00"),
            ),
            slots,
            date = yesterday,
            now = noon(today),
        )

        assertEquals(
            listOf("Body weight", "10:00 - 10:40", "Stretching", "Fingerboard 20 mm", "Evening hangboard"),
            day.cards.map { it.title },
        )
        assertEquals(
            listOf(
                DayCardKind.SINGLE,
                DayCardKind.DONE,
                DayCardKind.SINGLE,
                DayCardKind.SINGLE,
                DayCardKind.PLANNED,
            ),
            day.cards.map { it.kind },
        )
        // the plan is last because its window has passed and nothing was recorded near it;
        // it is still on the list, because a plan that quietly disappears is a plan nobody
        // learns anything from
        assertEquals("missed - nothing was recorded", day.cards.last().subtitle)
    }

    @Test
    fun `a card the clock cannot place sorts after every card it can`() {
        /*
         * A workout typed up two weeks late carries the time it was TYPED, which says
         * nothing about when the training happened, so it has no place on the day's clock
         * and goes last. The alternative — printing the typing time — would put a workout
         * done in the morning at the bottom of the evening, plausibly and wrongly.
         */
        val iso = yesterday.toString()
        val late = startedLate(iso, writtenOn = today.toString())
        val day = cardsOf(
            // the loose entry is later on the clock and still comes FIRST, because the other
            // card has no clock position at all
            listOf(
                late,
                set(squat, iso, late.id, at = "21:05", writtenOn = today.toString()),
                set(bench, iso, at = "23:00"),
            ),
            date = yesterday,
            now = noon(today),
        )

        assertEquals(listOf(DayCardKind.SINGLE, DayCardKind.RUNNING), day.cards.map { it.kind })
        // nothing on the backdated workout can be printed as a clock time, so the card falls
        // back to the generic word rather than to the hour it was typed at
        assertEquals("Workout", day.cards[1].title)
        assertEquals("", day.cards[1].timeLabel)
    }

    @Test
    fun `a plan with no time is a card of its own, and sits after the timed ones`() {
        val iso = today.toString()
        val slots = listOf(
            slot(9, "Some time today", null, iso),
            slot(10, "Gym", "18:00", iso),
        )
        val day = cardsOf(emptyList(), slots)

        assertEquals(listOf("Gym", "Some time today"), day.cards.map { it.title })
        assertEquals("", day.cards[1].timeLabel)
        assertTrue(day.cards.all { it.action == DayCardAction.START })
    }

    // --- a day that has not happened ---------------------------------------------------

    @Test
    fun `a day in the future shows its plan and offers no way to record against it`() {
        val slots = listOf(slot(7, "Gym", "18:00", tomorrow.toString()))
        val day = cardsOf(emptyList(), slots, date = tomorrow)

        assertFalse("a set you have not done yet is not a journal entry", day.canRecord)
        val card = day.cards.single()
        assertEquals(DayCardKind.PLANNED, card.kind)
        assertEquals("planned", card.subtitle)
        assertEquals(DayCardAction.NONE, card.action)
    }

    // --- records -----------------------------------------------------------------------

    @Test
    fun `a card says what record was broken on it, and only counts them once there are several`() {
        val iso = today.toString()
        val start = started(iso, at = "18:00")
        val events = listOf(
            // the first weighted set of an exercise is a baseline and breaks nothing
            set(bench, iso, at = "07:00", weightKg = 100.0),
            set(squat, iso, at = "07:10", weightKg = 100.0),
            start,
            set(bench, iso, start.id, at = "18:10", weightKg = 110.0),
        )

        val cards = cardsOf(events)
        val workout = cards.cards.first { it.kind == DayCardKind.RUNNING }
        assertNotNull(workout.recordLine)
        assertTrue(
            "one record is worth spelling out: the number is the news",
            workout.recordLine!!.startsWith("Record: estimated 1RM"),
        )
        // the baseline sets broke nothing, so their cards say nothing about records
        assertTrue(cards.cards.filter { it.kind == DayCardKind.SINGLE }.all { it.recordLine == null })

        val two = cardsOf(events + set(squat, iso, start.id, at = "18:20", weightKg = 120.0))
        assertEquals("2 records", two.cards.first { it.kind == DayCardKind.RUNNING }.recordLine)
    }

    // --- what belongs to which day -----------------------------------------------------

    @Test
    fun `only the asked-for day is on the list`() {
        val events = listOf(
            set(bench, yesterday.toString(), at = "09:00"),
            set(squat, today.toString(), at = "09:00"),
        )
        assertEquals(listOf("Squat"), cardsOf(events).cards.map { it.title })
        assertEquals(listOf("Bench press"), cardsOf(events, date = yesterday).cards.map { it.title })
    }

    @Test
    fun `a backdated workout being typed up right now is the running one, on its own day`() {
        val iso = yesterday.toString()
        val late = startedLate(iso, writtenOn = today.toString())
        val events = listOf(late, set(bench, iso, late.id, at = "09:00"))

        // it is running, because it is the workout the user is filling in at this moment
        assertEquals(
            listOf(DayCardKind.RUNNING),
            cardsOf(events, date = yesterday, now = noon(today)).cards.map { it.kind },
        )
        // and it is not on today, whatever the clock said when it was written
        assertTrue(cardsOf(events).isEmpty)
    }

    @Test
    fun `a cancelled set is gone from the card that would have counted it`() {
        val iso = today.toString()
        val mistake = set(bench, iso, at = "09:00", weightKg = 600.0)
        val events = listOf(
            mistake,
            row(TYPE_SET_CANCEL, payloadJson.encodeToString(SetCancel(mistake.id)), "${iso}T09:01:00"),
            set(bench, iso, at = "09:05"),
        )
        assertEquals("outside a workout - 1 entry", cardsOf(events).cards.single().subtitle)
    }
}
