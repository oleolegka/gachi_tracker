package xyz.oleolegka.gachimuchi.data.seed

import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.db.SEED_AUTHOR_ID
import xyz.oleolegka.gachimuchi.domain.Bodyweight
import xyz.oleolegka.gachimuchi.domain.Cardio
import xyz.oleolegka.gachimuchi.domain.Duration
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.REPEAT_DAILY
import xyz.oleolegka.gachimuchi.domain.REPEAT_NONE
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.Tick
import xyz.oleolegka.gachimuchi.domain.normPhrase
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Demo history — a (shortened) port of `tools/seed_mock.py`.
 *
 * THIS IS NOT A PRODUCT FIXTURE. The data is made up; the numbers were picked to look
 * plausible (progression at a sane pace, skipped sessions), but it is still synthetic.
 *
 * ── It is never written unasked, and that is a reversal ─────────────────────────
 * This used to run on first launch, on the argument that empty screens show nothing worth
 * looking at. What it actually did was hand a new user ninety days of somebody else's
 * training as if it were theirs, in the one app whose entire value is that its journal is
 * true. Worse, it was one-way: nothing outside this file could take it back out again, so
 * the demo and the real records grew together in the same journal for good.
 *
 * Now it is written only when the user asks for it (Settings), and everything it creates is
 * marked so the same screen can take it all back out — events by [SEED_AUTHOR_ID], the
 * catalog, the aliases and the slots by [xyz.oleolegka.gachimuchi.data.db.COLUMN_SEEDED].
 * See data/seed/DemoCleanup.kt for what removal spares and why.
 *
 * Progression is monotonic in the exercise's SESSION counter, as in the original: the peak
 * lands on the last session, so records are detected on recent dates.
 *
 * §12-A: the hangboard is split into SEPARATE exercises ("Hangs 20 mm · 7:3" and
 * "Hangs 15 mm · 7:3" are two catalog rows with independent histories), and the tracked
 * variable is added weight.
 */
object DemoSeed {

    private data class HoldSpec(
        val name: String, val edgeMm: Double, val workSec: Double, val restSec: Double,
        val startKg: Double, val gainKg: Double, val reps: Int, val sets: Int, val alias: String,
    )

    private val holdCatalog = listOf(
        HoldSpec("Hangs 20 mm · 7:3", 20.0, 7.0, 3.0, 6.0, 1.2, 5, 3, "hang20"),
        HoldSpec("Hangs 15 mm · 7:3", 15.0, 7.0, 3.0, 2.0, 0.8, 5, 3, "hang15"),
        HoldSpec("Hangs 20 mm · 10:50", 20.0, 10.0, 50.0, 10.0, 1.6, 5, 2, "hang20long"),
    )

    private data class StrengthSpec(
        val name: String, val alias: String, val start: Double, val gain: Double,
        val sets: List<Pair<Double, Int>>,
    )

    private val strengthCatalog = listOf(
        StrengthSpec("Bench press", "bench", 60.0, 0.6, listOf(-10.0 to 8, -5.0 to 6, 0.0 to 5, 0.0 to 5)),
        StrengthSpec("Squat", "squats", 80.0, 0.8, listOf(-20.0 to 8, -10.0 to 5, 0.0 to 5, 0.0 to 3)),
        StrengthSpec("Deadlift", "deads", 100.0, 1.0, listOf(-30.0 to 5, -15.0 to 5, 0.0 to 3)),
    )

    /** weekday -> (session code, probability that it actually happened). Sunday is empty. */
    private val weekTemplate: Map<DayOfWeek, List<Pair<String, Double>>> = mapOf(
        DayOfWeek.MONDAY to listOf("gym_a" to 0.8, "stretch" to 0.5),
        DayOfWeek.TUESDAY to listOf("run" to 0.7),
        DayOfWeek.WEDNESDAY to listOf("finger" to 0.8, "hangs" to 0.55, "stretch" to 0.45),
        DayOfWeek.THURSDAY to listOf("gym_b" to 0.8, "stretch" to 0.4),
        DayOfWeek.FRIDAY to listOf("elliptic" to 0.6, "hangs" to 0.5),
        DayOfWeek.SATURDAY to listOf("boulder" to 0.6, "run" to 0.35, "stretch" to 0.35),
        DayOfWeek.SUNDAY to emptyList(),
    )

