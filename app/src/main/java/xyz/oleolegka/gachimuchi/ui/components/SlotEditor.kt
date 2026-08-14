package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.MAX_REST_INPUT_SEC
import xyz.oleolegka.gachimuchi.domain.MIN_STEP_SEC
import xyz.oleolegka.gachimuchi.domain.PlannedExercise
import xyz.oleolegka.gachimuchi.domain.REPEAT_DAILY
import xyz.oleolegka.gachimuchi.domain.REPEAT_NONE
import xyz.oleolegka.gachimuchi.domain.REPEAT_RULES
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.Slot
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.SlotProblem
import xyz.oleolegka.gachimuchi.domain.asPlanned
import xyz.oleolegka.gachimuchi.domain.deletionWarning
import xyz.oleolegka.gachimuchi.domain.formatDurationSec
import xyz.oleolegka.gachimuchi.domain.formatTime
import xyz.oleolegka.gachimuchi.domain.formatTimeDigits
import xyz.oleolegka.gachimuchi.domain.isBackdated
import xyz.oleolegka.gachimuchi.domain.lastWorkoutNamed
import xyz.oleolegka.gachimuchi.domain.newSlotDraft
import xyz.oleolegka.gachimuchi.domain.nextOccurrence
import xyz.oleolegka.gachimuchi.domain.parseDurationText
import xyz.oleolegka.gachimuchi.domain.parseMinuteOfDay
import xyz.oleolegka.gachimuchi.domain.parseSlotTime
import xyz.oleolegka.gachimuchi.domain.pastWorkoutNames
import xyz.oleolegka.gachimuchi.domain.problem
import xyz.oleolegka.gachimuchi.domain.problemText
import xyz.oleolegka.gachimuchi.domain.repeatLabel
import xyz.oleolegka.gachimuchi.domain.toDraft
import xyz.oleolegka.gachimuchi.domain.toSlot
import xyz.oleolegka.gachimuchi.domain.withExerciseAdded
import xyz.oleolegka.gachimuchi.domain.withExerciseMoved
import xyz.oleolegka.gachimuchi.domain.withExerciseRemoved
import xyz.oleolegka.gachimuchi.domain.withExerciseRest
import xyz.oleolegka.gachimuchi.ui.UiState
import xyz.oleolegka.gachimuchi.ui.fmtWeekdayDay
import xyz.oleolegka.gachimuchi.ui.screens.ExercisePickerSheet
import xyz.oleolegka.gachimuchi.ui.screens.NewExercise
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

