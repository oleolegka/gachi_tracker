# How it is built

Design decisions and the reasoning behind them. The [README](../README.md) covers what
the app does; this file covers why it does it that way.

## The journal is append-only

Entries are never deleted. Correcting one writes an `entry_amended` event carrying a patch of
its payload; removing one writes an `entry_deleted` naming it. The older `set_cancel` is the
same act said in an older word and is still read. Nothing is ever updated in place.

Two things follow. The history stays truthful — it records what happened and when it was
corrected, rather than quietly rewriting itself. And synchronising several devices later
becomes a merge by union of immutable events instead of conflict resolution, which is the
part that usually sinks offline-first apps.

### One funnel decides what the journal now says

`domain/Amendments.kt` is the only place corrections are applied, and every reducer starts
there. That is a rule with a scar behind it: the reversing event used to be applied in one
reducer only, so a cancelled row could be gone from the logging feed and still be inside its
workout.

The fold, in full:

- a live deletion wins over every amendment, whenever it was written — correcting something
  that should not exist is not a reason to keep it;
- otherwise live amendments are laid over the payload in time order, so the last word wins
  FIELD BY FIELD. Two corrections of different fields both survive;
- "live" is recursive, and that is what makes undoing an undo work: a deletion that has itself
  been deleted stops hiding anything, following the chain to its end. There is no restore
  event, because deleting the deletion is one;
- an amendment changes VALUES and the DATE. It may not change which exercise or workout an
  entry belongs to — that is a deletion and a new entry, because an exercise's history has to
  be the entries that always were its own. Those keys are refused on the way in and ignored on
  the way out, so a journal merged in from another phone cannot do it either;
- it applies to the service events too. A workout started by mistake, an exercise added to the
  wrong one and a workout finished by mistake are all removable, and a workout can be redated
  or renamed by correcting the event that started it.

Two costs, stated. The fold happens per read and the journal is walked more than once for a
screen that asks several reducers — personal scale makes that free, and it would not survive a
shared journal. And an amendment can in principle leave a payload unreadable, which the readers
skip; the repository parses the merged result before writing and refuses, so such a row can
only arrive from outside.

## A hangboard exercise is identified by name, edge and protocol

Hangs on a 20 mm edge at a 7:3 work:rest protocol and hangs on a 15 mm edge are separate
exercises with separate histories, because they are separate training. Edge and protocol
are columns on the exercise, not fields repeated on every set, so they cannot drift within
one exercise.

The tracked variable — and therefore the personal record — is the added weight. Duration
is fixed by the protocol, so improvement shows up as load.

## The timer counts time, not ticks

The state of a running timer is the moment the current step ends, read from
`SystemClock.elapsedRealtime` — a monotonic clock that keeps running while the device
sleeps. Nothing decrements a "seconds left" counter, so the countdown cannot fall behind
while the process is frozen, and returning after four minutes away lands on the step the
workout is genuinely on.

A run is persisted as that end moment plus a boot reference. That is what lets it survive
the process being killed, and what makes it obvious that a run from before a reboot cannot
be resumed — its end moments are readings of a clock that no longer exists.

Cannot be resumed is not the same as worthless. The count of sets a run got through owes
nothing to the monotonic clock, so a run a reboot ended is offered as the part that
happened, conservatively: the step it was standing on does not count, because the device
could have gone down at any point inside it. The boot reference does one more job here — it
is wall minus monotonic, so it turns those dead readings back into the wall time the run
was last known to be alive.

The same reference is what dates a session. An outcome is very often built long after the
run ends, because a run ends with the phone in a pocket and the process may not survive
until the screen is looked at; reading the wall clock at that point filed evening sessions
under the following morning. The day comes from `bootRef + the moment the last step ended`,
never from "now".

