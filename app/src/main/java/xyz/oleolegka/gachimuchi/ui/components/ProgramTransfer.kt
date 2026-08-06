package xyz.oleolegka.gachimuchi.ui.components

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import xyz.oleolegka.gachimuchi.data.ProgramFiles
import xyz.oleolegka.gachimuchi.domain.PROGRAM_FILE_MIME
import xyz.oleolegka.gachimuchi.domain.ProgramImport
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.readProgramFile
import xyz.oleolegka.gachimuchi.domain.writeProgramFile
import java.time.LocalDate

/**
 * Programs in and out of files, as two functions a screen can call.
 *
 * ── Why this is a `remember...` that also draws ─────────────────────────────────
 * Same shape as [rememberTimerEnabler], and for the same reason: the feature is a
 * CONVERSATION (pick a destination, then a system picker, then say what happened), so it
 * owns dialogs, and it has to be created once above the list rather than once per program
 * card. The screen gets two callbacks and stays a screen.
 *
 * ── The user is told what happened, always ──────────────────────────────────────
 * Every path ends in a sentence: saved, shared, imported, or refused and why. A silent
 * failure here is the worst possible one — the whole point of an export is believing you
 * have a copy, and "nothing appeared to go wrong" is not the same as having one.
 */
class ProgramTransfer internal constructor(
    /** Offers to save or send these programs. Does nothing when the list is empty. */
    val export: (List<WorkoutProgram>) -> Unit,
    /** Opens the system file picker and reads programs out of what comes back. */
    val import: () -> Unit,
)

@Composable
fun rememberProgramTransfer(onImported: (List<WorkoutProgram>) -> Unit): ProgramTransfer {
    val context = LocalContext.current
    val today = remember { LocalDate.now().toString() }

    var pending by remember { mutableStateOf<List<WorkoutProgram>>(emptyList()) }
    var choosing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val save = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PROGRAM_FILE_MIME)
    ) { uri ->
        // a null uri is the user backing out of the picker, which needs no report
        if (uri != null) {
            val text = writeProgramFile(pending, today)
            val failure = ProgramFiles.write(context, uri, text)
            message = failure ?: "Saved ${countOf(pending.size)}."
        }
    }

    val open = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) message = importFrom(context, uri, onImported)
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Export ${countOf(pending.size)}") },
            text = {
                Text(
                    "Saving writes a JSON file wherever you choose - a folder, a memory " +
                        "card, a cloud app. Sharing hands the same file to another app to " +
                        "send. Either way it can be imported back here later."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    choosing = false
                    save.launch(ProgramFiles.suggestedName(pending, today))
                }) { Text("Save to a file") }
            },
            dismissButton = {
                TextButton(onClick = {
                    choosing = false
                    val failure = ProgramFiles.share(
                        context = context,
                        text = writeProgramFile(pending, today),
                        fileName = ProgramFiles.suggestedName(pending, today),
                        title = "Send the programs",
                    )
                    if (failure != null) message = failure
                }) { Text("Share") }
            },
        )
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Programs") },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }

    return remember(save, open) {
        ProgramTransfer(
            export = { programs ->
                if (programs.isNotEmpty()) {
                    pending = programs
                    choosing = true
                }
            },
            import = {
                // any type, rather than a filter on application/json: a .json file arrives
                // from half the file managers and cloud apps labelled
                // application/octet-stream or text/plain, and a picker filtered to the
                // correct type shows the user's own export greyed out. Nothing about the
                // contents is trusted in any case - readProgramFile validates what comes back
                open.launch(arrayOf(ANY_MIME))
            },
        )
    }
}

/** What the file picker is opened with; see the note at the call site. */
private const val ANY_MIME = "*/*"

/** Reads a picked document and hands over what it held. Returns the line to show. */
private fun importFrom(
    context: Context,
    uri: Uri,
    onImported: (List<WorkoutProgram>) -> Unit,
): String = when (val read = ProgramFiles.read(context, uri)) {
    is ProgramFiles.FileText.Failed -> read.reason
    is ProgramFiles.FileText.Ok -> when (val parsed = readProgramFile(read.text)) {
        is ProgramImport.Rejected -> parsed.reason
        is ProgramImport.Loaded -> {
            onImported(parsed.programs)
            "Imported ${countOf(parsed.programs.size)}. A name that was already taken was " +
                "kept apart with a mark - nothing you had was replaced."
        }
    }
}

private fun countOf(n: Int): String = if (n == 1) "1 program" else "$n programs"