/**
 * Planning a session: the editor behind the calendar's "Plan a session" button and behind
 * the pencil on a planned row.
 *
 * ── A dialog, not a screen ──────────────────────────────────────────────────────
 * There are four fields and the calendar behind them is the context ("this day"), which a
 * full screen would cover up. The program editor is a screen because a program is a tree;
 * a slot is a line.
 *
 * ── Defaults, so the common case is two taps ────────────────────────────────────
 * It opens as a one-off on the day that was selected, with no time — the cheapest thing a
 * plan can be. A name is the only thing that must be typed, and it does not have to be typed
 * either: the field carries a dropdown of names already used, so the second "Gym" is a tap.
 *
 * ── The name is a way to fill the session in, not just a label ──────────────────
 * That dropdown is the same control Today's "Start a workout" has ([NameDialog]) and it is here
 * for the same reason: a name a WORKOUT has carried before resolves into a composition, so
 * picking "Push day" plans what Push day actually was last time ([lastWorkoutNamed],
 * [asPlanned]). Planning is where that is worth most and was the one screen that could not ask
 * for it — reported by the owner, 2026-08-14. See `pickName` below for the two rules it keeps:
 * nothing already picked is overwritten, and a name typed by hand fills nothing.
 *
 * ── The date field is where a weekly plan gets its weekday ──────────────────────
 * The model has no "repeat on Tue and Thu": the anchor date carries the weekday and the
 * rule only says how often (domain/Schedule.kt). That is the one non-obvious thing here,
 * so the dialog spells the consequence out in words under the field ("Repeats every
 * Thursday") instead of leaving it to be discovered in the grid afterwards.
 *
 * ── The time is typed without a colon ───────────────────────────────────────────
 * A phone's number keypad has no colon key, which makes a plain "HH:MM" field impossible
 * to fill in. So there are three ways in and none of them needs one: the quick chips (how
 * a round hour is really chosen), the Material clock dialog, and the field itself, which
 * takes digits only and puts the colon in as they arrive — "1700" becomes "17:00" while
 * being typed (`formatTimeDigits`).
 *
 * A field that rewrites itself has to carry its own CARET, which is why the time is the one
 * field here held as a `TextFieldValue`. The colon is inserted in front of the caret, and a
 * caret left at the offset the keyboard reported would then sit before the digit it was
 * typed after: "1", "7", "0" reads "17:0" with the caret at offset 3, and the next digit
 * lands before the zero — 17:50 for someone who typed 17:05. So the caret is recomputed
 * from the number of DIGITS in front of it, which the colon does not change
 * (`caretAfterDigits`). Digit by digit is how a phone is really typed on.
 *
 * Half a time is not a time: "17:0" on screen means the last digit is still coming, so
 * `parseSlotTime` refuses it, the field turns red and Save stays disabled. The alternative
 * — reading it as 17:00 — would store a time the user did not type whenever they meant
 * 17:05, and would do it silently.
 *
 * ── The exercises are an extra, and the layout has to say so ────────────────────
 * A slot with nothing under it is a complete plan (domain/Schedule.kt), and by far the most
 * common one — "gym on Thursday" is the whole thought most of the time. So the composition
 * lives behind a COLLAPSED LINE that opens on a tap, and the line reads "none planned"
 * rather than sitting there as an empty field. An empty required-looking control is a
 * standing reproach: it makes the cheapest useful plan feel half-finished and invites the
 * user to fill something in before they know what they will do.
 *
 * It opens by itself when a slot already has exercises, because then there is something to
 * see and hiding it would mean a tap to find out whether anything is in there at all.
 *
 * ── Plans start from today, never before ────────────────────────────────────────
 * The date field can be stepped to any day, but Save shuts the moment it lands before
 * [today] — the same gate the time field already uses for a half-typed value, now guarding
 * against two reported bugs at once: a plan added straight onto a day already gone (which
 * can overwrite a MISSED verdict `planVsFact` had already settled on it) and a repeating
 * slot anchored in the past making every past occurrence of it read as planned (§12-B's
 * `occursOn` has always started from the anchor — it was the anchor that had no floor). See
 * [xyz.oleolegka.gachimuchi.domain.isBackdated] for why the check lives here and not in
 * [xyz.oleolegka.gachimuchi.domain.toSlot] itself.
 */
