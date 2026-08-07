package xyz.oleolegka.gachimuchi.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import xyz.oleolegka.gachimuchi.domain.PROGRAM_FILE_MIME
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The Android half of exporting programs: bytes in and out of a document the user picked,
 * and a copy handed to another app to send.
 *
 * ── No storage permission, on purpose ───────────────────────────────────────────
 * Everything here goes through the Storage Access Framework: the system file picker hands
 * back a [Uri] the app may read or write ONCE, for that one document. The alternative —
 * READ/WRITE_EXTERNAL_STORAGE and a path — would mean asking for the right to read every
 * file on the phone in order to write one. On the phone this app is built for, that is a
 * reason to uninstall it, and the manifest's list of permissions is meant to stay
 * defensible line by line.
 *
 * ── Failures come back as sentences ─────────────────────────────────────────────
 * A picked document can be gone, read-only, on an unmounted card, or handed over by a
 * provider that then dies. None of that is exceptional enough to crash on and none of it
 * is worth a stack trace: every call here returns either null (it worked) or a line to
 * show the user.
 */
object ProgramFiles {

    /**
     * Nothing bigger than this is read. A program file is a few kilobytes; a hundred of
     * them is still under a hundred. The cap exists because the picker can return ANY
     * document — a two-gigabyte video, picked by mistake through "all files" — and reading
     * it into a String would take the app down with an out-of-memory error before the JSON
     * parser ever got a chance to reject it.
     */
    private const val MAX_BYTES = 1_000_000

    private const val BUFFER_BYTES = 8 * 1024

    /** Must match the provider authority declared in the manifest. */
    private const val AUTHORITY_SUFFIX = ".files"

    /** The cache subdirectory a shared copy is written to. Cleared by the system at will. */
    private const val SHARE_DIR = "exports"

    /** What came out of a document. */
    sealed interface FileText {
        data class Ok(val text: String) : FileText
        data class Failed(val reason: String) : FileText
    }

    /**
     * The name to suggest in the save dialog: the program's own name when there is exactly
     * one, otherwise a dated bundle. Anything the file system might object to is replaced
     * rather than dropped, so two programs cannot collapse into the same suggestion.
     */
    fun suggestedName(programs: List<WorkoutProgram>, date: String): String {
        val stem = if (programs.size == 1) {
            sanitize(programs.single().name)
        } else {
            "gachimuchi-programs-$date"
        }
        return "${stem.ifBlank { "programs" }}.json"
    }

    /** A cap as a person reads it: "1000 kB" is a number to count the zeroes of, "32 MB" is not. */
    private fun sizeOf(bytes: Int): String =
        if (bytes >= 1_000_000) "${bytes / 1_000_000} MB" else "${bytes / 1000} kB"

    private fun sanitize(name: String): String = name.trim()
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
        .joinToString("")
        .replace(Regex("-+"), "-")
        .trim('-')
        .take(64)

    /** Writes [text] into a document the user picked. Returns null on success. */
    fun write(context: Context, uri: Uri, text: String): String? = runCatching {
        // "wt" truncates: without it, writing a shorter export over a longer file leaves the
        // tail of the old one behind and produces JSON with trailing garbage
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
            ?: return "The file could not be opened for writing."
        null
    }.getOrElse { "The file could not be written: ${it.javaClass.simpleName}." }

    /**
     * Reads a document the user picked, refusing anything implausibly large.
     *
     * The cap is applied WHILE reading rather than to the finished result — a size read
     * from the provider beforehand can be a lie or absent, and by the time an oversized
     * file is in memory the damage is done.
     *
     * [maxBytes] and [what] are parameters because this reader is used for the journal backup
     * as well (data/JournalBackup.kt), and a backup of years of training is legitimately
     * megabytes: the program cap would refuse the very file it is most important to be able to
     * read. Everything else about the two reads is identical, and one streaming cap that is
     * right is better than two.
     */
    fun read(
        context: Context,
        uri: Uri,
        maxBytes: Int = MAX_BYTES,
        what: String = "program export",
    ): FileText = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val collected = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var oversized = false
            while (!oversized) {
                val read = stream.read(buffer)
                if (read < 0) break
                collected.write(buffer, 0, read)
                oversized = collected.size() > maxBytes
            }
            if (oversized) {
                FileText.Failed("That file is larger than ${sizeOf(maxBytes)}, which no $what is. It was not read.")
            } else {
                FileText.Ok(collected.toString(Charsets.UTF_8.name()))
            }
        } ?: FileText.Failed("The file could not be opened.")
    }.getOrElse { FileText.Failed("The file could not be read: ${it.javaClass.simpleName}.") }

    /**
     * Hands a copy to whatever app the user picks (a messenger, a mail client, a cloud
     * folder). The copy goes into the cache and is exposed through a FileProvider: a
     * `file://` URI has been illegal to share since Android 7, and the provider grants read
     * access to this one file for this one send instead.
     */
    fun share(context: Context, text: String, fileName: String, title: String): String? =
        runCatching {
            val dir = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
            val file = File(dir, fileName)
            file.writeText(text)
            val uri = FileProvider.getUriForFile(
                context, context.packageName + AUTHORITY_SUFFIX, file,
            )
            val send = Intent(Intent.ACTION_SEND)
                .setType(PROGRAM_FILE_MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, fileName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, title))
            null
        }.getOrElse { "Nothing on this phone offered to send the file (${it.javaClass.simpleName})." }
}
