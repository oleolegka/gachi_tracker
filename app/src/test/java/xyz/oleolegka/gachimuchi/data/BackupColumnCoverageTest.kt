package xyz.oleolegka.gachimuchi.data

import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.oleolegka.gachimuchi.data.db.EventEntity
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.db.ProgramBlockEntity
import xyz.oleolegka.gachimuchi.data.db.ProgramEntity
import xyz.oleolegka.gachimuchi.data.db.ProgramGroupEntity
import xyz.oleolegka.gachimuchi.data.db.SlotEntity
import xyz.oleolegka.gachimuchi.data.db.SlotExerciseEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.PortableEvent
import xyz.oleolegka.gachimuchi.domain.PortableExercise
import xyz.oleolegka.gachimuchi.domain.PortablePlannedExercise
import xyz.oleolegka.gachimuchi.domain.PortableProgramRow
import xyz.oleolegka.gachimuchi.domain.PortableSlot
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup

/**
 * MAKING IT IMPOSSIBLE TO FORGET A COLUMN IN THE BACKUP FORMAT — quietly, at least.
 *
 * ── The defect, and why it kept happening ───────────────────────────────────────
 * backlog.md §14.2: "the catalog, the plan and the programs are written out column by column.
 * A new column not added to the format is restored as its default, silently. It happened twice
 * in one day." The mechanics are always the same three steps, and each of them compiles:
 *
 * 1. a column is added to a Room entity;
 * 2. the portable type the backup carries that row in is not given a matching field;
 * 3. nothing fails — every field of every portable type has a DEFAULT, precisely so that a
 *    file written before a column existed still reads, so the missing value arrives as that
 *    default and the restored row is quietly wrong about one thing.
 *
 * Steps 1 and 2 are the ones a person controls, and no amount of care makes them reliable:
 * the two files are far apart, the compiler is happy, and the round-trip tests pass because
 * a fixture written on the same day does not set the new column either.
 *
 * ── The mechanism ───────────────────────────────────────────────────────────────
 * Three tests, and the point of each is that a NEW COLUMN CANNOT BE ADDED WITHOUT ONE OF THEM
 * TURNING RED:
 *
 * - [every column of every backed-up table has a place in the file format] is a CENSUS. For
 *   every table in [AppDatabase] it compares the entity's fields, by name, against the portable
 *   type the backup carries it in. A column is either carried or listed in [Carried.excluded]
 *   with a reason written into this file — those are the only two outcomes, and the second one
 *   is a decision somebody had to type.
 * - [the census covers every table the database has] closes the other half: adding a whole
 *   TABLE to the schema and no line to the census would otherwise leave it unwatched. The list
 *   of tables is read off Room's own `@Database` annotation, not restated here.
 * - [a fully populated catalog row survives both mappers with nothing left at its default] is
 *   the VALUE half, and it is what a census alone cannot do: a field can exist on both sides
 *   and still be dropped by a mapper. It runs a row in which every carried column differs from
 *   a fresh row's default through [toPortable] and [toEntity] — the app's own two mappers, not
 *   a copy — and demands it come back identical. The fixture's own completeness is asserted
 *   first, so a column added tomorrow and left out of the fixture fails there rather than
 *   passing by comparing two defaults.
 *
 * ── What this does not do ───────────────────────────────────────────────────────
 * It does not touch the file's SHAPE, so every backup already written by the owner still
 * reads: the CSV columns, the version marker and the payload JSON are exactly what they were.
 * It also does not make a forgotten column impossible — it makes it LOUD. Somebody can still
 * add a name to [Carried.excluded] to get a red test green; what they cannot do is add a
 * column and never be asked about it.
 *
 * The value half currently covers the CATALOG only. The plan and the programs are mapped
 * across three tables with list positions rebuilt from indices, which does not reduce to one
 * pure function per row the way the catalog now does; their columns are covered by the census
 * and by `JournalBackupTest`'s export-restore-export text comparison, which is a weaker
 * guarantee, and closing that gap is left as its own piece of work.
 *
 * ── Why reflection, and why plain [Class], not `kotlin-reflect` ─────────────────
 * `kotlin-reflect` is not a dependency of this module, and pulling it in for one test is not
 * worth the jar. A Kotlin data class compiles each constructor `val` to a private instance
 * field of the same name, so comparing [Class.getDeclaredFields] names is exactly the
 * structural check this needs, with nothing extra to add to the build.
 */
class BackupColumnCoverageTest {

    /**
     * One table of the database and the type the backup carries its rows in.
     *
     * [excluded] is the list of entity fields that deliberately do not travel. Every entry
     * needs a reason in [why] — the point of the exclusion being explicit is that adding one
     * is a decision, not a way of making a test pass.
     */
    private data class Carried(
        val entity: Class<*>,
        val portable: Class<*>,
        val excluded: Set<String>,
        val why: String,
    )