And nothing announces a boundary that is not now. The path that rebuilds the controller
from disk is both the recovery path and the backstop path — the exact alarm wakes a dead
process and the rebuilt controller settling the state is what produces the beep — so the
signal cannot be suppressed on restore. It is gated on lateness instead: within a few
seconds of the boundary it sounds, and hours later it does not, which is the difference
between an alarm firing and a person opening the app.

Keeping it alive takes three mechanisms, because no single one is enough:

- a **foreground service**, so the process is not frozen — Android suspends an app roughly
  twenty seconds after the screen goes off;
- a **partial wake lock** while the clock is moving, so the CPU does not sleep through a
  ten-second interval;
- an **exact alarm** at the end of the step, as a backstop if the first two fail.

The obvious reference implementation, [Just Another Workout
Timer](https://github.com/blockbasti/just_another_workout_timer), does none of this: it
increments a counter on a one-second `Timer.periodic` and holds only a wakelock that keeps
the screen on. Its issue #69 — the timer falls behind with the screen off — is closed as
`wontfix`. That is the failure this design exists to avoid.

## Programs are two levels deep

A program is groups of blocks. A block is one timed effort with its own rest and repeat
count; a group repeats its blocks as a unit. That covers hangboard repeaters, Tabata, EMOM
and circuits, and it is as deep as an editor can go and still be usable one-handed.

A rest between sets is not a separate feature but a program of one step.

Because an exercise already carries its protocol and edge, and the journal knows how many
reps were done last time and how long the rests actually were, "Hangs 20 mm - 7:3" expands
into a full interval program with nothing left to ask.

## Signals go out on the alarm stream

Vibration is the primary channel and the tone is the addition: at the gym the phone is in
a pocket with the ringer on silent. The alarm stream is the one Android does not silence
in that state — which also means the tones are loud and ignore the ringer switch,
deliberately.

Spoken announcements are optional and are only offered once a speech engine has actually
been found. On a phone without Google services there often is none, and the app says so
instead of failing quietly.

## The countdown decides when to signal; the loop only obeys

What the timer owes at a given instant — a boundary signal, a countdown tick, or nothing —
is a pure function of the step list, the run state and the monotonic clock, so the timing of
the signals is tested on the JVM rather than only heard on a phone.

The rule that function exists to enforce: a tick is only made STRICTLY INSIDE a step. On a
step three seconds long the "three" tick would otherwise fall on the moment the step begins,
and since the tone generator plays one tone at a time and the vibrator one waveform at a
time, the tick would cut the boundary signal off. On 7:3 repeaters that silenced the change
of step at every rest.

For the same reason the signal is fired before the state is persisted and the notification
redrawn, not after: those involve a synchronous disk write, and a beep queued behind one
arrives after the step it announces.

## A finished run offers to log itself, and never logs by itself

Ending a run raises a summary — "three sets of six, the last one of three" — with every set
editable. Nothing reaches the journal until it is confirmed: a timer that quietly recorded
sets you did not do would poison the only record of what was trained. And nothing is
confirmed silently either: the write says what it wrote, by name and count, with an undo.

Every run offers except a rest between sets. A program that knows which catalog exercise it
trains arrives filled in; one that does not asks, once, and the answer is written back onto
the program so it never asks again. Restricting the offer to programs generated from an
exercise is what made a whole session of saved-protocol repeaters count twenty-four hangs
and then say nothing.

The offer is written to disk. A run ends with the phone in a pocket, and by the time the
screen is looked at the process may have been killed; an offer that lived in memory went
with it. A stored offer states when the run ended and files its sets under the day it
happened on, and is dropped after a day.

Stopping half way offers only the part that ran. Skipping forward, however, counts the
skipped efforts as done — the runner keeps a position, not a history — which is exactly
why the numbers are shown before they are written.

The pause between sets comes from the program, where it is known exactly rather than
derived.

## Programs are filed under headings the user writes

The timer tab groups programs by a free-text category on the program itself, with the
uncategorised ones under "Other" and every heading collapsible. A list with nothing
categorised draws as a plain list, so a phone with three programs does not grow a filing
system. Folders as rows would need a table, a rename and a delete story; grouping by the
linked exercise would sort almost nothing, because a circuit or a warm-up never links to
one exercise.

## Rest between sets is derived, not stored

The pause after a set only becomes known when the next one is logged, by which time the
earlier event is already in an append-only journal and cannot be amended. So the app
measures the gap between the two write times instead, and defers to an explicit
`rest_after_sec` whenever a record carries one.

Gaps longer than twenty minutes are treated as a break in the workout rather than a rest,
and are not reported.

## The plan is edited; the facts are not

A calendar slot is one row that says "this session, at this time, repeating by this rule
from this date". The individual occurrences are computed, never stored.

So editing a slot moves the whole series at once, and the weekday of a weekly session
comes from its date field rather than from a weekday picker — "Gym on Mon and Thu" is two
slots. Deleting one removes every occurrence, the past ones included: days it used to sit
on stop counting as missed.

Nothing in the journal is touched by any of it. What you actually did is a separate,
append-only record, and the plan is only ever compared against it.

## Plan versus fact is judged per slot: by the link when there is one, by the clock otherwise

A workout started from a planned session records which one, and such a slot is closed by
that workout and by nothing else — no window, no proximity, no competition with the day's
other slots. That is a statement the user made by pressing start on the plan's own card, and
it is asked before anything else.

Everything logged WITHOUT pressing start names no session, and for that the calendar still
infers the link from the time the entry was written. The rule is fixed rather than clever,
because a verdict has to be predictable:

- an entry closes a slot if it was written between 30 minutes before its time and 3 hours
  after it; of all possible pairs the closest is taken first, then the next;
- one entry closes at most one slot, and every slot needs an entry of its own: two sessions
  planned and one workout logged leaves one of them open;
- a slot whose window has not opened cannot be done — an entry at noon says nothing about a
  session planned for eight in the evening;
- a slot with no time covers the whole day, and an entry backfilled on another day (its
  clock time is when it was typed, not when it was trained) falls back to the same day-level
  granularity: it closes the earliest slot still open that day;
- a slot is missed only once its window has closed, so nothing is called missed while an
  entry could still land in it;
- an entry that closes nothing is unplanned training, and a day's colour in the grid is the
  summary of its slots, with a missed one dominating.

The cost of being honest about it: logging a session hours away from the time it was
planned for now leaves the slot missed and the entry unplanned, where the old day-level
rule counted the whole day as done. That is the same information the old rule was hiding.

Three places where the link and the old guess give different answers, all of them decided in
favour of the link:

- a workout started from a slot and recorded hours away from its time now closes it, where
  the guess left the slot missed and the sets unplanned. The "window has not opened" rule
  does not apply to it either — sets logged at noon close an eight-in-the-evening session
  that they were started from;
- two workouts started from the same slot close it once, the first in the day's order. One
  slot is one session; the second workout is unplanned training on a day that had a plan;
- a workout naming a slot that is not on the day (deleted, its rule edited, or a journal
  merged in from another phone) closes nothing, and it is NOT handed back to the guess —
  otherwise "this was Tuesday's gym" could end up closing Tuesday's hangboard. The cost is
  the one deleting a slot already warns about: removing a plan un-plans the days it sat on,
  so a day that read as done goes back to reading as merely trained.

A workout that was started from a slot and then had nothing logged into it closes nothing
either — there is no entry to close it with. The day screen still hides such a plan behind
the workout card by its own rule, so it is not offered to be started twice.

## Celebration pictures are yours and are copied in

No image ships with the app. They are picked with the system photo picker, which needs no
storage permission and hands back only the files that were tapped. Each one is copied into
the app's own storage, so moving or deleting the original later changes nothing. With an
empty gallery the feature is silent.

## No Google Play Services

Nothing in the app depends on a Google service: no FCM, no Play-backed location, no
Play-only libraries. It is meant to work on GrapheneOS and other builds without them,
which is also why notifications, alarms and speech all go through plain platform APIs.

## Back is one function, not a stack

There is no navigation library. The app is five tabs with four full-window modes over them
(the logging screen, the workout screen, the form detail screen, the program editor), and
which one is in front is four flags.

Back is decided by one pure function over those same flags, `backStep` in
`ui/Navigation.kt`, written in the same order the screen is drawn in: close the editor,
else close the detail screen, else close the logging screen, else close the workout screen,
else go to Today, else let the system background the app. Closing a mode leaves the tab
underneath untouched, which is what makes "back out of logging" return to whichever tab
opened it.

Logging sits ABOVE the workout screen on purpose, and that pair is the only place the order
carries a decision. A workout is opened to be read, and "Continue" leads from it into the
entry card; backing out of the entry card therefore lands on the workout it was writing
into, rather than skipping past it to a tab.

Back goes to Today rather than to the previously visited tab. Tabs are switched idly and
back and forth, so a tab history would mostly record glances nobody remembers making, and
the number of presses needed to leave the app would depend on how much browsing happened.
This way it is at most two from anywhere.

Dialogs and bottom sheets are not in the rule. Each is hosted in its own window and takes
the gesture before the app sees it.

## A day is a short list of cards, built once and drawn twice

The Today tab and the day picked on the calendar show the same thing: two or three cards
covering everything that day is about. A card is a WORKOUT or a group of entries recorded
outside one — never "an exercise", which is what Today used to list and what turned a gym
session into eleven rows.

Which cards there are, in what order, and what each says is `domain/DayCards.kt`, a pure
function over the journal and the plan; the screens only draw it (`DayCardList`). Two
screens computing "what happened on this day" separately is two answers, and the day they
disagree is the day the app stops being believed.

Four kinds, told apart by their SUBTITLE rather than by colour, as everywhere else here:

- a **planned** session with nothing against it yet, which offers to start a workout;
- the workout **in progress**, which offers to continue and says how much is in it;
- a **finished** workout, which opens;
- entries logged outside a workout, GROUPED BY EXERCISE — five fingerboard sets on their
  own are one card, not five.

Everything is ordered by the clock, and a card the clock cannot place goes last: training
typed up days later carries the time it was TYPED, and printing that would put a morning
workout at the bottom of the evening, plausibly and wrongly.

## Logging is entered from the thing being logged

There is no floating "log a set" button any more. It was the app's primary action stated in
the abstract: press it, and then work out which exercise it had decided the set was about.
The way in is now the card — a plan starts a workout, a running workout continues, "Add"
offers a workout or a single entry — so the action is always named by what it acts on.

The consequence that matters is not cosmetic: the entry card is now always entered FOR A
DAY, and usually for a workout, so it is told which day it writes under instead of assuming
today. That is what makes typing up last Tuesday possible, and what stops a set logged into
a backdated workout being filed under today by the calendar while the workout shows it —
one row, two views, permanently disagreeing, in an append-only journal. The rule is
`loggingDay` in `domain/Workout.kt`: the workout's day wins.

The timer is the exception, and it is not a gap. A finished run does not open the logging
screen at all: it raises an offer that already knows the sets, the exercise and the day,
and confirming it writes them through the repository directly. Sending it through the
entry card would mean re-typing four sets the app has just counted, which is the problem
the offer exists to remove.

## Today is watched, not read once

"Today" used to be `LocalDate.now()` evaluated when the ViewModel was built. A phone left in
a pocket overnight keeps its ViewModel, so the app woke up still believing it was yesterday
and the first set of the morning went into the journal under the wrong date.

It is a flow now, polling the date once a minute and shortening the wait as midnight
approaches (`domain/Today.kt`). The poll is not laziness: a coroutine `delay` is not a
promise about wall-clock time — the device dozes and the process is frozen — so a sleep
timed to end exactly at midnight can come back hours late, while a date comparison every
minute costs nothing and is right however the sleep behaves.

## There is no demo data, and empty screens say what to do

The app used to write about ninety days of invented training so that no screen would ever
be seen empty. It ran on first launch at first, then from a button in Settings behind a
confirmation, with a removal routine that knew which rows were the seed's.

It is gone entirely (schema version 7). The reasons stack up rather than balance out: it
once wrote synthetic sets into exercises the user had created; it cost a `seeded` column on
three tables, a migration, and a page of rules about which deletes were allowed to touch
what; and every one of those costs was paid inside the one part of the app that promises the
journal is true. What it bought was screens that looked inhabited to somebody who had not
trained yet.

Empty states do that job better, because they can say what to do. An empty day names the
button under it, the overview says where the first entry comes from, and the logging screen
explains what an exercise is before asking for one.

## Exercises are found by name, not by learned synonyms

The catalog used to carry a `dictionary` of words the user had taught it — `bench` ->
`Bench press` — ported from the telegram bot, where an exercise was named by typing a
sentence and a synonym was the only way to be understood.

In the app the exercise is picked from a list ordered by recency, so a synonym could only
ever save keystrokes in a search, and it could not be set on purpose: the app guessed one
from whatever was in the search box when a row was tapped. For a while the mechanism was
unreachable altogether — the search filtered the word out of the list before it could be
learned, and the fix was to stop filtering, which turned "no match" into "here is the whole
catalog again".

Both are gone (schema version 7). Search matches a substring of the name, and a word that
matches nothing narrows the list to nothing and says so, with the two ways out named: clear
the search, or create the exercise.

## Known limits

- **A slot with nothing started from it is matched to an entry by the clock, never by what
  was trained.** A workout started from the plan's own card says which session it was and is
  believed; anything logged without pressing start still closes whichever slot it was
  written near, so a set of push-ups counts against a gym slot just as well.
- **Strength volume is tonnage** (weight times reps). Sets done at body weight contribute
  zero, so a mixed history understates the bars.
- **The activity heatmap counts distinct exercises per day**, not events. Counting events
  would push every gym day into the darkest bucket and flatten the year.
- **Repeat rules are once, daily or weekly.** No end date, no skipped occurrence, no
  "every second Tuesday".
- **Nothing forces a new reducer through the correction funnel.** `liveEvents` is a call every
  reader has to make, not a type it has to accept, so a reducer written next month can read the
  raw list and be wrong in exactly the way this one was. The defence is a test that asks every
  reader the same question in one place, which catches it and cannot prevent it.
- **Back does not undo a hop between hold siblings.** The form detail screen can switch to
  a sibling edge or protocol in place; back closes the screen rather than stepping back
  through the siblings visited. That in-screen move is the one thing the navigation rule
  does not see.
- **A workout has no name of its own.** It borrows the name of the slot it was started
  from, resolved when the card is drawn, so renaming a planned session renames every
  workout ever started from it — back through the history. A workout started off-plan has
  no name at all and is shown by its time.
- **A workout cannot be edited or deleted yet.** The workout screen shows what is in it and
  nothing more. Correcting or removing an entry is a change the records, the statistics,
  the calendar and the heatmap all have to honour at once (§13.6), which makes it a step of
  its own rather than a control to add to a screen.
- **The entry card still shows the whole day, not the workout.** It is the old logging
  screen wired to the new frame: it is told which day it writes under, but the tape below it
  is everything logged that day, so a day with two workouts shows both. Rebuilding it around
  per-exercise cards with parallel rest countdowns is the next step.
- **Starting from a plan does not copy the plan's exercises.** Slots carry one now, and the
  copy is not implemented: a workout started from a plan arrives empty and is filled in as
  it goes. Which rest wins when the slot names one and the catalog remembers another is
  still open.