@Composable
fun SlotEditorDialog(
    initial: Slot?,
    day: LocalDate,
    /**
     * Names this PLAN already uses, most recent first. They go under the past workout names in
     * the one dropdown on the name field — see `offeredNames`, and the header for why the two
     * lists are ordered the way they are rather than merged blind.
     */
    suggestions: List<String>,
    today: LocalDate,
    /** The catalog and the journal behind it: what the exercise picker searches through. */
    state: UiState,
    /**
     * Writes a new catalog row and hands back its id. Null hides the picker's create button,
     * which is only right for a caller that genuinely cannot write — the calendar can.
     */
    onCreateExercise: ((new: NewExercise, then: (Long) -> Unit) -> Unit)? = null,
    onSave: (SlotDraft) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalGachiColors.current
    var draft by remember(initial, day) {
        mutableStateOf(initial?.toDraft() ?: newSlotDraft(day))
    }
    // transient like the dialog itself: a clock left open across a rotation is a surprise
    var clockOpen by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    // open on what is already there, shut on what is not — see the header
    var exercisesOpen by remember(initial) { mutableStateOf(initial?.exercises?.isNotEmpty() == true) }
    // the field checks first (name, time, rule, a readable date), and only once those are
    // clean does "is this day already gone" get asked — see isBackdated's own KDoc for why
    // that one is kept apart rather than folded into problem() itself
    //
    // The anchor the slot ALREADY has is handed over with it: an old repeating session is
    // anchored in the past by definition, and comparing its untouched anchor with today
    // would leave the editor unable to save a plan the user is not moving anywhere.
    val problem = draft.problem()
        ?: if (draft.isBackdated(today, was = initial?.anchorDate)) SlotProblem.DATE_IN_PAST else null
    val anchor = remember(draft.anchorDate) {
        runCatching { LocalDate.parse(draft.anchorDate) }.getOrDefault(day)
    }
    val parsedTime = parseSlotTime(draft.timeText)
    // digits that are not a time yet: saving would drop or invent the minutes
    val timeBroken = draft.timeText.isNotBlank() && parsedTime == null
    // the time field keeps its own caret (see the header); everything that writes the time
    // from outside the field — the chips, the clock, Clear — goes through `setTime`
    var timeField by remember(initial, day) {
        mutableStateOf(TextFieldValue(draft.timeText, TextRange(draft.timeText.length)))
    }
    fun setTime(text: String) {
        draft = draft.copy(timeText = text)
        timeField = TextFieldValue(text, TextRange(text.length))
    }

    /*
     * The names on offer, and the reason this dialog now has a dropdown instead of a strip of
     * chips — reported by the owner, 2026-08-14: "когда добавляешь из календаря, там просто нет
     * опции дропдауна при написании имени".
     *
     * Today's "Start a workout" has offered past workout names for a while ([NameDialog]), and
     * the point of it is not the saved typing: a name that a workout has carried before RESOLVES
     * INTO A COMPOSITION ([lastWorkoutNamed] + [asPlanned]), so "Push day" fills the session with
     * what Push day was last time. Planning is the place that wants that most and was the one
     * place that could not ask for it.
     *
     * PAST WORKOUTS COME FIRST, then names this plan already uses. The order is the difference
     * between the two: a workout name pulls a composition in, a plan-only name is a shortcut into
     * the field and nothing more. Both are in one list because one field takes one control, and
     * because a name is frequently both.
     */
    val offeredNames = remember(state.events, suggestions) {
        (pastWorkoutNames(state.events) + suggestions).distinct()
    }
    var namesOpen by remember { mutableStateOf(false) }
    /** What the last pick did to the composition, said under the field rather than left to guess. */
    var pickNote by remember(initial, day) { mutableStateOf<String?>(null) }

    /*
     * NOTHING ALREADY PICKED IS EVER OVERWRITTEN. Filling an empty plan is a shortcut; replacing
     * a composition somebody has just built by hand, because they then went back and touched the
     * name field, is losing their work — and the plan editor has no undo to lose it into.
     *
     * Typing the same name by hand does NOT fill: this fires on a deliberate pick from the list.
     * That is a real difference from Today, where the name is resolved at the moment the workout
     * begins and how it was typed makes no difference — here the composition becomes part of the
     * plan being edited, visible on screen, so it happens when it is asked for.
     */
    fun pickName(name: String) {
        draft = draft.copy(name = name)
        val past = lastWorkoutNamed(state.events, name)?.let(::asPlanned).orEmpty()
        pickNote = when {
            past.isEmpty() -> null
            draft.exercises.isNotEmpty() ->
                "The last \"$name\" had ${past.size} exercises - the ones already picked here were left alone."
            else -> {
                draft = draft.copy(exercises = past)
                // opened, because a list that filled itself behind a collapsed line is a change
                // nobody was shown
                exercisesOpen = true
                "Filled from the last \"$name\": ${past.size} exercises."
            }
        }
    }

    if (clockOpen) {
        TimePickerSheet(
            initial = parsedTime,
            onPick = {
                setTime(it)
                clockOpen = false
            },
            onDismiss = { clockOpen = false },
        )
    }

    if (picking) {
        ExercisePickerSheet(
            state = state,
            today = today,
            onPick = { id -> draft = draft.withExerciseAdded(id) },
            /*
             * Planning can create, and once could not. The argument for refusing was that the
             * identity questions (form, protocol) belong to the moment of the first set — which
             * sounds right and is wrong in the hand: planning Tuesday's hangs on a protocol you
             * have never tried is exactly what a plan is for, and the picker offered no way out
             * of the dead end. Reported from the phone, 2026-08-08.
             */
            onCreate = onCreateExercise?.let { create ->
                { new ->
                    create(new) { id ->
                        draft = draft.withExerciseAdded(id)
                    }
                }
            },
            onDismiss = { picking = false },
        )
    }

    val body = rememberScrollState()
    var menuOpen by remember { mutableStateOf(false) }
    val dialogColor = MaterialTheme.colorScheme.surfaceContainerHigh
    Dialog(
        onDismissRequest = onDismiss,
        // the platform default is a percentage of the screen; this dialog is 312 wide on
        // every phone, which is 360 less the 24 of margin either side
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = Spacing.Cards)
                .fillMaxWidth()
                .widthIn(max = DIALOG_WIDTH)
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.9f).dp),
            shape = MaterialTheme.shapes.large,
            color = dialogColor,
        ) {
            Column(Modifier.fillMaxWidth()) {
                /*
                 * The header holds the title and the ONE destructive action of this dialog.
                 * Deleting the session used to be a text button at the bottom of the scrolling
                 * body — that is, directly above "Save": two words of the same weight, one
                 * storing the plan and the other wiping the whole series (SYSTEM.md, rule 3).
                 */
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            start = Spacing.Cards,
                            end = Spacing.Line,
                            top = Spacing.Cards,
                            bottom = Spacing.Block,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
                ) {
                    Text(
                        if (initial == null) "Plan a session" else "Edit this session",
                        fontSize = TextSize.Figure,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (initial != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = "More for this session",
                                    tint = colors.inkSecondary,
                                )
                            }
                            ActionMenu(
                                expanded = menuOpen,
                                onDismiss = { menuOpen = false },
                                title = initial.name,
                                actions = listOf(
                                    ItemAction("Delete this session", destructive = true) {
                                        menuOpen = false
                                        onDelete()
                                    },
                                ),
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.grid)

                Box(Modifier.weight(1f, fill = false)) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(body)
                            .padding(horizontal = Spacing.Cards, vertical = Spacing.Block),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Block),
                    ) {
                        /*
                         * The same control the "Start a workout" dialog carries on Today: the
                         * field, and a dropdown of names on the end of it. It replaced a strip of
                         * chips headed "Already in the plan" — two controls for one answer, and
                         * the chips could only ever offer the plan's own names.
                         */
                        Box {
                            OutlinedTextField(
                                value = draft.name,
                                onValueChange = { draft = draft.copy(name = it) },
                                label = { Text("Session name") },
                                placeholder = { Text("Gym") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                trailingIcon = if (offeredNames.isEmpty()) {
                                    null
                                } else {
                                    {
                                        IconButton(onClick = { namesOpen = true }) {
                                            Icon(
                                                Icons.Filled.ArrowDropDown,
                                                contentDescription = "Plan like a past session",
                                                tint = colors.inkSecondary,
                                            )
                                        }
                                    }
                                },
                            )
                            DropdownMenu(
                                expanded = namesOpen,
                                onDismissRequest = { namesOpen = false },
                            ) {
                                offeredNames.forEach { name ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            namesOpen = false
                                            pickName(name)
                                        },
                                    )
                                }
                            }
                        }

                        pickNote?.let { note ->
                            Text(
                                note,
                                fontSize = TextSize.Caption,
                                color = colors.inkSecondary,
                            )
                        }

                        OutlinedTextField(
                            value = timeField,
                            // the colon is inserted for the user: it is not on the number keypad,
                            // and the caret is moved along with it
                            onValueChange = { typed ->
                                val formatted = formatTimeDigits(typed.text)
                                val digitsTyped = typed.text
                                    .take(typed.selection.end)
                                    .count { it.isDigit() }
                                timeField = TextFieldValue(
                                    text = formatted,
                                    selection = TextRange(caretAfterDigits(formatted, digitsTyped)),
                                )
                                draft = draft.copy(timeText = formatted)
                            },
                            label = { Text("Time (optional)") },
                            placeholder = { Text("18:00") },
                            singleLine = true,
                            isError = timeBroken,
                            // inside the field, so the field does not change width the moment
                            // a first digit is typed — it used to be a "Clear" button beside it
                            trailingIcon = if (draft.timeText.isNotBlank()) {
                                {
                                    IconButton(onClick = { setTime("") }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Clear the time",
                                            tint = colors.inkMuted,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done,
                            ),
                        )

                        QuickTimes(
                            selected = parsedTime,
                            onPick = { setTime(it) },
                            onClock = { clockOpen = true },
                        )

                        SegmentControl(
                            options = REPEAT_RULES,
                            selected = draft.repeatRule.takeIf { it in REPEAT_RULES } ?: REPEAT_NONE,
                            label = { ruleLabel(it) },
                            onSelect = { draft = draft.copy(repeatRule = it) },
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                            DayField(
                                label = if (draft.repeatRule == REPEAT_NONE) "On" else "Starts on",
                                day = anchor,
                                onChange = { draft = draft.copy(anchorDate = it.toString()) },
                            )
                            /*
                             * ONE line where there were two greys saying the same thing:
                             * "Repeats every Thursday" and, under it, "Next: Thu 13 Aug at
                             * 18:00" — same size, same colour, one fact (SYSTEM.md, rule 5).
                             * Both values are still here, joined by the app's own separator.
                             */
                            Text(
                                buildString {
                                    append(repeatLabel(draft.repeatRule, draft.anchorDate))
                                    draft.toSlot(initial?.id ?: 0L)?.let { candidate ->
                                        val next = nextOccurrence(candidate, today)
                                        append(" · ")
                                        if (next == null) {
                                            append("that day is already gone")
                                        } else {
                                            append("next ${fmtWeekdayDay(next)}")
                                            candidate.atTime?.let { append(", $it") }
                                        }
                                    }
                                },
                                fontSize = TextSize.Meta,
                                color = colors.inkSecondary,
                            )
                        }

                        PlannedExercisesSection(
                            exercises = draft.exercises,
                            open = exercisesOpen,
                            onToggle = { exercisesOpen = !exercisesOpen },
                            nameOf = { state.exerciseById(it)?.name },
                            onAdd = { picking = true },
                            onRemove = { draft = draft.withExerciseRemoved(it) },
                            onMove = { index, delta -> draft = draft.withExerciseMoved(index, delta) },
                            onRest = { index, sec -> draft = draft.withExerciseRest(index, sec) },
                        )
                    }
                    /*
                     * The sign that there IS more below. The body used to be a bare scrolling
                     * Column: on a phone the composition simply stopped at the edge, and
                     * nothing said whether that was the end of it (SYSTEM.md, rule 2).
                     */
                    if (body.canScrollForward) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(Spacing.Cards)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, dialogColor)
                                    )
                                )
                        )
                    }
                }
                HorizontalDivider(color = colors.grid)

                // why Save is shut, pinned where Save is. It used to be inkMuted 13 sp — the
                // palest thing in the dialog, the colour of the hints — and it sat in the
                // body, which means it could be scrolled off the screen entirely
                if (problem != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.recessed)
                            .padding(horizontal = Spacing.Cards, vertical = Spacing.Inset),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = colors.critical,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            problemText(problem),
                            fontSize = TextSize.Meta,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.Cards, vertical = Spacing.Block),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Line, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("Cancel") }
                    Button(
                        enabled = problem == null,
                        onClick = { onSave(draft) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (initial == null) "Add to the plan" else "Save")
                    }
                }
            }
        }
    }
}