    /**
     * LOCAL PLUMBING, excluded everywhere it appears: `id` is a count of how many rows THIS
     * phone has written and `spaceId` is the single local profile. Both are meaningless on any
     * other device, which is why every link in the file is said in uids (see
     * domain/JournalTransfer.kt).
     */
    private val localPlumbing = setOf("id", "spaceId")

    private val carried = listOf(
        Carried(
            entity = EventEntity::class.java,
            portable = PortableEvent::class.java,
            excluded = localPlumbing + setOf("workoutId", "opDate", "tsUtc"),
            why = "workoutId is a row number and workoutUid says the same thing portably; " +
                "opDate is read back out of the payload on restore and tsUtc is ts resolved " +
                "against tzOffsetMin - both are arithmetic over columns that do travel, and " +
                "storing them again would be storing a second, staleable copy",
        ),
        Carried(
            entity = ExerciseEntity::class.java,
            portable = PortableExercise::class.java,
            excluded = localPlumbing + setOf("identityKey", "protocolProgramId", "pictureId"),
            why = "identityKey is derived from name, form and the protocol program's uid and " +
                "is recomputed by every reader; protocolProgramId is a local row number, " +
                "carried as protocolProgramUid instead and resolved on both sides; pictureId " +
                "names a FILE in ExercisePictureStore, which no CSV can carry - a real, " +
                "deliberate loss, named here so it cannot be undone by accident",
        ),
        Carried(
            entity = SlotEntity::class.java,
            portable = PortableSlot::class.java,
            excluded = localPlumbing,
            why = "nothing else about a plan slot is local",
        ),
        Carried(
            entity = SlotExerciseEntity::class.java,
            portable = PortablePlannedExercise::class.java,
            excluded = setOf("id", "slotId", "exerciseId", "position"),
            why = "a planned line travels NESTED inside its slot's payload, so the slot is its " +
                "own parent and the list index is its own position; exerciseId is a row " +
                "number, carried as exerciseUid",
        ),
        Carried(
            entity = ProgramEntity::class.java,
            portable = PortableProgramRow::class.java,
            excluded = localPlumbing + setOf("exerciseId"),
            why = "exerciseId is a row number, carried as exerciseUid",
        ),
        Carried(
            entity = ProgramGroupEntity::class.java,
            portable = ProgramGroup::class.java,
            excluded = setOf("id", "programId", "position"),
            why = "a group travels nested inside its program's payload, so the program is its " +
                "own parent and the list index is its own position",
        ),
        Carried(
            entity = ProgramBlockEntity::class.java,
            portable = ProgramBlock::class.java,
            excluded = setOf("id", "groupId", "position"),
            why = "a block travels nested inside its group, for the same reason a group does",
        ),
    )

    private fun instanceFieldNames(clazz: Class<*>): Set<String> =
        clazz.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapTo(HashSet()) { it.name }

    @Test
    fun `every column of every backed-up table has a place in the file format`() {
        for (table in carried) {
            val entityFields = instanceFieldNames(table.entity) - table.excluded
            val missing = entityFields - instanceFieldNames(table.portable)
            assertTrue(
                "${table.entity.simpleName} has column(s) the journal backup does not carry: " +
                    "$missing. Add them to ${table.portable.simpleName} " +
                    "(domain/JournalTransfer.kt) in the same change, or name them in this " +
                    "test's exclusion list with a reason - a portable type's fields all " +
                    "default, so a column left out comes back as its default on every future " +
                    "restore, silently. Already excluded here: ${table.why}.",
                missing.isEmpty(),
            )
        }
    }

    /**
     * A new TABLE is a new way to forget a column, so the census is checked against the
     * database's own declaration rather than against itself.
     *
     * ── Why it reads the source file instead of the annotation ──────────────────
     * Room's `@Database` is declared `@Retention(BINARY)`, so `entities = [...]` is not there
     * to be reflected on at runtime — `getAnnotation` returns null. The declaration is still
     * the honest source of truth, so it is read where it exists: in the file. If the file
     * cannot be found the test FAILS rather than passing empty-handed, which is the whole
     * point of the exercise.
     */
    @Test
    fun `the census covers every table the database has`() {
        val declared = declaredEntityNames()
        val watched = carried.mapTo(HashSet()) { it.entity.simpleName }

        assertEquals(
            "A table was added to AppDatabase and not to this test's census, so nothing is " +
                "watching whether its columns reach a backup. Add a Carried(...) line for it, " +
                "or a line saying the table is deliberately not backed up.",
            emptySet<String>(),
            declared - watched,
        )
        assertEquals(
            "This test's census names a table the database does not have any more.",
            emptySet<String>(),
            watched - declared,
        )
    }