    /** The catalog rows that are neither strength nor holds: name, alias, form. */
    private val plainCatalog = listOf(
        Triple("Running", "run", ExerciseForm.CARDIO),
        Triple("Elliptical", "ellipse", ExerciseForm.CARDIO),
        Triple("Emil hangs", "emil", ExerciseForm.DURATION),
        Triple("Stretching", "stretch", ExerciseForm.TICK),
        Triple("Bouldering gym", "boulder", ExerciseForm.TICK),
        Triple("Body weight", "bodyweight", ExerciseForm.BODYWEIGHT),
    )

    /**
     * Every exercise name this seed has ever created, NORMALIZED.
     *
     * Used to recognise demo data written by a build that had no marker column — see
     * data/seed/DemoCleanup.kt. Names are compared the way the catalog itself compares them
     * ([normPhrase]), because that is what decided whether a row was created in the first
     * place.
     */
    val demoExerciseKeys: Set<String> =
        (strengthCatalog.map { it.name } + holdCatalog.map { it.name } + plainCatalog.map { it.first })
            .mapNotNull { normPhrase(it) }
            .toSet()

    /** One demo slot as it is written: the session name, the time of day and the repeat rule. */
    data class SlotShape(val name: String, val atTime: String?, val repeatRule: String)

    /**
     * The plan the seed writes. Only the three fields that do not depend on WHEN the seed
     * ran are listed: the anchor date is derived from "today", so it cannot be matched
     * against later. Same purpose as [demoExerciseKeys] — recognising unmarked demo data.
     */
    val demoSlotShapes: List<SlotShape> = listOf(
        SlotShape("Gym", "18:00", REPEAT_WEEKLY),
        SlotShape("Hangboard", "20:00", REPEAT_WEEKLY),
        SlotShape("Stretching", "21:30", REPEAT_DAILY),
        SlotShape("Bouldering gym", "12:00", REPEAT_NONE),
        SlotShape("Running", "08:00", REPEAT_NONE),
    )

    /** Rounds a weight to a realistic plate step. */
    private fun r2(x: Double, step: Double = 2.5): Double =
        (x / step).roundToInt() * step

    data class Summary(val events: Int, val exercises: Int, val slots: Int, val activeDays: Int)

