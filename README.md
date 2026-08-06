# gachi_tracker

Offline-first Android tracker for strength, hangboard and cardio training, with an
interval workout timer and a training calendar. Everything stays on the phone: no cloud,
no accounts, no Google Play Services.

## What it does

**Logging, during the session.** The entry card sits at the bottom of the screen, within
reach of one thumb, and comes prefilled from your last set of that exercise — so another
set of the same is a single tap. The exercise picker is ordered by what you used most
recently, and knows your own names for things: "bench" and "bench press" are one exercise.

**Six kinds of activity**, because they are not the same shape: strength (weight and
reps), hangs and hangboard, cardio, plain duration, a bare check-in with no metrics at
all, and body weight.

**Personal records**, found for you as you log: estimated 1RM by the Epley formula, your
best weight at a given rep count, and the heaviest added weight on hangs.

**An interval timer** that keeps running with the screen off and the phone in a pocket.
Programs are built from timed blocks grouped into sets — hangboard repeaters, Tabata,
EMOM, circuits. A hangboard exercise already knows its protocol and edge, so it expands
into a ready program in one tap. When a run ends, the app offers to write what it counted
into the journal; nothing is recorded until you confirm it. Programs export to and import
from JSON files ([format](docs/program-file-format.md)).

**A calendar you can plan in.** A session is a slot on a day — once, every day or every
week — and the plan is checked against what you actually logged: done, missed, unplanned.

**Charts and history**: activity across the year, trends and volume per exercise over a
chosen period, records with the date they were set.

**Celebration pictures.** Your own images, added from the system photo picker, flashed
over the screen when a set is logged — on every set, on records only, or never. No image
ships with the app.

## Two things worth knowing

**Hangboard exercises are defined by name, edge and protocol.** Hangs on a 20 mm edge at
7:3 and hangs on a 15 mm edge are different exercises with separate histories, because
they are different training. The number tracked — and the record kept — is the added
weight.

**Nothing is ever deleted.** Cancelling a set records a reversal rather than erasing the
original, so the history stays honest about what happened and when.

## Planned

- richer repeat rules for the calendar: an end date, skipping a single occurrence, and
  "every second Tuesday" — today a rule is once, daily or weekly and nothing else;
- sync with a self-hosted server, for backups and for sharing one journal between
  devices.

## Installing

Via [Obtainium](https://github.com/ImranR98/Obtainium): add the app by the URL of this
repository, and updates then arrive from GitHub Releases.

An APK is built automatically whenever a tag like `v0.4.0` is pushed.

## Building from source

You need JDK 21 and the Android SDK (compileSdk 37).

```
./gradlew test            # unit tests
./gradlew assembleDebug   # debug APK
```

The SDK path goes into `local.properties` (`sdk.dir=...`); that file is not part of the
repository.

The launcher icon is generated rather than hand-drawn — it is a 32x32 pixel-art map
inside `tools/pixel_icon.py`. Edit the map or the palette there and run:

```
python3 tools/pixel_icon.py                            # rewrites the icon resources
python3 tools/pixel_icon.py --preview /tmp/icon.png    # and renders what it made
```

## How it is built

Why the journal is append-only, how the timer stays accurate while the device sleeps, and
what a calendar slot really is: [docs/architecture.md](docs/architecture.md).

## License

GPL-3.0 — see [LICENSE](LICENSE).