/** The width of the editor on every phone: 360 less the 24 of margin on each side. */
private val DIALOG_WIDTH = 312.dp

/**
 * The six times a session is actually given, as a grid rather than a strip.
 *
 * All six are known in advance, and a strip could not hold them: 6 x 62 + 5 x 8 is 412 dp
 * against the 264 a 360 dp phone has inside this dialog, so a third of the values sat past
 * the edge with nothing to say they were there. Three columns of (264 - 2 x 8) / 3 = 82 dp
 * show the lot at once.
 */
@Composable
private fun QuickTimes(selected: String?, onPick: (String) -> Unit, onClock: () -> Unit) {
    val colors = LocalGachiColors.current
    val entries = QUICK_TIMES.map { it to { onPick(it) } } + ("Clock" to onClock)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Line)) {
        entries.chunked(3).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
            ) {
                row.forEach { (label, click) ->
                    val on = label == selected
                    OutlinedButton(
                        onClick = click,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = Spacing.Tight),
                        border = BorderStroke(1.dp, if (on) colors.accent else colors.border),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (on) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                colors.recessed
                            },
                            contentColor = if (on) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                colors.inkSecondary
                            },
                        ),
                    ) {
                        Text(
                            label,
                            fontSize = TextSize.Meta,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * "Yes, delete it" — with the consequence written out, not summarised.
 *
 * The wording comes from the domain (`deletionWarning`), because what deletion does is a
 * property of the MODEL, not of this dialog: occurrences are computed from the row, so a
 * series cannot be half-deleted and a past occurrence cannot be kept.
 */
@Composable
fun DeleteSlotDialog(slot: Slot, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = LocalGachiColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${slot.name}\"?") },
        text = {
            Text(
                deletionWarning(slot),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.inkSecondary,
            )
        },
        /*
         * The one place in this app where the destructive choice is the heavier button, and
         * that is deliberate: rule 7 is about facts on a card, not about a confirmation.
         * Anyone who has reached this dialog has already said what they want, and the two
         * choices used to be two text buttons of identical weight — "Delete" and "Keep it"
         * told apart by colour alone.
         */
        confirmButton = {
            Button(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.critical,
                    contentColor = Color.White,
                ),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("Keep it")
            }
        },
    )
}

