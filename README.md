# gachi_tracker

Offline-first Android tracker for strength, hangboard and cardio training, with a rest
timer and a training calendar. Everything stays on the phone: no cloud, no accounts and
no Google Play Services.

## What works today

This is an early version — the skeleton of the app:

- a logging screen for use during a session: the entry card is pinned to the bottom of
  the screen and prefilled from the last set of that exercise, so another set of the same
  is a single tap; the exercise picker is a sheet ordered by what you used most recently;
- a local training journal (append-only, SQLite via Room);
- an exercise catalog with aliases, so "bench" and "bench press" are one exercise;
- six activity forms: strength (weight x reps), holds and hangboard, cardio, duration,
  a bare check-in with no metrics, and body weight;
- personal records: estimated 1RM by the Epley formula, best weight at a given rep
  count, and maximum added weight on hangs;
- a training calendar with repeating slots (view only for now);
- an interval workout timer that keeps running with the screen off (see below);
- a demo history and two starter programs written on first launch, so the screens are
  not empty.

## Planned

- exporting and importing programs as files;
- writing the sets of a finished program into the journal;
- progress charts;
- calendar editing;
- sync with a self-hosted server for backups.

## Design notes

**Hangboard.** An exercise is identified by the triple *name + edge + protocol*: hangs
on a 20 mm edge with a 7:3 work:rest protocol and hangs on a 15 mm edge are different
exercises with separate histories. The tracked variable — and the personal record — is
the added weight, not the duration.

**The timer counts time, not ticks.** The state of a running timer is the moment the
current step ends, read from `SystemClock.elapsedRealtime` — a monotonic clock that keeps
running while the device sleeps. Nothing decrements a "seconds left" counter, so the
countdown cannot fall behind while the process is frozen, and coming back after four
minutes away lands on the step the workout is genuinely on. A run is written to disk as
the end moment plus a boot reference, which is what lets it survive the process being
killed, and what makes it obvious that a run from before a reboot must be discarded
rather than resumed.

Keeping it alive takes three mechanisms, because no single one is enough: a foreground
service so the process is not frozen (Android suspends an app roughly twenty seconds
after the screen goes off), a partial wake lock while the clock is moving so the CPU does
not sleep through a ten-second interval, and an exact alarm at the end of the step as a
backstop if the first two fail.

**Programs are two levels deep.** A program is groups of blocks: a block is one timed
effort with its own rest and repeat count, a group repeats its blocks as a unit. That
covers hangboard repeaters, Tabata, EMOM and circuits, and it is as deep as an editor can
go and still be usable one-handed. A rest between sets is not a separate feature but a
program of one step.

**A hangboard exercise is already a program.** An exercise carries its work:rest protocol
and its edge, and the journal knows how many reps were done last time and how long the
rests actually were. So "Hangs 20 mm - 7:3" expands into a full interval program in one
tap, with nothing left to ask.

**Signals go out on the alarm stream.** Vibration is the primary channel and the tone is
the addition: at the gym the phone is in a pocket with the ringer on silent. The alarm
stream is the one Android does not silence in that state — which also means the tones are
loud and ignore the ringer switch, deliberately. Spoken announcements are optional and
are only offered once a speech engine has actually been found; on a phone without Google
services there often is none, and the app says so instead of failing quietly.

**Rest between sets is derived, not stored.** The pause after a set only becomes known
when the next one is logged, by which time the earlier event is already in an append-only
journal and cannot be amended. So the app measures the gap between the two write times
instead, and defers to an explicit `rest_after_sec` whenever a record carries one (the
Telegram bot writes it). Gaps longer than 20 minutes are treated as a break in the
workout rather than a rest and are not reported.

**No Google Play Services.** The app depends on no Google service and is meant to work
on GrapheneOS and other builds without them.

**Append-only journal.** Entries are never deleted; cancelling a set is recorded as a
separate reversing event. That keeps the history trustworthy, and it reduces future
multi-device sync to a merge by union instead of conflict resolution.

## Installing

Via [Obtainium](https://github.com/ImranR98/Obtainium): add the app by the URL of this
repository, and updates then arrive from GitHub Releases.

An APK is built automatically whenever a tag like `v0.1.0` is pushed.

## Building from source

You need JDK 21 and the Android SDK (compileSdk 37).

```
./gradlew test            # unit tests
./gradlew assembleDebug   # debug APK
```

The SDK path goes into `local.properties` (`sdk.dir=...`); that file is not part of the
repository.

## License

GPL-3.0 — see [LICENSE](LICENSE).
