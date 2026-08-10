package xyz.oleolegka.gachimuchi.data

import android.content.Context
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.EventEntity
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.db.ProgramBlockEntity
import xyz.oleolegka.gachimuchi.data.db.ProgramEntity
import xyz.oleolegka.gachimuchi.data.db.ProgramGroupEntity
import xyz.oleolegka.gachimuchi.data.db.SlotEntity
import xyz.oleolegka.gachimuchi.data.db.SlotExerciseEntity
import xyz.oleolegka.gachimuchi.domain.CelebrationMode
import xyz.oleolegka.gachimuchi.domain.ImportReport
import xyz.oleolegka.gachimuchi.domain.JournalFile
import xyz.oleolegka.gachimuchi.domain.PortableEvent
import xyz.oleolegka.gachimuchi.domain.PortablePlannedExercise
import xyz.oleolegka.gachimuchi.domain.PortableProgramRow
import xyz.oleolegka.gachimuchi.domain.PortableSettings
import xyz.oleolegka.gachimuchi.domain.PortableSlot
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.exerciseIdentityKey
import xyz.oleolegka.gachimuchi.domain.mergeExercises
import xyz.oleolegka.gachimuchi.domain.portableSettings
import xyz.oleolegka.gachimuchi.domain.toTimerSettings
import xyz.oleolegka.gachimuchi.domain.uniqueProgramName
import xyz.oleolegka.gachimuchi.domain.writeJournalFile
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The database half of the backup: the journal out of Room into a file, and a file back into
 * Room without writing anything twice.
 *
 * The format itself — one CSV table, everything the app holds as either a journal row or a
 * whole reference row — and every judgement about what a valid file is, lives in
 * domain/JournalTransfer.kt. What is here is the part that needs Room: which tables are read,
 * in which order rows are written back, and how the one link the schema still keeps as a row
 * number (`slot_exercises.exercise_id`) is translated into a uid on the way out and back on
 * the way in.
 *
 * ── Restoring appends; it never edits and never deletes ─────────────────────────
 * Nothing in here updates or removes a stored row. A backup is merged INTO whatever is on the
 * phone: rows the file has and the phone does not are inserted, rows both have are left alone,
 * and rows the phone has and the file does not are none of the file's business. That is the
 * only rule under which importing the same file twice is safe, and importing a backup twice
 * by accident — into a phone somebody is not sure they already restored to — is precisely the
 * situation this is built for.
 *
 * The exception, stated because it is the one thing a user could be surprised by: the
 * SETTINGS section is applied over the local preferences when the file carries one. Nine
 * switches are not history, they are the answer to "how was this set up", and a restore that
 * left the phone's defaults in place would not have restored the setup.
 */