    /** The `entities = [...]` list of [AppDatabase], read off the declaration itself. */
    private fun declaredEntityNames(): Set<String> {
        val path = "src/main/java/xyz/oleolegka/gachimuchi/data/db/AppDatabase.kt"
        val file = listOf(path, "app/$path", "../app/$path").map(::File).firstOrNull { it.isFile }
        checkNotNull(file) {
            "AppDatabase.kt was not found from ${File(".").absolutePath}, so the list of tables " +
                "could not be read. Fix the path in this test - do not delete the check."
        }
        val list = Regex("""entities\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(file.readText())
        checkNotNull(list) { "AppDatabase.kt no longer declares entities = [...] in a shape this test can read." }
        return Regex("""(\w+)::class""").findAll(list.groupValues[1])
            .mapTo(HashSet()) { it.groupValues[1] }
    }

    // --- the value half: the catalog through both mappers -------------------------------

    /** The uid of the protocol program the fixture points at, on both sides of the trip. */
    private val programUid = "program-uid-1"

    /**
     * A catalog row with EVERY carried column set to something a fresh row would not have.
     *
     * Built by naming every argument, so that a column added to [ExerciseEntity] tomorrow is
     * left at its default here — which is exactly what the first assertion below catches.
     */
    private val populated = ExerciseEntity(
        name = "One-arm hang",
        form = ExerciseForm.HOLD.code,
        createdAt = "2026-08-01T10:00:00",
        protocolProgramId = 7L,
        defaultRestSec = 240,
        ledByProtocol = false,
        uid = "exercise-uid-1",
        oneSided = true,
        bodyweightShare = 0.65,
        hidden = true,
        pictureId = "picture-1",
    )

    /**
     * The same class with only its non-defaultable arguments given, and those given values
     * [populated] does not use. Every OTHER field on it is therefore the default a restore
     * would fall back to, which is what makes it the right thing to compare a fixture against.
     */
    private val bare = ExerciseEntity(name = "", form = 0, createdAt = "")

    private fun valuesOf(row: ExerciseEntity): Map<String, Any?> =
        ExerciseEntity::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .associate { field: Field ->
                field.isAccessible = true
                field.name to field.get(row)
            }

    private val excludedFromTheCatalog: Set<String> =
        carried.single { it.entity == ExerciseEntity::class.java }.excluded

    @Test
    fun `a fully populated catalog row survives both mappers with nothing left at its default`() {
        val fixture = valuesOf(populated)
        val defaults = valuesOf(bare)

        // FIRST: the fixture is worth running. A column added to the entity and not to the
        // fixture above sits at its default, and a round trip over a default proves nothing -
        // it would come back "correct" precisely because nothing carried it.
        val untouched = fixture.keys
            .filterNot { it in excludedFromTheCatalog }
            .filter { fixture[it] == defaults[it] }
        assertTrue(
            "The fixture in this test leaves column(s) $untouched at the value a fresh row " +
                "has, so the round trip below cannot tell whether the backup carries them. " +
                "Give them a value here (and check they are carried at all).",
            untouched.isEmpty(),
        )

        // THEN the trip itself, through the app's own two mappers and nothing local
        val returned = valuesOf(populated.toPortable(programUid).toEntity("2026-01-01T00:00:00"))

        val lost = fixture.keys
            .filterNot { it in excludedFromTheCatalog }
            .filter { returned[it] != fixture[it] }
        assertTrue(
            "Column(s) $lost did not survive export and restore: the field exists on " +
                "PortableExercise but one of the two mappers (data/CatalogMapping.kt's " +
                "toPortable / toEntity) does not carry it, so a restore hands back the " +
                "default. Expected " +
                "${lost.associateWith { fixture[it] }}, got ${lost.associateWith { returned[it] }}.",
            lost.isEmpty(),
        )
    }

    /**
     * The two columns the catalog deliberately drops are dropped, and the identity is REBUILT
     * rather than carried. Stated as a test so the exclusions above are a promise about
     * behaviour and not only a note.
     */
    @Test
    fun `the excluded catalog columns behave the way the exclusion list says`() {
        val restored = populated.toPortable(programUid).toEntity("2026-01-01T00:00:00")

        assertEquals("a picture is a file and cannot travel in a CSV", null, restored.pictureId)
        assertEquals(
            "the local program row number cannot be known until the programs section is in",
            null,
            restored.protocolProgramId,
        )
        // recomputed from name, form and the program's UID - the one thing about the protocol
        // that means anything on another phone
        assertEquals(
            xyz.oleolegka.gachimuchi.domain.exerciseIdentityKey(
                populated.name, populated.form, programUid,
            ),
            restored.identityKey,
        )
    }
}