/**
 * What the session is meant to consist of — a plan for the workout, not a record of one.
 *
 * ── Collapsed by default, and worded as an option ───────────────────────────────
 * The header is the whole control when nothing is planned: one line saying so, which opens
 * on a tap. It deliberately does NOT look like an empty field, because it is not one — the
 * slot saves perfectly well without it, and the user said as much ("exercises are needed,
 * but not required; sometimes I will not be bothered"). A control that looks unfilled reads
 * as an instruction, and the instruction here would be wrong.
 *
 * ── Order is the plan, so order is editable ─────────────────────────────────────
 * Up and down rather than drag-and-drop: the list is three or four rows inside a scrolling
 * dialog, and a long-press-drag inside a scroll container inside a dialog fights the two
 * gestures either side of it. The arrows are unambiguous and stay hittable one-handed.
 *
 * ── The rest is per exercise and optional ───────────────────────────────────────
 * "Usual" is the default and it is NOT the absence of an answer to a required question: it
 * means "whatever this exercise normally gets", which is a live value the catalog keeps and
 * this plan should not freeze a copy of. A number is only stored when the user says this
 * session is different — the heavy day that wants three minutes where ninety seconds is
 * normal.
 */
@Composable
private fun PlannedExercisesSection(
    exercises: List<PlannedExercise>,
    open: Boolean,
    onToggle: () -> Unit,
    nameOf: (Long) -> String?,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRest: (Int, Int?) -> Unit,
) {
    val colors = LocalGachiColors.current
    /*
     * WHICH row is having its rest changed, or none. The mm:ss field used to be open on every
     * row at once: with the chip, the caption, the field and four bump buttons, one exercise
     * took about 210 dp of a dialog body that is about 360 dp tall on a 360 dp phone, so a
     * plan of four came to 840 dp of composition — two and a half screens, behind a scrollbar
     * that was not drawn. Collapsed to a chip, a row is 104 dp and the four fit.
     *
     * Held by index, so it is dropped whenever the indices move under it.
     */
    var editingRest by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.Line)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (exercises.isEmpty()) "Exercises - none planned" else "Exercises (${exercises.size})",
                fontSize = TextSize.Meta,
                fontWeight = FontWeight.SemiBold,
                color = colors.inkSecondary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "Hide the exercises" else "Show the exercises",
                tint = colors.inkMuted,
            )
        }

        if (open) {
            // one phrase. The second sentence of the paragraph that was here said the first
            // one again in more words (SYSTEM.md, rule 5)
            Text(
                "Optional - a session with nothing listed is a plan just the same.",
                fontSize = TextSize.Meta,
                color = colors.inkMuted,
            )

            exercises.forEachIndexed { index, planned ->
                PlannedExerciseRow(
                    position = index,
                    last = index == exercises.lastIndex,
                    // a name the catalog no longer has: the line is KEPT and labelled rather
                    // than dropped, because a plan quietly losing a line is worse than one
                    // showing a line it cannot name (data/db/Entities.kt, SlotExerciseEntity)
                    name = nameOf(planned.exerciseId) ?: "Removed exercise",
                    restSec = planned.restSec,
                    editingRest = editingRest == index,
                    onEditRest = { editingRest = if (editingRest == index) null else index },
                    onUp = { editingRest = null; onMove(index, -1) },
                    onDown = { editingRest = null; onMove(index, 1) },
                    onRemove = { editingRest = null; onRemove(index) },
                    onRest = { onRest(index, it) },
                )
            }

            TextButton(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent)
                Text("  Add an exercise", color = colors.accent)
            }
        }
    }
}