class JournalBackup(
    private val db: AppDatabase,
    /** Where the preferences come from and go to; null leaves them out of the file entirely. */
    private val settings: BackupSettings? = null,
) {

    private fun now(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

    // --- out ---------------------------------------------------------------------------

    /**
     * The whole journal as file text: the journal itself, EVERY row of it (live and dead —
     * see domain/JournalTransfer.kt's `current_version` column for how a reader tells them
     * apart), plus the catalog, the plan, the programs and the settings, each carried whole.
     */
    suspend fun export(exportedAt: String = "", deviceId: String = ""): String {
        val exercises = db.exercises().all()
        val uidOfExercise = exercises.associate { it.id to it.uid }
        // the reverse of programsOut's own exercise lookup: an exercise names its protocol
        // program by uid the same way a program names its exercise by uid
        val uidOfProgram = db.programs().allPrograms().associate { it.id to it.uid }

        return writeJournalFile(
            events = db.events().all().map { it.toJournalEvent() },
            catalog = exercises.map { it.toCatalogRow() },
            exercises = exercises.map { it.toPortable(it.protocolProgramId?.let(uidOfProgram::get)) },
            slots = slotsOut(uidOfExercise),
            programs = programsOut(uidOfExercise),
            settings = settings?.read(),
            exportedAt = exportedAt,
            deviceId = deviceId,
        )
    }

    private suspend fun slotsOut(uidOfExercise: Map<Long, String>): List<PortableSlot> {
        val composition = db.slots().allExercises().groupBy { it.slotId }
        return db.slots().all().map { slot ->
            PortableSlot(
                uid = slot.uid,
                name = slot.name,
                atTime = slot.atTime,
                repeatRule = slot.repeatRule,
                anchorDate = slot.anchorDate,
                createdAt = slot.createdAt,
                exercises = composition[slot.id].orEmpty().map { line ->
                    PortablePlannedExercise(
                        uid = line.uid,
                        // a plan may point at a catalog row that has since gone; the line is
                        // written out unresolved rather than dropped, so the file says what the
                        // database says and the import can report it
                        exerciseUid = uidOfExercise[line.exerciseId],
                        restSec = line.restSec,
                    )
                },
            )
        }
    }

    private suspend fun programsOut(uidOfExercise: Map<Long, String>): List<PortableProgramRow> {
        val groups = db.programs().allGroups().groupBy { it.programId }
        val blocks = db.programs().allBlocks().groupBy { it.groupId }
        return db.programs().allPrograms().map { program ->
            PortableProgramRow(
                uid = program.uid,
                name = program.name,
                prepareSec = program.prepareSec,
                category = program.category,
                exerciseUid = program.exerciseId?.let { uidOfExercise[it] },
                position = program.position,
                createdAt = program.createdAt,
                groups = groups[program.id].orEmpty().map { group ->
                    ProgramGroup(
                        name = group.name,
                        repeats = group.repeats,
                        restBetweenRepeatsSec = group.restBetweenRepeatsSec,
                        restAfterSec = group.restAfterSec,
                        blocks = blocks[group.id].orEmpty().map { block ->
                            ProgramBlock(
                                name = block.name,
                                workSec = block.workSec,
                                restSec = block.restSec,
                                repeats = block.repeats,
                            )
                        },
                    )
                },
            )
        }
    }

    // --- in ----------------------------------------------------------------------------

    /**
     * Merges a file that has already been read and accepted by
     * [xyz.oleolegka.gachimuchi.domain.readJournalFile].
     *
     * The order is not arbitrary: the catalog goes first because everything else is
     * translated through it, then the journal (which refers to the catalog only inside its
     * own payloads), then the plan and the programs, which need catalog row numbers that do
     * not exist until the catalog has been written.
     */
    suspend fun restore(file: JournalFile): ImportReport {
        val notes = ArrayList<String>()

        // resolved for the STORED side of the identity comparison too, or every stored
        // exercise would compare as "no protocol" and a genuine duplicate could slip past
        // mergeExercises — see [export] for the same lookup built the other way round
        val uidOfProgram = db.programs().allPrograms().associate { it.id to it.uid }
        val merge = mergeExercises(
            file.exercises,
            db.exercises().all().map { it.toPortable(it.protocolProgramId?.let(uidOfProgram::get)) },
        )
        for (row in merge.toInsert) {
            db.exercises().insert(
                ExerciseEntity(
                    name = row.name,
                    form = row.form,
                    createdAt = row.createdAt.ifBlank { now() },
                    // the local row id of the program this uid names cannot be known yet — the
                    // program itself may still be waiting in file.programs, below — so this is
                    // filled in afterwards by [setProtocolProgramId]; the identity key needs no
                    // such wait, because it is keyed on the uid STRING the file already carries,
                    // not on a local id (see [xyz.oleolegka.gachimuchi.domain.PortableExercise])
                    defaultRestSec = row.defaultRestSec,
                    ledByProtocol = row.ledByProtocol,
                    oneSided = row.oneSided,
                    bodyweightShare = row.bodyweightShare,
                    uid = row.uid,
                    hidden = row.hidden,
                    identityKey = exerciseIdentityKey(row.name, row.form, row.protocolProgramUid),
                )
            )
        }
        if (merge.aliases.isNotEmpty()) {
            notes += "${merge.aliases.size} exercise(s) in the file were the same exercise this " +
                "phone already had under a different key; the key already here was kept. Sets " +
                "imported for them stay in the journal but will not appear in that exercise's " +
                "own records."
        }

        // the catalog, now that it is complete, as the two number-carrying tables need it
        val idOfUid = db.exercises().all().associate { it.uid to it.id }

        val events = restoreEvents(file.events)
        val slots = restoreSlots(file.slots, merge::resolve, idOfUid)
        if (slots.skipped > 0) {
            notes += "${slots.skipped} planned line(s) named an exercise the file does not " +
                "carry, and were left out of the plan."
        }
        val programs = restorePrograms(file.programs, merge::resolve, idOfUid, notes)

        /*
         * The protocol link, backfilled now that the programs section has been written: every
         * program the file names by uid either just landed above or was already here under
         * that uid. A row whose program is in neither case (a hand-edited or inconsistent file)
         * keeps `protocol_program_id = null` — a dangling reference reads as "no protocol",
         * exactly as it does everywhere else this column is read.
         */
        val programIdOfUid = db.programs().allPrograms().associate { it.uid to it.id }
        for (row in merge.toInsert) {
            val programUid = row.protocolProgramUid ?: continue
            val exerciseId = idOfUid[row.uid] ?: continue
            programIdOfUid[programUid]?.let { db.exercises().setProtocolProgramId(exerciseId, it) }
        }

        val carried = file.settings
        val settingsApplied = carried != null && settings != null
        if (carried != null) settings?.write(carried)

        return ImportReport(
            eventsAdded = events.added,
            eventsAlreadyHere = events.alreadyHere,
            exercisesAdded = merge.toInsert.size,
            exercisesAlreadyHere = merge.alreadyHere,
            exercisesMergedByIdentity = merge.aliases.size,
            slotsAdded = slots.added,
            slotsAlreadyHere = slots.alreadyHere,
            plannedLinesSkipped = slots.skipped,
            programsAdded = programs.added,
            programsAlreadyHere = programs.alreadyHere,
            settingsApplied = settingsApplied,
            notes = notes,
        )
    }

    private data class Counted(val added: Int = 0, val alreadyHere: Int = 0, val skipped: Int = 0)

    /**
     * The journal itself: every row the phone does not already hold, in file order.
     *
     * IN FILE ORDER AND IN ONE INSERT, which is worth stating. A uid sorts by the moment it
     * was minted, so a file written by this app is in journal order, and restoring it into an
     * empty database hands out the same row numbers it had before. That is what keeps a
     * `set_cancel` written before schema version 9 — which names its victim by row number and
     * nothing else — pointing at the same set after a full restore.
     *
     * The numeric `workout_id` is deliberately not carried: it is a row number, the uid beside
     * it says the same thing portably, and the rows that have only the number are the ones
     * whose workout is not in this journal at all.
     */
    private suspend fun restoreEvents(events: List<PortableEvent>): Counted {
        val known = db.events().all().mapTo(HashSet()) { it.uid }
        val fresh = events.filter { it.uid !in known }
        if (fresh.isNotEmpty()) {
            db.events().insertAll(
                fresh.map { event ->
                    val payload = event.payload
                    /*
                     * The file now carries the offset the row was written at ([PortableEvent
                     * .tzOffsetMin], schema version 2 of the file format), so a restored row is
                     * resolved against THAT offset, wherever the restore itself happens to run —
                     * a journal exported abroad and restored at home keeps the offset it was
                     * exported with. [WriteTime.of] is handed a [ZonedDateTime] built from the
                     * file's local wall clock at a FIXED offset (not a named zone, which could
                     * mean a different UTC delta on a different day): that reproduces the file's
                     * own offset exactly rather than re-deriving it from a device.
                     *
                     * A file with no offset (v1, written before this column existed, or a row
                     * whose own `ts` never resolved to one) has nothing else to go on, so this
                     * falls back to THE ZONE OF THE DEVICE DOING THE RESTORE, exactly as the 15
                     * -> 16 migration resolves the rows already on the phone, and with the same
                     * caveat: a v1 journal exported abroad and restored at home still gets the
                     * offset of home. That loss is unavoidable for a file the exporting device
                     * never recorded an offset into in the first place; leaving the columns empty
                     * instead would make every restored row unsortable against every row this
                     * phone wrote.
                     */
                    val written = event.tzOffsetMin
                        ?.let { offsetMin ->
                            runCatching {
                                WriteTime.of(LocalDateTime.parse(event.ts).atZone(ZoneOffset.ofTotalSeconds(offsetMin * 60)))
                            }.getOrNull()
                        }
                        ?: WriteTime.ofLocal(event.ts)
                    EventEntity(
                        ts = event.ts,
                        authorId = event.authorId,
                        type = event.type,
                        payload = payload,
                        workoutId = null,
                        uid = event.uid,
                        workoutUid = event.workoutUid,
                        opDate = opDateOfPayload(payload),
                        tsUtc = written?.utc,
                        tzOffsetMin = written?.offsetMin,
                        // carried verbatim from the file rather than re-derived: WHEN THE
                        // TRAINING HAPPENED, as opposed to ts (when the row was written), is an
                        // independent fact this format used to lose silently on every restore —
                        // see PortableEvent's own KDoc
                        occurredTs = event.occurredTs,
                    )
                }
            )
        }
        return Counted(added = fresh.size, alreadyHere = events.size - fresh.size)
    }

    private suspend fun restoreSlots(
        slots: List<PortableSlot>,
        resolve: (String) -> String,
        idOfUid: Map<String, Long>,
    ): Counted {
        val known = db.slots().all().mapTo(HashSet()) { it.uid }
        var added = 0
        var skipped = 0
        for (slot in slots) {
            if (slot.uid in known) continue
            val id = db.slots().insert(
                SlotEntity(
                    name = slot.name,
                    atTime = slot.atTime,
                    repeatRule = slot.repeatRule,
                    anchorDate = slot.anchorDate,
                    createdAt = slot.createdAt.ifBlank { now() },
                    uid = slot.uid,
                )
            )
            added++
            val lines = slot.exercises.mapIndexedNotNull { index, line ->
                val exerciseId = line.exerciseUid?.let { idOfUid[resolve(it)] }
                if (exerciseId == null) {
                    // the column is not nullable and a made-up exercise would be a lie about
                    // what the session is; the line is dropped and counted into the report
                    skipped++
                    null
                } else {
                    SlotExerciseEntity(
                        slotId = id,
                        exerciseId = exerciseId,
                        position = index,
                        restSec = line.restSec,
                        uid = line.uid,
                    )
                }
            }
            if (lines.isNotEmpty()) db.slots().insertExercises(lines)
        }
        return Counted(added = added, alreadyHere = slots.size - added, skipped = skipped)
    }

    /**
     * Programs, matched by uid so that a second restore adds none.
     *
     * A name already taken by a DIFFERENT program is marked rather than merged, exactly as the
     * program file does it (see [uniqueProgramName]): the copy on the phone may have been
     * edited since the backup was taken, and overwriting a hand-tuned protocol with an older
     * one is the single outcome that cannot be undone.
     */
    private suspend fun restorePrograms(
        programs: List<PortableProgramRow>,
        resolve: (String) -> String,
        idOfUid: Map<String, Long>,
        notes: MutableList<String>,
    ): Counted {
        val stored = db.programs().allPrograms()
        val known = stored.mapTo(HashSet()) { it.uid }
        val taken = stored.mapTo(ArrayList()) { it.name }
        var added = 0
        var unlinked = 0
        for (program in programs) {
            if (program.uid in known) continue
            val name = uniqueProgramName(program.name, taken)
            taken += name
            val exerciseId = program.exerciseUid?.let { idOfUid[resolve(it)] }
            if (program.exerciseUid != null && exerciseId == null) unlinked++
            val id = db.programs().insertProgram(
                ProgramEntity(
                    name = name,
                    prepareSec = program.prepareSec,
                    position = program.position,
                    createdAt = program.createdAt.ifBlank { now() },
                    exerciseId = exerciseId,
                    category = program.category.trim(),
                    uid = program.uid,
                )
            )
            added++
            for ((groupIndex, group) in program.groups.withIndex()) {
                val groupId = db.programs().insertGroup(
                    ProgramGroupEntity(
                        programId = id,
                        name = group.name,
                        position = groupIndex,
                        repeats = group.repeats,
                        restBetweenRepeatsSec = group.restBetweenRepeatsSec,
                        restAfterSec = group.restAfterSec,
                    )
                )
                for ((blockIndex, block) in group.blocks.withIndex()) {
                    db.programs().insertBlock(
                        ProgramBlockEntity(
                            groupId = groupId,
                            name = block.name,
                            position = blockIndex,
                            workSec = block.workSec,
                            restSec = block.restSec,
                            repeats = block.repeats,
                        )
                    )
                }
            }
        }
        if (unlinked > 0) {
            notes += "$unlinked program(s) named an exercise the file does not carry; they " +
                "arrived unlinked and will ask which exercise they are the first time they finish."
        }
        return Counted(added = added, alreadyHere = programs.size - added)
    }
}

// ExerciseEntity.toPortable() has moved to data/CatalogMapping.kt, alongside the rest of the
// catalog row's views.

/**
 * Where the preferences in a backup come from and go to.
 *
 * An interface rather than a direct reach for the two stores, because the preferences live in
 * SharedPreferences and the database does not: a test that wants to prove the journal survives
 * a round trip should not have to own a process-wide singleton to do it, and a caller that
 * only wants the tables can pass null.
 */
interface BackupSettings {
    fun read(): PortableSettings
    fun write(settings: PortableSettings)
}

/** The real preferences of this installation: the timer's, and the celebration mode. */
class DeviceBackupSettings(context: Context) : BackupSettings {

    private val app = context.applicationContext
    private val timer = TimerStore(app)
    private val gallery = GalleryStore.get(app)

    override fun read(): PortableSettings = portableSettings(
        timer = timer.settings.value,
        timerEnabled = timer.enabled.value,
        celebration = gallery.mode.value,
    )

    /**
     * The timer is switched on only if the file says so, and never switched off by omission
     * of the field — [PortableSettings] defaults it to false, so a file written before the
     * flag existed would otherwise turn a working timer off on restore.
     */
    override fun write(settings: PortableSettings) {
        timer.update(settings.toTimerSettings())
        if (settings.timerEnabled) timer.setEnabled(true)
        gallery.setMode(CelebrationMode.fromCode(settings.celebrationMode))
    }
}
