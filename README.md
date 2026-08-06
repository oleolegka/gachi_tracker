# gachi_tracker

Offline-first Android tracker for strength, hangboard and cardio training, with a rest
timer and a training calendar. Everything stays on the phone: no cloud, no accounts and
no Google Play Services.

## What works today

This is an early version — the skeleton of the app:

- a local training journal (append-only, SQLite via Room);
- an exercise catalog with aliases, so "bench" and "bench press" are one exercise;
- six activity forms: strength (weight x reps), holds and hangboard, cardio, duration,
  a bare check-in with no metrics, and body weight;
- personal records: estimated 1RM by the Epley formula, best weight at a given rep
  count, and maximum added weight on hangs;
- a training calendar with repeating slots (view only for now);
- a demo history written on first launch, so the screens are not empty.

## Planned

- a logging screen to use during a session;
- a rest timer with a notification;
- progress charts;
- calendar editing;
- sync with a self-hosted server for backups.

## Design notes

**Hangboard.** An exercise is identified by the triple *name + edge + protocol*: hangs
on a 20 mm edge with a 7:3 work:rest protocol and hangs on a 15 mm edge are different
exercises with separate histories. The tracked variable — and the personal record — is
the added weight, not the duration.

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