/**
 * One planned exercise: where it sits, what it is, and how long the pauses in it are.
 *
 * ── The rest, typed rather than picked off a ladder ──────────────────────────
 * It used to be six chips capped at 4:00 — "not able to choose anything above 4:00" was the
 * complaint that started §13.9. Now it is [TimeField], the same free mm:ss entry the live
 * workout's own rest dialog uses, with "Usual" kept as the one preset worth a tap: it is not
 * a length of time at all, it is "ask [xyz.oleolegka.gachimuchi.domain.restHintSec] instead",
 * and typing a number into the field could never mean that.
 */
@Composable
private fun PlannedExerciseRow(
    position: Int,
    last: Boolean,
    name: String,
    restSec: Int?,
    /** Whether THIS row is the one whose rest is open for editing — at most one is. */
    editingRest: Boolean,
    onEditRest: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
    onRest: (Int?) -> Unit,
) {
    val colors = LocalGachiColors.current
    // re-derived whenever the COMMITTED rest changes for any reason — "Usual" tapped, a fresh
    // slot opened, an exercise moved to this row by a reorder — but left alone the rest of the
    // time, so a keystroke that does not yet parse (a lone "1") is not erased by its own write
    var restText by remember(restSec) { mutableStateOf(restSec?.let(::formatDurationSec) ?: "") }
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.border, MaterialTheme.shapes.small)
            .padding(
                start = Spacing.Inset,
                end = Spacing.Tight,
                top = Spacing.Tight,
                bottom = Spacing.Tight,
            ),
    ) {
        /*
         * 264 dp of dialog content, spent: 12 (the card's own left inset) + 20 (the number)
         * + 8 + NAME + 8 + 48 (the menu) + 4 = 264, so the name gets 164. It used to get 98,
         * with three 48 dp buttons taking 144 of the line next to it.
         */
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
        ) {
            Text(
                "${position + 1}.",
                fontSize = TextSize.Meta,
                color = colors.inkMuted,
                textAlign = TextAlign.End,
                modifier = Modifier.width(20.dp),
            )
            Text(
                name,
                fontSize = TextSize.Body,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "More for \"$name\"",
                        tint = colors.inkSecondary,
                    )
                }
                /*
                 * Taking an exercise OUT lives in here, and it used to be a critical-coloured
                 * cross flush against the "move down" arrow — nothing at all between "shift
                 * this by one" and "throw it off the plan" (SYSTEM.md, rule 3).
                 */
                ActionMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    title = name,
                    actions = buildList {
                        if (position > 0) {
                            add(ItemAction("Move earlier") { menuOpen = false; onUp() })
                        }
                        if (!last) {
                            add(ItemAction("Move later") { menuOpen = false; onDown() })
                        }
                        add(
                            ItemAction(if (editingRest) "Hide the rest" else "Change the rest") {
                                menuOpen = false
                                onEditRest()
                            }
                        )
                        add(
                            ItemAction("Take out of the plan", destructive = true) {
                                menuOpen = false
                                onRemove()
                            }
                        )
                    },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            // the value, always visible, and the way in to changing it. Every row says what
            // its rest is; only the row being edited spends the height on a field
            RestChip(
                text = restSec?.let { "Rest ${formatDurationSec(it)}" } ?: "Rest: usual",
                accented = restSec != null,
                open = editingRest,
                onClick = onEditRest,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onUp, enabled = position > 0) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Move \"$name\" earlier",
                    tint = colors.inkSecondary,
                )
            }
            IconButton(onClick = onDown, enabled = !last) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Move \"$name\" later",
                    tint = colors.inkSecondary,
                )
            }
        }

        if (editingRest) {
            HorizontalDivider(color = colors.grid)
            Column(
                Modifier.padding(top = Spacing.Line),
                verticalArrangement = Arrangement.spacedBy(Spacing.Line),
            ) {
                TimeField(
                    label = "Rest, mm:ss",
                    value = restText,
                    onValueChange = { text ->
                        restText = text
                        // committed only once it is a real rest — MIN_STEP_SEC excludes zero,
                        // which restHintSec would otherwise read back as "nothing chosen"
                        // (§13.9's ceiling is MAX_REST_INPUT_SEC, an exercise's own, not
                        // MAX_STEP_SEC's hour)
                        parseDurationText(text)
                            ?.takeIf { it in MIN_STEP_SEC..MAX_REST_INPUT_SEC }
                            ?.let(onRest)
                    },
                    bumpsSec = listOf(10, 30),
                    isError = restText.isNotBlank() &&
                        parseDurationText(restText)?.let { it !in MIN_STEP_SEC..MAX_REST_INPUT_SEC } ?: true,
                )
                SiblingChip(
                    text = "Usual",
                    selected = restSec == null,
                    accent = colors.accent,
                    onClick = { restText = ""; onRest(null) },
                )
            }
        }
    }
}

