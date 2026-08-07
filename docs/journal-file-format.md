# The journal file format

What the app writes when you back the journal up, and what it will read back.

This is the copy of the training history that lives somewhere other than the phone. There is
no other one: the database sits in the app's private storage, the phone this is built for has
no Google backup, and `adb backup` stopped taking app data at this target SDK. A file written
from the Settings tab is the whole insurance policy.

The format is plain JSON, indented, and meant to be readable in a text editor on the worst day.
The code lives in
[`domain/JournalTransfer.kt`](../app/src/main/java/xyz/oleolegka/gachimuchi/domain/JournalTransfer.kt)
(the format and the merge rules) and
[`data/JournalBackup.kt`](../app/src/main/java/xyz/oleolegka/gachimuchi/data/JournalBackup.kt)
(the database side); they are the authority, this page describes what they do.

The interval-program file ([`program-file-format.md`](program-file-format.md)) is a different,
smaller thing: it exists to SEND somebody a protocol. This one exists to survive losing the
phone, and it carries programs too.

## An example

```json
{
  "format": "gachimuchi.journal",
  "version": 1,
  "exported_at": "2026-08-07",
  "device_id": "0198c2f1-6b40-7a3e-9d21-4f77c1a09b52",
  "events": [
    {
      "uid": "0198c2f0-1000-7000-8000-000000000001",
      "ts": "2026-08-07T18:41:02",
      "type": "strength_set",
      "payload": {
        "exercise": "Bench press",
        "reps": 5,
        "weight_kg": 72.5,
        "own_weight": false,
        "exercise_id": 1,
        "exercise_uid": "0198c2ef-0000-7000-8000-00000000000a",
        "op_date": "2026-08-07",
        "exercise_key": "bench press"
      },
      "workout_uid": "0198c2ef-9000-7000-8000-000000000003",
      "author_id": 1
    }
  ],
  "exercises": [
    {
      "uid": "0198c2ef-0000-7000-8000-00000000000a",
      "name": "Bench press",
      "form": 1,
      "created_at": "2026-08-01T09:00:00",
      "edge_mm": null,
      "protocol_work_sec": null,
      "protocol_rest_sec": null,
      "default_rest_sec": 150,
      "led_by_protocol": null
    }
  ],
  "slots": [],
  "programs": [],
  "settings": {
    "default_rest_sec": 120,
    "auto_start_rest": true,
    "adapt_rest_to_exercise": true,
    "prepare_sec": 10,
    "sound": true,
    "vibrate": true,
    "countdown_ticks": true,
    "speak": false,
    "default_sets": 4,
    "timer_enabled": true,
    "celebration_mode": 1
  }
}
```

## The envelope

| Field | Type | Required | Meaning |
|---|---|---|---|
| `format` | string | yes | Always `gachimuchi.journal`. Anything else is refused by name. |
| `version` | integer | yes | The shape of the file. Currently `1`. |
| `exported_at` | string | no | ISO date the file was written. Decoration; never read back. |
| `device_id` | string | no | Which installation wrote it. Decoration; never restored. |
| `events` | array | no | The journal. |
| `exercises` | array | no | The catalog. |
| `slots` | array | no | The plan, each slot with what it consists of. |
| `programs` | array | no | Interval programs. |
| `settings` | object | no | Preferences. |

Every section is optional, so a backup taken before the plan had anything in it still reads.
An entirely empty file is legal and imports nothing.

## An event, and the payload that is not opened

| Field | Type | Required | Meaning |
|---|---|---|---|
| `uid` | string | yes | The identity of the row. Unique within the file. This is what a re-import matches on. |
| `ts` | string | yes | When the row was written (not the day it belongs to — that is inside the payload). |
| `type` | string | yes | The event type, e.g. `strength_set`, `workout_started`, `set_cancel`. |
| `payload` | any | yes | **Carried through untouched.** |
| `workout_uid` | string | no | The workout this row was recorded during, by identity. |
| `author_id` | integer | no | Mirrors the local author column. Defaults to `1`. |

**The payload is opaque on purpose, and this is the central decision of the format.** Nothing
in the exporter or the importer knows what a strength set contains. Forms grow fields — a
warm-up flag, which hand, the body weight at the time — and if this file listed them, every
new field would need a change here, a version bump, and a chance for somebody to forget one
and quietly drop it out of the only copy of the history that exists.

What that costs, plainly: a payload cannot be validated on the way in, so a corrupt payload is
exported and restored corrupt. For a backup that is the right way round — its job is to
reproduce what was there, not to improve on it. A payload that is not JSON at all is carried
as a JSON string and restored verbatim rather than being dropped.

Local row numbers (`id`, `workout_id`, `space_id`) are **not** in the file. They count how
many rows one phone has written and mean nothing on another. Every link the app reads is
already a uid.

## An exercise

| Field | Type | Required | Meaning |
|---|---|---|---|
| `uid` | string | yes | Identity. |
| `name` | string | yes | Must not be blank. |
| `form` | integer | yes | 1 strength, 2 holds, 3 cardio, 4 duration, 5 check-in, 6 body weight. An unknown code is refused. |
| `created_at` | string | yes | When the row was made. |
| `edge_mm` | number | no | Hangboard edge; part of identity (§12-A). |
| `protocol_work_sec` / `protocol_rest_sec` | number | no | The work:rest protocol; part of identity. |
| `default_rest_sec` | integer | no | The rest last chosen for this exercise. |
| `led_by_protocol` | boolean | no | Run sets by the protocol, or just count the rest. `null` means "decide from whether a protocol exists". |
| `one_sided` | boolean | no | A set is done one side at a time. |
| `bodyweight_share` | number | no | How much of the body weight this exercise actually lifts. |

