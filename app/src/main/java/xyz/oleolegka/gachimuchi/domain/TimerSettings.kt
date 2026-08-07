package xyz.oleolegka.gachimuchi.domain

import kotlin.math.roundToInt

/**
 * Timer preferences, and the one piece of intelligence behind the rest between sets:
 * working out how long that rest should actually be.
 *
 * Kept in the domain, away from SharedPreferences, so that "which duration does this
 * exercise get" is a pure function of the journal and the settings, and is tested as one.
 */

/** How long a step may be. The upper bound only exists to keep a typo out of the alarm. */
const val MIN_STEP_SEC = 1
const val MAX_STEP_SEC = 60 * 60

/** The one-tap durations offered next to the rest timer. */
val REST_PRESETS_SEC = listOf(60, 90, 120, 180)

/** The step of the +/- buttons, everywhere: notification, run screen, log screen. */
const val NUDGE_SEC = 30

/**
 * Everything the timer lets you change.
 *
 * [speak] defaults to OFF, and that is not timidity: the phone this is built for runs
 * GrapheneOS with no Google services, and the speech engine most Android phones have is
 * part of Play. Announcements are therefore an opt-in that is only offered once an engine
 * has actually been found (see timer/Speaker.kt) — never a default that silently does
 * nothing.
 */
data class TimerSettings(
    /** Fallback length of a rest between sets when the journal has nothing to say. */
    val defaultRestSec: Int = 120,
    /** Start the rest for an exercise by itself the moment a set of it is recorded. */
    val autoStartRest: Boolean = true,
    /** Prefer the rest actually taken last time over [defaultRestSec]. */
    val adaptRestToExercise: Boolean = true,
    /** Lead-in before the first work step of a generated program. */
    val prepareSec: Int = PREPARE_DEFAULT_SEC,
    /** Tones at step boundaries, on the alarm stream. */
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    /** A short tick over the last few seconds of a step. */
    val countdownTicks: Boolean = true,
    /** Spoken step names, when an engine exists. */
    val speak: Boolean = false,
    /** Default number of sets when a program is generated from an exercise. */
    val defaultSets: Int = 4,
)

/**
 * How long the last rest between sets of this exercise actually was, in seconds, or null
 * when the journal cannot say.
 *
 * ── Which day ───────────────────────────────────────────────────────────────────
 * The most recent day on which the exercise has at least two entries. A day with a single
 * set contains no pause to measure, and reaching further back for one would answer with a
 * number from a different training block.
 *
 * ── Which number from that day ──────────────────────────────────────────────────
 * The MEDIAN of the pauses, not the mean and not the last one. A session almost always
 * contains one gap that is not a rest — a queue for the rack, a conversation, a phone
 * call — and both the mean and "the last one" are wrecked by a single such gap. The
 * median ignores it.
 *
 * Gaps above [MAX_REST_SEC] are dropped before that (they are breaks in the workout, not
 * rests, and the session feed already refuses to call them rests), and an explicit
 * `rest_after_sec` on a record is believed over the derived gap — the Telegram bot writes
 * that field and it is the better fact when present.
 *
 * The result is rounded to a whole [ROUND_TO_SEC] so the offer reads as a decision
 * ("2:30") rather than as a measurement ("2:27").
 */
fun lastRestSec(events: List<JournalEvent>, exerciseId: Long): Int? {
    val entries = readActivities(events)
        .filter { it.form.exerciseLink()?.matches(ExerciseLink.ofId(exerciseId)) == true }
    if (entries.size < 2) return null

    val byDay = entries.groupBy { it.opDate }
    val day = byDay.keys.filter { (byDay[it]?.size ?: 0) >= 2 }.maxOrNull() ?: return null
    val ofDay = byDay.getValue(day)

    val gaps = ArrayList<Double>()
    for (i in 1 until ofDay.size) {
        val previous = ofDay[i - 1]
        val gap = explicitRestAfter(previous.form)
            ?: secondsBetween(previous.ts, ofDay[i].ts)
            ?: continue
        if (gap > 0 && gap <= MAX_REST_SEC) gaps += gap
    }
    if (gaps.isEmpty()) return null

    val sorted = gaps.sorted()
    val middle = sorted.size / 2
    val median = if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    return roundRest(median)
}

/** Offers are rounded to this, so a suggestion reads as a decision rather than a measurement. */
const val ROUND_TO_SEC = 15

fun roundRest(sec: Double): Int =
    ((sec / ROUND_TO_SEC).roundToInt() * ROUND_TO_SEC).coerceIn(ROUND_TO_SEC, MAX_STEP_SEC)

/**
 * The length of the rest to start for [exerciseId]: what was actually done last time when
 * the setting allows it and the journal knows, otherwise the configured default.
 */
fun resolveRestSec(settings: TimerSettings, events: List<JournalEvent>, exerciseId: Long?): Int {
    if (!settings.adaptRestToExercise || exerciseId == null) return settings.defaultRestSec
    return lastRestSec(events, exerciseId) ?: settings.defaultRestSec
}

/**
 * Where the offered duration came from, spelled out for the screen. Silently using a
 * number derived from history would leave no way to tell a good suggestion from a bad one.
 */
fun restSourceLabel(settings: TimerSettings, events: List<JournalEvent>, exerciseId: Long?): String =
    if (settings.adaptRestToExercise && exerciseId != null && lastRestSec(events, exerciseId) != null) {
        "from last time"
    } else {
        "default"
    }

/**
 * Whether recording this form should start a rest floor for its exercise.
 *
 * Only the two set-based forms. A body weight reading, a check-in and a finished cardio
 * session are not followed by another set, and a countdown after them is noise that trains
 * you to ignore the timer.
 */
fun startsRest(form: ActivityForm): Boolean = form is StrengthSet || form is HoldSet

/** "2:05", the shape a countdown is read in. Hours appear only if there are any. */
fun formatClock(totalSec: Int): String {
    val safe = totalSec.coerceAtLeast(0)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    val s = safe % 60
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}

/** Rounds a millisecond remainder UP to whole seconds, the way a countdown must display it. */
fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt().coerceAtLeast(0)