    /**
     * Writes the demo history. Deterministic for a given [rngSeed] and [today].
     *
     * Whatever an earlier demo left behind is removed first, so writing it twice produces one
     * demo rather than two. Only MARKED rows are taken: unlike the settings button, this
     * removal happens without anybody being shown what it is about to do, so it does not act
     * on the by-name guess that recognises demo data from before the mark existed (see
     * [removeDemoData]). The consequence is stated plainly: on a phone that was seeded by an
     * older build, adding the demo again leaves the old one in place beside the new one, and
     * "Remove demo data" is what clears both.
     */
    suspend fun seed(
        repo: ActivityRepository,
        today: LocalDate = LocalDate.now(),
        days: Int = 90,
        rngSeed: Int = 20260806,
    ): Summary {
        val rng = Random(rngSeed)
        val start = today.minusDays((days - 1).toLong())
        removeDemoData(repo, matchByName = false)

        val exerciseIds = HashMap<String, Long>()
        for (s in strengthCatalog) {
            val id = repo.ensureExercise(s.name, ExerciseForm.STRENGTH, seeded = true)
            exerciseIds[s.name] = id
            repo.learnAlias(s.name, id, seeded = true); repo.learnAlias(s.alias, id, seeded = true)
        }
        for (h in holdCatalog) {
            val id = repo.ensureExercise(
                h.name, ExerciseForm.HOLD, h.edgeMm, h.workSec, h.restSec, seeded = true,
            )
            exerciseIds[h.name] = id
            repo.learnAlias(h.name, id, seeded = true); repo.learnAlias(h.alias, id, seeded = true)
        }
        for ((name, alias, form) in plainCatalog) {
            val id = repo.ensureExercise(name, form, seeded = true)
            exerciseIds[name] = id
            repo.learnAlias(name, id, seeded = true); repo.learnAlias(alias, id, seeded = true)
        }

        val sessions = HashMap<String, Int>() // exercise -> how many sessions it already had
        var written = 0
        val activeDays = HashSet<String>()

        suspend fun strengthDay(day: LocalDate, names: List<String>) {
            for (name in names) {
                val plan = strengthCatalog.first { it.name == name }
                val n = sessions.getOrDefault(name, 0)
                val top = r2(plan.start + plan.gain * n)
                sessions[name] = n + 1
                for ((offset, reps) in plan.sets) {
                    val w = r2(max(20.0, top + offset))
                    // noise is applied to the REPS of warm-up sets only: the weight stays
                    // monotonic, otherwise the "record" would bounce around and the chart
                    // would stop being readable
                    val r = if (offset == 0.0) reps else max(3, reps + listOf(-1, 0, 0, 1).random(rng))
                    repo.record(
                        StrengthSet(
                            exercise = name, reps = r, weightKg = w,
                            exerciseId = exerciseIds[name], restAfterSec = listOf(120.0, 150.0, 180.0).random(rng),
                            opDate = day.toString(),
                        ),
                        authorId = SEED_AUTHOR_ID,
                    )
                    written++
                }
                activeDays.add(day.toString())
            }
        }

        var day = start
        while (!day.isAfter(today)) {
            val iso = day.toString()
            val picked = weekTemplate.getValue(day.dayOfWeek).filter { rng.nextDouble() < it.second }.map { it.first }
            for (code in picked) {
                when (code) {
                    "gym_a" -> strengthDay(day, listOf("Bench press", "Squat"))
                    "gym_b" -> strengthDay(day, listOf("Deadlift", "Bench press"))
                    "finger" -> {
                        // two exercises out of three: the "long" protocol comes up less often —
                        // the §12-A siblings have different history density, just like in real life
                        val names = if (rng.nextDouble() < 0.4) {
                            listOf("Hangs 20 mm · 7:3", "Hangs 20 mm · 10:50")
                        } else {
                            listOf("Hangs 20 mm · 7:3", "Hangs 15 mm · 7:3")
                        }
                        for (name in names) {
                            val spec = holdCatalog.first { it.name == name }
                            val n = sessions.getOrDefault(name, 0)
                            val weight = ((spec.startKg + spec.gainKg * n) * 10).roundToInt() / 10.0
                            sessions[name] = n + 1
                            repeat(spec.sets) {
                                repo.record(
                                    HoldSet(
                                        activity = name, reps = spec.reps,
                                        workSec = spec.workSec, restSec = spec.restSec,
                                        edgeMm = spec.edgeMm, addedKg = weight, ownWeight = true,
                                        exerciseId = exerciseIds[name], restAfterSec = 180.0,
                                        opDate = iso,
                                    ),
                                    authorId = SEED_AUTHOR_ID,
                                )
                                written++
                            }
                            activeDays.add(iso)
                        }
                    }
                    "run" -> {
                        val i = sessions.getOrDefault("Running", 0)
                        sessions["Running"] = i + 1
                        val dist = min(6500.0, 4000.0 + 180.0 * i) + listOf(-200, 0, 0, 300).random(rng)
                        val pace = max(290.0, 340.0 - 3.0 * i) + listOf(-4, 0, 0, 5).random(rng)
                        repo.record(
                            Cardio(
                                activity = "Running", distanceM = dist.roundToInt().toDouble(),
                                durationSec = (dist / 1000 * pace).toInt(),
                                paceSecPerKm = pace.roundToInt().toDouble(),
                                exerciseId = exerciseIds["Running"], opDate = iso,
                            ),
                            authorId = SEED_AUTHOR_ID,
                        )
                        written++; activeDays.add(iso)
                    }
                    "elliptic" -> {
                        val i = sessions.getOrDefault("Elliptical", 0)
                        sessions["Elliptical"] = i + 1
                        val dur = (min(2700.0, 1800.0 + 30.0 * i) + listOf(-120, 0, 180).random(rng)).toInt()
                        repo.record(
                            Cardio(
                                activity = "Elliptical", distanceM = (dur * 3.2).roundToInt().toDouble(),
                                durationSec = dur, exerciseId = exerciseIds["Elliptical"], opDate = iso,
                            ),
                            authorId = SEED_AUTHOR_ID,
                        )
                        written++; activeDays.add(iso)
                    }
                    "hangs" -> {
                        val i = sessions.getOrDefault("Emil hangs", 0)
                        sessions["Emil hangs"] = i + 1
                        val sec = (min(900.0, 480.0 + 15.0 * i) + listOf(-60, 0, 60).random(rng)).toInt()
                        repo.record(
                            Duration(
                                activity = "Emil hangs", durationSec = sec,
                                exerciseId = exerciseIds["Emil hangs"], opDate = iso,
                            ),
                            authorId = SEED_AUTHOR_ID,
                        )
                        written++; activeDays.add(iso)
                    }
                    "stretch" -> {
                        repo.record(
                            Tick(activity = "Stretching", exerciseId = exerciseIds["Stretching"], opDate = iso),
                            authorId = SEED_AUTHOR_ID,
                        )
                        written++; activeDays.add(iso)
                    }
                    "boulder" -> {
                        repo.record(
                            Tick(activity = "Bouldering gym", exerciseId = exerciseIds["Bouldering gym"], opDate = iso),
                            authorId = SEED_AUTHOR_ID,
                        )
                        written++; activeDays.add(iso)
                    }
                }
            }
            day = day.plusDays(1)
        }

        // body weight: a noisy series trending down from 76.5 to about 72.5 over the
        // period, measured every fourth day
        var idx = 0
        day = start
        while (!day.isAfter(today)) {
            if (idx % 4 == 0) {
                val base = 76.5 - 4.0 * (idx.toDouble() / max(1, days - 1))
                val w = ((base + rng.nextDouble(-0.4, 0.4)) * 10).roundToInt() / 10.0
                repo.record(Bodyweight(weightKg = w, opDate = day.toString()), authorId = SEED_AUTHOR_ID)
                written++
            }
            idx++; day = day.plusDays(1)
        }

        /*
         * Plan slots: "Gym" on Mon and Thu, "Hangboard" on Wed, daily stretching (anchored
         * two weeks back, so that the early history keeps some "unplanned" days), plus
         * one-off ones.
         *
         * These are written unconditionally now, because they are MARKED. The old code
         * skipped them whenever any slot existed at all — the only defence it had against
         * burying the user's plan under a demo one it could never remove again. With a mark
         * on the row the demo plan is separable, so it can be written next to a real one and
         * taken away without it.
         */
        val slotIds = mutableListOf<Long>()
        suspend fun plan(shape: SlotShape, anchor: LocalDate) {
            slotIds += repo.createSlot(shape.name, shape.atTime, shape.repeatRule, anchor.toString(), seeded = true)
        }
        plan(demoSlotShapes[0], firstWeekdayOnOrAfter(start, DayOfWeek.MONDAY))
        plan(demoSlotShapes[0], firstWeekdayOnOrAfter(start, DayOfWeek.THURSDAY))
        plan(demoSlotShapes[1], firstWeekdayOnOrAfter(start, DayOfWeek.WEDNESDAY))
        plan(demoSlotShapes[2], today.minusDays(14))
        plan(demoSlotShapes[3], today.plusDays(2))
        // a one-off "Running" on a past day WITHOUT activity — a guaranteed "missed" day
        val missDay = (1..30).map { today.minusDays(it.toLong()) }.firstOrNull { it.toString() !in activeDays }
        if (missDay != null) plan(demoSlotShapes[4], missDay)

        return Summary(written, exerciseIds.size, slotIds.size, activeDays.size)
    }

    private fun firstWeekdayOnOrAfter(start: LocalDate, weekday: DayOfWeek): LocalDate {
        val shift = (weekday.value - start.dayOfWeek.value + 7) % 7
        return start.plusDays(shift.toLong())
    }
}
