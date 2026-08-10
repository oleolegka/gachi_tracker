package xyz.oleolegka.gachimuchi.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import xyz.oleolegka.gachimuchi.data.DeviceBackupSettings
import xyz.oleolegka.gachimuchi.data.DeviceStore
import xyz.oleolegka.gachimuchi.data.JournalBackup
import xyz.oleolegka.gachimuchi.data.ProgramFiles
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.JOURNAL_CSV_MIME
import xyz.oleolegka.gachimuchi.domain.JOURNAL_FILE_MIME
import xyz.oleolegka.gachimuchi.domain.JournalImport
import xyz.oleolegka.gachimuchi.domain.readJournalFile
import java.time.LocalDate

/**
 * The backup as two buttons: write the journal to a file, and read one back.
 *
 * Same shape as [rememberProgramTransfer] and for the same reason — the feature is a
 * conversation (choose a destination, then a system picker, then say what happened), so it
 * owns its dialogs and the screen keeps two callbacks.
 *
 * ── Restoring asks first, and reports after ─────────────────────────────────────
 * Exporting is harmless and goes straight to the picker. Restoring is not: it merges rows
 * into the journal and applies the settings the file carries, and neither is something to
 * discover afterwards. So it is a question first, and a report of exactly what happened
 * after — counts on both sides, plus anything that did not fit a counter.
 *
 * ── Every path ends in a sentence ───────────────────────────────────────────────
 * A silent failure is the worst outcome this screen has, worse here than anywhere else in
 * the app: the whole point of an export is BELIEVING you have a copy, and "nothing appeared
 * to go wrong" is not the same as having one.
 */
class JournalTransfer internal constructor(
    /** Offers to save or send the whole journal. */
    val export: () -> Unit,
    /** Asks, then opens the system file picker and merges what comes back. */
    val restore: () -> Unit,
    /**
     * Straight to the file picker with a CSV of the journal — see domain/JournalCsv.kt for
     * what is in it. No question first, unlike [restore]: this reads the phone and changes
     * nothing on it, the same as [export] and for the same reason it needs no dialog of its
     * own either. There is nothing to restore FROM it, so there is no third button here for
     * that.
     */
    val exportCsv: () -> Unit,
)

/**
 * Nothing larger than this is read back. A journal of years is a few megabytes indented;
 * thirty-two is room for a lifetime of training and still small enough that a video picked
 * by mistake through "all files" is refused instead of taking the app down with it.
 */
private const val MAX_BACKUP_BYTES = 32_000_000

/** What the file picker is opened with; see the note in [rememberProgramTransfer]. */
private const val ANY_MIME = "*/*"

@Composable
fun rememberJournalTransfer(): JournalTransfer {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now().toString() }
    val backup = remember(context) {
        JournalBackup(AppDatabase.get(context), DeviceBackupSettings(context))
    }
    val deviceId = remember(context) { DeviceStore(context).deviceId }

    var choosing by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<List<String>?>(null) }
    var title by remember { mutableStateOf("Backup") }

    fun say(heading: String, lines: List<String>) {
        title = heading
        report = lines
    }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(JOURNAL_FILE_MIME)
    ) { uri ->
        // a null uri is the user backing out of the picker, which needs no report
        if (uri != null) {
            scope.launch {
                val text = backup.export(today, deviceId)
                val failure = ProgramFiles.write(context, uri, text)
                if (failure != null) {
                    say("Not saved", listOf(failure))
                } else {
                    say(
                        "Saved",
                        listOf(
                            "The whole journal is in that file. Keep it somewhere that is not " +
                                "this phone - a copy that is lost with the phone is not a copy."
                        ),
                    )
                }
            }
        }
    }

    val saveCsv = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(JOURNAL_CSV_MIME)
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = backup.exportCsv()
                val failure = ProgramFiles.write(context, uri, text)
                if (failure != null) {
                    say("Not saved", listOf(failure))
                } else {
                    say(
                        "Saved",
                        listOf(
                            "One row per set, as the app shows it now - deleted entries and " +
                                "corrections are already settled. This file is for reading, " +
                                "not for restoring; keep the JSON backup for that."
                        ),
                    )
                }
            }
        }
    }

    val open = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                when (val read = ProgramFiles.read(context, uri, MAX_BACKUP_BYTES, "journal backup")) {
                    is ProgramFiles.FileText.Failed -> say("Not restored", listOf(read.reason))
                    is ProgramFiles.FileText.Ok -> when (val parsed = readJournalFile(read.text)) {
                        is JournalImport.Rejected -> say("Not restored", listOf(parsed.reason))
                        is JournalImport.Loaded -> {
                            val done = backup.restore(parsed.file)
                            say(
                                if (done.addedAnything) "Restored" else "Nothing to add",
                                done.lines(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Export the journal") },
            text = {
                Text(
                    "Everything the app holds - the journal, the exercises, the plan, the " +
                        "programs and the settings - as one JSON file. Saving writes it wherever " +
                        "you choose; sharing hands the same file to another app to send. The " +
                        "celebration pictures are not in it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    choosing = false
                    save.launch("gachimuchi-journal-$today.json")
                }) { Text("Save to a file") }
            },
            dismissButton = {
                TextButton(onClick = {
                    choosing = false
                    scope.launch {
                        val failure = ProgramFiles.share(
                            context = context,
                            text = backup.export(today, deviceId),
                            fileName = "gachimuchi-journal-$today.json",
                            title = "Send the journal",
                        )
                        if (failure != null) say("Not sent", listOf(failure))
                    }
                }) { Text("Share") }
            },
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Restore from a file?") },
            text = {
                Text(
                    "A backup is merged into what is already here: anything the file has and " +
                        "this phone does not is added, and nothing already here is changed or " +
                        "removed. Restoring the same file twice adds nothing the second time. " +
                        "The settings in the file do replace the ones on this phone. You will " +
                        "get a count of what happened."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    // any type, rather than a filter on application/json: a .json file arrives
                    // from half the file managers labelled octet-stream or text/plain, and a
                    // filtered picker shows the user's own backup greyed out. Nothing about the
                    // contents is trusted either way - readJournalFile validates what comes back
                    open.launch(arrayOf(ANY_MIME))
                }) { Text("Choose a file") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }

    report?.let { lines ->
        AlertDialog(
            onDismissRequest = { report = null },
            title = { Text(title) },
            // one line per section, which is how the report is written: a paragraph would
            // hide the one number that is not what the reader expected
            text = { Text(lines.joinToString("\n")) },
            confirmButton = { TextButton(onClick = { report = null }) { Text("OK") } },
        )
    }

    return remember(save, open, saveCsv) {
        JournalTransfer(
            export = { choosing = true },
            restore = { confirming = true },
            exportCsv = { saveCsv.launch("gachimuchi-journal-$today.csv") },
        )
    }
}