/**
 * The rest of one planned exercise, said in the space of a chip.
 *
 * It is a button as well as a value: tapping it is what opens the mm:ss field, and tapping
 * it again puts it away. There is no separate "done" — the panel has exactly one way in and
 * the same way out, and opening another row's rest closes this one.
 */
@Composable
private fun RestChip(text: String, accented: Boolean, open: Boolean, onClick: () -> Unit) {
    val colors = LocalGachiColors.current
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = Spacing.Inset),
        border = BorderStroke(1.dp, if (open) colors.accent else colors.grid),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (accented) colors.accent else colors.inkSecondary,
        ),
    ) {
        Text(text, fontSize = TextSize.Meta, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * A day picked by stepping, not by a calendar popup: the calendar is already on screen
 * behind the dialog, and the day it was opened on is nearly always the right one. One or
 * two days either way is the whole use for this control; anything further is a tap on the
 * grid before the dialog is opened.
 */
@Composable
private fun DayField(label: String, day: LocalDate, onChange: (LocalDate) -> Unit) {
    val colors = LocalGachiColors.current
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = TextSize.Caption, color = colors.inkMuted, modifier = Modifier.padding(end = Spacing.Tight))
        IconButton(onClick = { onChange(day.minusDays(1)) }) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "A day earlier",
                tint = colors.inkSecondary,
            )
        }
        Text(
            fmtWeekdayDay(day),
            fontSize = TextSize.Body,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onChange(day.plusDays(1)) }) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "A day later",
                tint = colors.inkSecondary,
            )
        }
    }
}

