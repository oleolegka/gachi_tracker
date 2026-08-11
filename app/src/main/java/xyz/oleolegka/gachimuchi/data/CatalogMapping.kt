package xyz.oleolegka.gachimuchi.data

import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.CatalogExercise
import xyz.oleolegka.gachimuchi.domain.CatalogRow
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.PortableExercise
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.exerciseIdentityKey
import xyz.oleolegka.gachimuchi.domain.toCatalogExercise
import xyz.oleolegka.gachimuchi.domain.toPortable
import xyz.oleolegka.gachimuchi.domain.toRef

/**
 * Every screen's and every file's view of a catalog row, built off ONE reading of the entity.
 *
 * ── Why this used to be four mappers, and what that cost ────────────────────────
 * [ExerciseEntity] carried thirteen columns. Independent places each turned a row into a
 * narrower domain type and each remembered a different subset: `toRef` (ten columns, no
 * body-weight share), `toCatalog` (six, with it), `toPortable` (twelve), and — until the §12-A
 * hold-sibling switcher was removed along with the edge attribute it compared — a `toSibling`
 * living in a screen file, describing how the table is read from inside the UI layer. A column
 * added to the entity had to be added to every one of them separately, and every field on every
 * one of those types defaults to something, so a mapper that forgot a column compiled, ran, and
 * quietly answered the old question with the old data. That happened twice in three days.
 *
 * Now [ExerciseEntity.toCatalogRow] is the only function in the app that reads the entity's
 * columns, and it reads all of them into [CatalogRow]. The functions below are thin, total
 * mappings off that one value — see domain/Catalog.kt for [toRef] and [toCatalogExercise], and
 * domain/JournalTransfer.kt for [toPortable] — so a column landing on [CatalogRow] is a column
 * every view already has, and the one place left to forget it is here.
 */
fun ExerciseEntity.toCatalogRow(): CatalogRow = CatalogRow(
    id = id,
    uid = uid,
    name = name,
    form = form,
    createdAt = createdAt,
    protocolProgramId = protocolProgramId,
    defaultRestSec = defaultRestSec,
    ledByProtocol = ledByProtocol,
    oneSided = oneSided,
    bodyweightShare = bodyweightShare,
    hidden = hidden,
    pictureId = pictureId,
)

/**
 * The catalog row as the domain sees it — see [ExerciseRef].
 *
 * [program] is the resolved library program [ExerciseEntity.protocolProgramId] points at, or
 * null for no protocol — the caller resolves it (see [CatalogRow.toRef]'s own KDoc for why this
 * function does not reach for a database itself).
 */
fun ExerciseEntity.toRef(program: WorkoutProgram? = null): ExerciseRef = toCatalogRow().toRef(program)

/** Catalog row -> what the dashboard needs; an unreadable form code drops out of the feed. */
fun ExerciseEntity.toCatalog(): CatalogExercise? = toCatalogRow().toCatalogExercise()

/**
 * Catalog row -> what a journal backup carries for it.
 *
 * [protocolProgramUid] is the resolved uid of [ExerciseEntity.protocolProgramId], or null —
 * resolved by the caller for the same reason [toRef] takes a resolved program rather than a
 * database: this mapper stays a pure function of what it is handed.
 */
fun ExerciseEntity.toPortable(protocolProgramUid: String? = null): PortableExercise =
    toCatalogRow().toPortable(protocolProgramUid)

/**
 * What a journal backup carries for a catalog row -> the row a restore inserts. THE ONE PLACE
 * the import direction of the catalog is spelled out.
 *
 * ── Why it is a named function and not four lines inside the restore ────────────
 * It was those four lines, in `JournalBackup.restore`, and that is the shape of the defect
 * backlog.md §14.2 reports: a column added to [ExerciseEntity] had to be remembered in a
 * constructor call buried in a transaction, and a forgotten one compiled and restored as its
 * default. Out here it is a pure function of a [PortableExercise], so a test can run a fully
 * populated row through both directions and fail when any column comes back different — see
 * `BackupColumnCoverageTest`, which is the other half of this and the reason the extraction
 * was worth doing.
 *
 * Two columns are deliberately not set here and neither is an oversight:
 * - `protocolProgramId` is a LOCAL row number of a program that may still be waiting in the
 *   file's own programs section, so the restore backfills it once that section is written;
 * - `pictureId` names a file the format cannot carry (see [PortableExercise]).
 *
 * [createdAtFallback] stands in for a file that carries no creation time for the row — a
 * restore's own "now", which the caller owns.
 */
fun PortableExercise.toEntity(createdAtFallback: String): ExerciseEntity = ExerciseEntity(
    name = name,
    form = form,
    createdAt = createdAt.ifBlank { createdAtFallback },
    defaultRestSec = defaultRestSec,
    ledByProtocol = ledByProtocol,
    uid = uid,
    oneSided = oneSided,
    bodyweightShare = bodyweightShare,
    hidden = hidden,
    // keyed on the uid STRING the file already carries, not on a local id, so it needs no
    // wait for the programs section the way protocolProgramId does
    identityKey = exerciseIdentityKey(name, form, protocolProgramUid),
)
