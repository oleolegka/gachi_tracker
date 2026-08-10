# The journal file format

What the app writes when you back the journal up, and what it will read back.

This is the copy of the training history that lives somewhere other than the phone. There is
no other one: the database sits in the app's private storage, the phone this is built for has
no Google backup, and `adb backup` stopped taking app data at this target SDK. A file written
from the Settings tab is the whole insurance policy.

The format is a single CSV table, meant to be opened in a spreadsheet as much as restored back
into the app. The code lives in
[`domain/JournalTransfer.kt`](../app/src/main/java/xyz/oleolegka/gachimuchi/domain/JournalTransfer.kt)
(the format and the merge rules) and
[`data/JournalBackup.kt`](../app/src/main/java/xyz/oleolegka/gachimuchi/data/JournalBackup.kt)
(the database side); they are the authority, this page describes what they do.

The interval-program file ([`program-file-format.md`](program-file-format.md)) is a different,
smaller thing: it exists to SEND somebody a protocol. This one exists to survive losing the
phone, and it carries programs too.

## One file, not two

This used to be two exports: a JSON that restored and a read-only CSV that did not. It is one
CSV now, because a spreadsheet is a table with rows that matter for restoring (the whole
journal, live and dead) and rows that are nicer to read (the picture right now), and the fix
for two files is a flag on one of them, not a second file.

## The shape: one row per record, whatever kind of record it is

Every row is either a **journal event** — one row of the append-only log, exactly as it is
stored, corrections and deletions included — or a **whole reference row**: one catalog
exercise, one plan slot (with what it consists of), one interval program, or the settings.
Which kind a row is says in its first column, `gachimuchi_journal_v1`. That column name is
also the format marker and the version, together — a file whose first column is not named
`gachimuchi_journal_v<N>` was not written by this app.

## The columns: structural, then for the eye

| Column | Kind it applies to | What it is |
|---|---|---|
| `gachimuchi_journal_v1` | every row | The row kind: `event`, `exercise`, `slot`, `program`, `settings`, or `meta` (one row of file-level metadata, see below). |
| `uid` | `event` | The event's identity. For every other kind the identity is already inside `payload` (see below); this column repeats it for a human filtering the file. |
| `event_type` | `event` | The event type, e.g. `strength_set`, `workout_started`, `entry_deleted`. |
| `written_at` | `event` | When the row was WRITTEN. |
| `happened_at` | `event` | When the row's own training HAPPENED, if that is known and different from when it was written — see "Two times" below. |
| `workout_uid` | `event` | The workout this row was recorded during, by identity. |
| `author_id` | `event` | Mirrors the local author column. |
| `payload` | every row | **The whole record, carried through untouched.** For an event, its own JSON payload text, exactly as stored. For every other kind, the whole row — every field — as one compact JSON object. |

Everything after `payload` is **derived**: `current_version`, then `name`, `date`, `workout`,
`exercise`, `form`, `side`, and the value columns a spreadsheet would want to filter or sum
(`weight_kg`, `reps`, `hold_sec`, and so on). They exist so a person can read a row without
decoding its `payload` — a resolved exercise name in place of a uid, a form's numbers laid out
as their own columns.

**A restore reads only the structural columns above and `payload`, and never a derived one.**
That is the rule the whole format is built around, and it is what lets a derived column be
renamed, added to or reordered freely in any later change without a restore silently changing
behaviour — the exact opposite of the design this replaces, where the catalog was exported
column by column and a column added to the entity and forgotten in the exporter came back as
its default, silently, on every future backup. It happened twice in one day. That is why the
catalog, the plan and the programs are one JSON object per row here rather than columns at
all: there is then nothing left to forget.

## `current_version`: the flag in place of a second file

`true` for a row that is what the app currently says: an event still live once every deletion
and correction is applied, or any row of a kind the app does not keep old versions of at all
(the catalog, the plan, the programs and the settings are edited or deleted in place — there is
no old version sitting in the database for them to begin with, so they are always `true`).
`false` for an event a deletion or a correction has superseded.

Filtering the file on `current_version = true` reproduces what the app shows right now.
Filtering on nothing at all is the whole history, corrections and deletions included — which is
what a restore reads.

## Two times: written, and happened

`written_at` is the honest write time. `happened_at` is when the row's own training happened,
which is not always the same moment: a correction (see below) writes a whole new row at the
moment of the CORRECTION, but inherits `happened_at` from the row it replaces, so a set fixed a
week later does not jump to the end of that week's log. A row from before this distinction
existed carries no `happened_at` at all.

## An event, and the payload that is not opened

**The payload is opaque on purpose, and this is the central decision of the format.** Nothing
in the exporter or the importer knows what a strength set contains. Forms grow fields — a
warm-up flag, which hand, the body weight at the time — and if this file listed them as
columns, every new field would need a change here and a chance for somebody to forget one and
quietly drop it out of the only copy of the history that exists.

