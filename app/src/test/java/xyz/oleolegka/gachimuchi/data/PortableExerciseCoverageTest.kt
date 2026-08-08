package xyz.oleolegka.gachimuchi.data

import java.lang.reflect.Modifier
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.domain.PortableExercise

/**
 * Guards the one obligation domain/JournalTransfer.kt states in words: a column added to
 * [ExerciseEntity] and not added to [PortableExercise] is a column that does not survive a
 * restore, silently, because every field on [PortableExercise] has a default.
 *
 * ── Why reflection, and why plain [Class], not `kotlin-reflect` ─────────────────
 * `kotlin-reflect` is not a dependency of this module, and pulling it in for one test is not
 * worth the jar. A Kotlin data class compiles each constructor `val` to a private instance
 * field of the same name, so comparing [Class.getDeclaredFields] names is exactly the
 * structural check this test needs, with nothing extra to add to the build.
 *
 * ── What is deliberately excluded ────────────────────────────────────────────────
 * [id] and `spaceId` are local plumbing (domain/Catalog.kt's [xyz.oleolegka.gachimuchi.domain.CatalogRow]
 * explains why): a row number and a space id mean nothing off the phone that assigned them, and
 * the backup refers to everything by [ExerciseEntity.uid] instead. `identityKey` is derived —
 * from [ExerciseEntity.name], [ExerciseEntity.form] and the protocol program's uid — and is
 * recomputed by every reader rather than trusted from one, so a portable form carrying it would
 * be a second, possibly stale, copy of the truth.
 *
 * `protocolProgramId` (schema version 19) is excluded for the same reason [id] is, one level
 * down: it is a LOCAL row number of the `programs` table and means nothing off this phone. It
 * genuinely does have a place on [PortableExercise] — `protocolProgramUid`, spelled differently
 * on purpose, because it carries the program's portable uid rather than its local id. A
 * name-for-name reflection check cannot see that the two are the same column resolved into a
 * different shape, which is exactly the reason [ExerciseEntity.toPortable] resolves it rather
 * than this test asserting on it directly; see JournalBackupTest's round-trip for the actual
 * proof that the link survives.
 *
 * This test does NOT check that the value round-trips correctly, only that the field exists on
 * both sides — see JournalBackupTest and JournalTransferTest for the round-trip itself. What it
 * catches is the narrower, actually-recurring defect: a column that a mapper never mentions at
 * all.
 */
class PortableExerciseCoverageTest {

    private val excludedEntityFields = setOf("id", "spaceId", "identityKey", "protocolProgramId")

    private fun instanceFieldNames(clazz: Class<*>): Set<String> =
        clazz.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .mapTo(HashSet()) { it.name }

    @Test
    fun `every catalog column ExerciseEntity carries has a place on PortableExercise`() {
        val entityFields = instanceFieldNames(ExerciseEntity::class.java) - excludedEntityFields
        val portableFields = instanceFieldNames(PortableExercise::class.java)

        val missing = entityFields - portableFields
        assertTrue(
            "ExerciseEntity has column(s) the journal backup format does not carry: $missing. " +
                "A column added to the exercises table has to be added to PortableExercise " +
                "(domain/JournalTransfer.kt) in the same change, or it is restored empty on " +
                "every future backup, silently, because PortableExercise's fields all default. " +
                "See domain/Catalog.kt's CatalogRow for the single place every reader now goes " +
                "through instead.",
            missing.isEmpty(),
        )
    }
}