The catalog, the plan and the programs are carried **column by column**, unlike an event
payload. That is a standing obligation on whoever adds a column: a column added to `exercises`
and not added here does not survive a restore, silently — the file loads, the exercise comes
back, and one thing about it is quietly the default.

## A plan slot

| Field | Type | Required | Meaning |
|---|---|---|---|
| `uid` | string | yes | Identity. |
| `name` | string | yes | The name of the session. |
| `at_time` | string | no | `HH:MM`, or absent for "some time that day". |
| `repeat_rule` | string | yes | `none`, `daily` or `weekly`. Anything else is refused. |
| `anchor_date` | string | yes | The day the series is counted from. |
| `created_at` | string | yes | |
| `exercises` | array | no | What the session consists of, in order. |

A planned line is `{ "uid": …, "exercise_uid": …, "rest_sec": … }`. The exercise is named by
uid even though the database still stores that one link as a row number — it is translated on
the way out and back on the way in. A line whose exercise is no longer in the catalog is
written with `exercise_uid: null` and is skipped on import, counted in the report.

## A program

Same shape as a program in the program file (`groups` of `blocks`; see that page), plus three
things: a `uid`, so a second restore does not add a fourth copy of "Tabata 20:10"; an
`exercise_uid`, because a link said in identities does travel; and a `position`, so the timer
tab reads the way it was arranged. The same bounds are applied as in the program file — a
program that expands to nothing, or to more steps than can be run, is refused with the rest of
the file.

## Settings

The timer's nine switches, whether the timer has been switched on at all, and the celebration
mode. Not history, and cheap to set again — but "which of these did I have on" is exactly the
question nobody can answer on the day the phone is replaced. Every field is optional and
defaults to the app's own default, so a file written before a setting existed still reads.

Restoring **applies** the settings over what is on the phone. That is the one part of an
import that overwrites something, and it is in the report.

## What is refused, and why

A file is taken whole or turned away with a sentence. A half-restored journal is a history
with holes in it that nobody can spot, on a device with nothing to compare against.

- **Not JSON, truncated, or missing `format`/`version`.** Refused as unreadable.
- **A different `format` string.** Named in the message.
- **A `version` higher than this build understands.** Refused with both numbers: a future
  shape is recognised rather than half-read into a journal with silently missing rows.
- **A row with no uid, or two rows sharing one.** Identity is what the merge stands on. Two
  rows with one uid make "have I got this already" unanswerable, and a file like that would
  keep adding rows on every import.
- **An event with no type or no timestamp.**
- **An exercise with no name, or a form code this build does not know.**
- **A plan slot with no name, an unknown repeat rule, or no starting date.**
- **A program that cannot be run** — the same checks the program file applies.

Unknown keys are ignored, so a v1 file written by a later build that added an optional field
still loads.

## Reading it back into a database that is not empty

Import **appends**. Nothing stored is edited or deleted; rows the phone already has are left
exactly as they are. That is what makes importing the same file twice safe, which is the
situation this is built for — somebody who is not sure whether they already restored.

- **Events** merge by `uid`. A second import of one file adds nothing at all.
- **The catalog** merges by `uid` first, then by identity: name (normalized) + form + edge +
  work:rest protocol. The name alone is not enough — §12-A makes "Hangs 20 mm 7:3" and
  "Hangs 15 mm 7:3" two exercises. The form is in the identity for the mirror-image reason: a
  "Plank" logged as a duration and a "Plank" logged as strength write different payload shapes,
  and welding them produces one history half the readers cannot read.
- **Slots and programs** merge by `uid`. A program arriving under a name already taken by a
  different program is marked — `Tabata 20:10 (imported)` — never merged over.
- **The report** counts what was added, what was already here, and what did not fit, and is
  shown after the import. Silence about a row that did not land is the failure this whole
  feature exists to prevent.

### The seam, stated: one exercise under two keys

When a file's exercise matches a stored one by identity but not by uid — which happens when
two devices invented the same exercise independently — the key **already on the phone** wins,
for the same reason a program import never overwrites: what is on the phone is what the user
has been using.

The sets arriving in that same file name the exercise by the FILE's key, inside payloads this
format refuses to rewrite. They land in the journal and are visible in the history and the
daily feed, but they do **not** appear under that exercise's own records and charts. The
import says so in its report. Fixing it properly means letting a catalog row carry more than
one key, which is a schema change and a separate piece of work.

A restore onto an empty phone, and a re-import of a file this phone wrote, never reach this
case.

### The other seam: a very old set reversal

A `set_cancel` written before schema version 9 names the set it reverses by **row number**,
inside its payload, and this format does not open payloads. Restored into an empty database in
file order the numbers land back where they were (a uid sorts by the moment it was minted, the
journal is append-only and has no gaps, so the rows are renumbered identically). Merged into a
database that already holds training, such a reversal can name an unrelated row and hide a set
that was never cancelled. Every reversal this app has written since version 9 carries the uid
as well and is unaffected.

## What this file does not save

- **The celebration pictures.** They are image files in the app's storage; only the mode is in
  here. A restored phone shows nothing until pictures are added again.
- **The device id.** A restored copy is a new installation and mints its own; the file only
  records which installation wrote it.
- **The unfinished timer run and the offer waiting to be written down.** They live in
  preferences, are meaningful for minutes, and are meaningless on another phone.
- **Anything that was never in the database** — a set done but not logged is not in the file
  either, and a backup taken on Tuesday does not contain Wednesday.

## How files are written and read

Through the system file picker (the Storage Access Framework), so the app holds **no storage
permission**: the picker hands back one document, for one read or one write. "Share" writes a
copy into the app's cache and hands that one file to another app through a `FileProvider`.

The copy is only as safe as where it is put. A file on the phone itself is lost with the phone,
which is the failure being insured against — put it somewhere else.
