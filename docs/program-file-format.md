# The program file format

What the app writes when you export interval programs, and what it will read back.

The format is plain JSON and is meant to be opened in a text editor: every field is
spelled out even when it holds its default value, so a file can be edited by hand, kept in
a backup, or written by something that is not this app.

The code lives in
[`domain/ProgramTransfer.kt`](../app/src/main/java/xyz/oleolegka/gachimuchi/domain/ProgramTransfer.kt)
and is the authority; this page describes what it does.

## An example

```json
{
  "format": "gachimuchi.programs",
  "version": 1,
  "programs": [
    {
      "name": "Hangboard repeaters 7:3",
      "groups": [
        {
          "name": "Repeaters",
          "blocks": [
            {
              "name": "Hang",
              "work_sec": 7,
              "rest_sec": 3,
              "repeats": 6
            }
          ],
          "repeats": 4,
          "rest_between_repeats_sec": 180,
          "rest_after_sec": 0
        }
      ],
      "prepare_sec": 15
    }
  ],
  "exported_at": "2026-08-06"
}
```

That file is four sets of six 7:3 hangs, three minutes between sets, with fifteen seconds
to get to the bar first.

## The envelope

| Field | Type | Required | Meaning |
|---|---|---|---|
| `format` | string | yes | Always `gachimuchi.programs`. Anything else is refused by name. |
| `version` | integer | yes | The shape of the file. Currently `1`. |
| `programs` | array | yes | At least one program. An empty array is refused. |
| `exported_at` | string | no | ISO date the file was written. Decoration only; never read back. |

## A program

| Field | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `name` | string | yes | | Must not be blank. |
| `groups` | array | yes | | At least one group. |
| `prepare_sec` | integer | no | `10` | Lead-in before the first effort. `0` means none. |

## A group

A group is a run of blocks repeated as a unit — one "set" of a circuit or of a hangboard
protocol.

| Field | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `name` | string | yes | | Shown while that part of the program runs. |
| `blocks` | array | yes | | At least one block. |
| `repeats` | integer | no | `1` | How many times the whole group runs. |
| `rest_between_repeats_sec` | integer | no | `0` | Pause between repeats. Never added after the last one. |
| `rest_after_sec` | integer | no | `0` | Pause before the next group. Dropped for the last group. |

## A block

A block is one timed effort with the pause that follows it.

| Field | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `name` | string | yes | | Announced and shown as the effort runs. |
| `work_sec` | integer | yes | | The effort itself. At least 1. |
| `rest_sec` | integer | no | `0` | Pause after each repeat of this effort. |
| `repeats` | integer | no | `1` | How many times the work/rest pair runs back to back. |

## What is refused, and why

Reading a file never crashes the app and never half-imports: a file is taken whole or
turned away with a sentence explaining which part is wrong. Importing three good programs
out of five while saying nothing about the other two would leave you believing you have a
copy of something you do not.

- **Not JSON, truncated, or missing `format`/`version`.** Refused as unreadable.
- **A different `format` string.** Named in the message, so it is obvious the file belongs
  to another app.
- **A `version` higher than this build understands.** Refused with both numbers. This is
  the whole point of the field: a future shape is recognised rather than half-read into a
  program with silently missing steps.
- **No programs, a program with no groups, a group with no blocks, a blank name.**
- **Numbers that parse but cannot be run**: an effort of 0 seconds (it would expand to
  nothing), a rest longer than an hour, a repeat count outside 1..999. The bounds are the
  ones the editor enforces, applied again because a file did not come from the editor.

Unknown keys are ignored. A file written by a later build of the same version that added
an optional field still loads here.

## What does not travel

Database row ids. A program is always imported as a **new** program, and a name that is
already taken is marked — `Tabata 20:10 (imported)`, then `(imported 2)` — rather than
replacing what is on the phone. The stored program may have been edited since it was
exported, and overwriting it with an older copy is the one outcome that cannot be undone.

## How files are read and written

Through the system file picker (the Storage Access Framework), so the app holds **no
storage permission**: the picker hands back one document, for one read or one write.
"Share" writes a copy into the app's cache and hands that single file to another app
through a `FileProvider`; nothing else in the app's storage is reachable that way.