What that costs, plainly: a payload cannot be validated on the way in, so a corrupt payload is
exported and restored corrupt. For a backup that is the right way round — its job is to
reproduce what was there, not to improve on it.

Local row numbers (`id`, `workout_id`, `space_id`) are **not** in the file. They count how
many rows one phone has written and mean nothing on another. Every link the app reads is
already a uid.

## A reference row: the catalog, the plan, the programs, the settings

Each of these travels as ONE JSON object in its `payload` cell — an exercise, a plan slot
(carrying what it consists of, nested), a program (carrying its groups and blocks, nested), or
the settings. Nothing about them is spread across columns, for the reason above.

An exercise's fields: `uid`, `name`, `form` (1 strength, 2 holds, 3 cardio, 4 duration, 5
check-in, 6 body weight — an unknown code is refused), `created_at`, `protocol_program_uid` (the
library program this exercise's protocol is, by uid), `default_rest_sec`, `led_by_protocol`,
`one_sided`, `bodyweight_share`, `hidden`. The stored `identity_key` is **not** among them: it
is `name` + `form` + protocol folded into one string, and it is recomputed on the way in rather
than trusted from a file, which could disagree with the values it claims to summarise.

A plan slot: `uid`, `name`, `at_time`, `repeat_rule` (`none`, `daily` or `weekly`), `anchor_date`,
`created_at`, and `exercises` — the composition, each one `{ uid, exercise_uid, rest_sec }`. A
line whose exercise is no longer in the catalog carries `exercise_uid: null` and is skipped on
import, counted in the report.

A program: the same shape as a program in the program file (`groups` of `blocks`), plus a
`uid` (so a second restore does not add a fourth copy of "Tabata 20:10"), an `exercise_uid`,
and a `position`.

Settings: the timer's nine switches, whether the timer has been switched on at all, and the
celebration mode. Restoring **applies** them over what is on the phone — the one part of an
import that overwrites something, and it is in the report.

## The `meta` row

One row, always present, `payload` carrying `exported_at` and `device_id` — when the file was
written and which installation wrote it. Decoration: neither is ever restored. A restored copy
is a new installation and mints its own device id.

## What is refused, and why

A file is taken whole or turned away with a sentence. A half-restored journal is a history
with holes in it that nobody can spot, on a device with nothing to compare against.

- **Not CSV, empty, or the first column is not `gachimuchi_journal_v<N>`.** Refused as
  unreadable — this format cannot open a foreign file to name what it actually is; it can only
  say this does not look like one of its own.
- **A version higher than this build understands.** Refused with both numbers.
- **A row not the width of the header.** The file is truncated or was edited by something that
  does not speak CSV.
- **A row with no uid, or two rows sharing one.** Identity is what the merge stands on. Two
  rows with one uid make "have I got this already" unanswerable, and a file like that would
  keep adding rows on every import.
- **An event with no type or no timestamp.**
- **An exercise with no name, or a form code this build does not know.**
- **A plan slot with no name, an unknown repeat rule, or no starting date.**
- **A program that cannot be run** — the same checks the program file applies.

`ignoreUnknownKeys` is set on the JSON inside `payload`, so a v1 file written by a later build
that added an optional field to a reference row still loads.

## Reading it back into a database that is not empty

Import **appends**. Nothing stored is edited or deleted; rows the phone already has are left
exactly as they are. That is what makes importing the same file twice safe, which is the
situation this is built for — somebody who is not sure whether they already restored.

- **Events** merge by `uid`, EVERY one in the file — live and superseded alike, so the history
  a `current_version = false` row records is not lost by restoring. A second import of one file
  adds nothing at all.
- **The catalog** merges by `uid` first, then by identity: name (normalized) + form +
  work:rest protocol. The name alone is not enough — a hangboard exercise's protocol is part of
  what it IS, so "Hangs" at 7:3 and "Hangs" at 10:5 are two exercises. The form is in the
  identity for the mirror-image reason: a "Plank" logged as a duration and a "Plank" logged as
  strength write different payload shapes, and welding them produces one history half the
  readers cannot read.
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
import says so in its report.

### The other seam: a very old set reversal

A `set_cancel` written before schema version 9 names the set it reverses by **row number**,
inside its payload, and this format does not open payloads. Restored into an empty database in
file order the numbers land back where they were — which is why the `event` rows travel in
JOURNAL order and are not sorted by the day trained the way the derived columns might suggest
reading them. Merged into a database that already holds training, such a reversal can name an
unrelated row. Every reversal this app has written since schema version 9 carries the uid as
well and is unaffected.

## What this file does not save

- **The celebration pictures, or any picture attached to an exercise.** They are image files in
  the app's storage; a CSV cell is not where a picture goes.
- **The device id as an identity.** A restored copy is a new installation and mints its own;
  the `meta` row only records which installation wrote the file.
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