/** The rule as a segment label; the domain codes are storage, not English. */
private fun ruleLabel(rule: String): String = when (rule) {
    REPEAT_DAILY -> "Every day"
    REPEAT_WEEKLY -> "Every week"
    else -> "Once"
}

/** Hours a session tends to start at — one tap instead of four digits. */
private val QUICK_TIMES = listOf("07:00", "09:00", "12:00", "18:00", "20:00")

/**
 * Where the caret goes in [text] once [digits] digits are behind it.
 *
 * The field's contents are rewritten under the caret on every keystroke, so an offset means
 * nothing across the rewrite — a colon appearing in front of the caret moves everything after
 * it along by one. What DOES survive is how many digits the user has typed past, because
 * inserting a separator never changes that count. So the offset is thrown away and rebuilt:
 * count digits in the new text, stop after the [digits]-th, and sit just behind it.
 *
 * Landing just behind the last digit rather than after the separator that may follow it is
 * what makes deleting work: backspace on "17:0" takes the zero, not the colon.
 */
private fun caretAfterDigits(text: String, digits: Int): Int {
    if (digits <= 0) return 0
    var seen = 0
    text.forEachIndexed { index, char ->
        if (char.isDigit()) {
            seen++
            if (seen == digits) return index + 1
        }
    }
    return text.length
}

/**
 * The Material 3 clock: the third way to a time, for the ones the chips do not cover.
 *
 * Both of its faces are offered — the dial and the platform's own two-field keyboard entry
 * — because the dial is quicker for a round hour and hopeless for 21:35. It writes the
 * canonical "HH:MM" straight into the field, which is a value the parser always accepts, so
 * the clock is also the way out of a half-typed time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(initial: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val minutes = parseMinuteOfDay(initial)
    val state = rememberTimePickerState(
        initialHour = (minutes ?: DEFAULT_PICKER_MINUTE) / 60,
        initialMinute = (minutes ?: DEFAULT_PICKER_MINUTE) % 60,
        is24Hour = true,
    )
    var keyboard by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(
                Modifier.padding(Spacing.Cards).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.Inset),
            ) {
                Text("Time of the session", style = MaterialTheme.typography.labelLarge)
                if (keyboard) TimeInput(state = state) else TimePicker(state = state)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Line)) {
                    TextButton(onClick = { keyboard = !keyboard }) {
                        Text(if (keyboard) "Clock" else "Keyboard")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { onPick(formatTime(state.hour, state.minute)) }) {
                        Text("Set")
                    }
                }
            }
        }
    }
}

/** Where the clock opens when the slot has no time yet: the usual evening session. */
private const val DEFAULT_PICKER_MINUTE = 18 * 60
