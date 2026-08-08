package xyz.oleolegka.gachimuchi.data

import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.CatalogExercise
import xyz.oleolegka.gachimuchi.domain.CatalogRow
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSibling
import xyz.oleolegka.gachimuchi.domain.PortableExercise
import xyz.oleolegka.gachimuchi.domain.toCatalogExercise
import xyz.oleolegka.gachimuchi.domain.toHoldSibling
import xyz.oleolegka.gachimuchi.domain.toPortable
import xyz.oleolegka.gachimuchi.domain.toRef

/**
 * Every screen's and every file's view of a catalog row, built off ONE reading of the entity.
 *
 * ── Why this used to be four mappers, and what that cost ────────────────────────
 * [ExerciseEntity] carries thirteen columns. Four independent places each turned a row into a
 * narrower domain type and each remembered a different subset: `toRef` (ten columns, no
 * body-weight share), `toCatalog` (six, with it), `toPortable` (twelve), `toSibling` (five —
 * one of them living in a screen file, describing how the table is read from inside the UI
 * layer). A column added to the entity had to be added to all four separately, and every
 * field on every one of the four types defaults to something, so a mapper that forgot a column
 * compiled, ran, and quietly answered the old question with the old data. That happened twice
 * in three days.
 *
 * Now [ExerciseEntity.toCatalogRow] is the only function in the app that reads the entity's
 * columns, and it reads all of them into [CatalogRow]. The four functions below are thin,
 * total mappings off that one value — see domain/Catalog.kt for [toRef], [toCatalogExercise]
 * and [toHoldSibling], and domain/JournalTransfer.kt for [toPortable] — so a column landing on
 * [CatalogRow] is a column every view already has, and the one place left to forget it is here.
 */
fun ExerciseEntity.toCatalogRow(): CatalogRow = CatalogRow(
    id = id,
    uid = uid,
    name = name,
    form = form,
    createdAt = createdAt,
    edgeMm = edgeMm,
    protocolWorkSec = protocolWorkSec,
    protocolRestSec = protocolRestSec,
    defaultRestSec = defaultRestSec,
    ledByProtocol = ledByProtocol,
    oneSided = oneSided,
    bodyweightShare = bodyweightShare,
    hidden = hidden,
)

/** The catalog row as the domain sees it — see [ExerciseRef]. */
fun ExerciseEntity.toRef(): ExerciseRef = toCatalogRow().toRef()

/** Catalog row -> what the dashboard needs; an unreadable form code drops out of the feed. */
fun ExerciseEntity.toCatalog(): CatalogExercise? = toCatalogRow().toCatalogExercise()

/** Catalog row -> the sibling record the §12-A switcher is built from. */
fun ExerciseEntity.toSibling(): HoldSibling = toCatalogRow().toHoldSibling()

/** Catalog row -> what a journal backup carries for it. */
fun ExerciseEntity.toPortable(): PortableExercise = toCatalogRow().toPortable()
