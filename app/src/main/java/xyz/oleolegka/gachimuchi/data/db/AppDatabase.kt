package xyz.oleolegka.gachimuchi.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.contentOrNull
import xyz.oleolegka.gachimuchi.domain.newUid

/**
 * The local database (SQLite via Room). The schema repeats the server one (`bot/db.py`):
 * append-only journal + catalog + slots, plus the local-only program tables added in
 * version 2. The server's synonym dictionary was mirrored here too until version 7.
 *
 * There is NO encryption (SQLCipher) here yet, unlike on the server: on the phone the
 * database sits in the app's internal storage, and the key would have to be kept right
 * next to it anyway. If it is ever needed, that is a separate step (SQLCipher for
 * Android, or a Keystore-wrapped key).
 *
 * ── Migrations are written by hand, and fallback is NOT enabled ─────────────────
 * `fallbackToDestructiveMigration` would turn a schema mistake into a wiped training
 * journal on a stranger's phone. The journal is the entire point of the app and there is
 * no backup yet, so a failed migration must crash loudly and be fixed, never "recover" by
 * deleting years of history. Every version bump gets a [Migration] and a test.
 */
@Database(
    entities = [
        EventEntity::class,
        ExerciseEntity::class,
        SlotEntity::class,
        SlotExerciseEntity::class,
        ProgramEntity::class,
        ProgramGroupEntity::class,
        ProgramBlockEntity::class,
    ],
    version = 12,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun exercises(): ExerciseDao
    abstract fun slots(): SlotDao
    abstract fun programs(): ProgramDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Version 1 -> 2: the interval timer's programs.
         *
         * Purely additive — three new tables and their indices, nothing existing is
         * touched. The journal, the catalog, the aliases and the slots come through
         * untouched, which is what the migration test checks by writing rows before the
         * upgrade and reading them back after it.
         *
         * The DDL is spelled out rather than generated because Room validates the result
         * against the entity definitions on the next open: if a column type or an index
         * name here drifts from [ProgramEntity] and friends, the app fails to open the
         * database instead of running on a subtly wrong schema.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `programs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`space_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`prepare_sec` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`created_at` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_programs_space_id_id` " +
                        "ON `programs` (`space_id`, `id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `program_groups` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`program_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`repeats` INTEGER NOT NULL, " +
                        "`rest_between_repeats_sec` INTEGER NOT NULL, " +
                        "`rest_after_sec` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`program_id`) REFERENCES `programs`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_program_groups_program_id` " +
                        "ON `program_groups` (`program_id`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `program_blocks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`group_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`work_sec` INTEGER NOT NULL, " +
                        "`rest_sec` INTEGER NOT NULL, " +
                        "`repeats` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`group_id`) REFERENCES `program_groups`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_program_blocks_group_id` " +
                        "ON `program_blocks` (`group_id`)"
                )
            }
        }

        /**
         * Version 2 -> 3: two columns on `programs` — the optional link to a catalog
         * exercise, and the heading the program is filed under.
         *
         * Additive in effect: every existing program comes through as "not linked, no
         * heading", which is exactly what it was. No foreign key is declared on the link —
         * see [ProgramEntity] for why a hand-written protocol must not be deletable by way of
         * the catalog.
         *
         * ── Why this REBUILDS the table instead of two ALTER statements ─────────────
         * `category` is NOT NULL because the entity declares a non-null String, and SQLite
         * refuses to add a NOT NULL column to a populated table without a DEFAULT. That
         * default is not free: it stays on the column forever, so an upgraded phone ended up
         * with `category TEXT NOT NULL DEFAULT ''` where a fresh install has `category TEXT
         * NOT NULL`. Two different databases, both passing every check the app had.
         *
         * Room does not catch it. Its identity hash is computed from column names,
         * affinities, nullability and indices — a DEFAULT clause is not part of it — so both
         * shapes verify identically on open. What catches it is data/SchemaParityTest, which
         * walks a database up from version 1 and compares `PRAGMA table_info` against a fresh
         * install column by column. That test is the reason this rebuild exists.
         *
         * The rebuild is the same sequence [MIGRATION_6_7] uses and is safe for the same
         * reason: `program_groups` cascades from `programs`, and dropping a parent table with
         * foreign keys ENABLED would delete its children, but Room turns foreign keys on in
         * `onOpen`, which runs after migrations. The AUTOINCREMENT counter is carried across
         * for the reason spelled out in [MIGRATION_6_7]: a rebuilt table restarts its counter
         * at the highest surviving id, and a reissued program id would adopt the groups of a
         * program that was deleted.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TEMP TABLE `seq_before_v3` AS SELECT `name`, `seq` FROM `sqlite_sequence` " +
                        "WHERE `name` = 'programs'"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_programs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`space_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`prepare_sec` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`created_at` TEXT NOT NULL, " +
                        "`exercise_id` INTEGER, " +
                        "`category` TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `_new_programs` (`id`, `space_id`, `name`, `prepare_sec`, " +
                        "`position`, `created_at`, `exercise_id`, `category`) " +
                        "SELECT `id`, `space_id`, `name`, `prepare_sec`, `position`, `created_at`, " +
                        "NULL, '' FROM `programs`"
                )
                db.execSQL("DROP TABLE `programs`")
                db.execSQL("ALTER TABLE `_new_programs` RENAME TO `programs`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_programs_space_id_id` " +
                        "ON `programs` (`space_id`, `id`)"
                )

                // delete-then-insert rather than INSERT OR REPLACE, for the reason given in
                // [MIGRATION_6_7]: `sqlite_sequence` has no unique index on `name`
                db.execSQL(
                    "DELETE FROM `sqlite_sequence` WHERE `name` IN (SELECT `name` FROM `seq_before_v3`)"
                )
                db.execSQL(
                    "INSERT INTO `sqlite_sequence` (`name`, `seq`) SELECT `name`, `seq` FROM `seq_before_v3`"
                )
                db.execSQL("DROP TABLE `seq_before_v3`")
            }
        }

        /**
         * Version 3 -> 4: the demo-seed marker on the catalog, the aliases and the slots.
         *
         * A column per table, NOT NULL with a default of 0, so every row already on the
         * phone read as "the user's own" — the safe answer, because the only thing the flag
         * was ever used for was DELETION.
         *
         * ── This adds a column that version 7 takes away again ──────────────────────
         * The demo seed is gone and so is the mark; [MIGRATION_6_7] drops all three columns.
         * This step is kept, and kept literal, because a phone at version 3 still has to walk
         * through 4, 5 and 6 to get to 7, and every one of those steps has to describe the
         * database that actually existed at the time. Shortening the chain by pretending the
         * column was never added would make 4 -> 5 and 5 -> 6 run against a schema no phone
         * ever had; the column names are spelled out here rather than taken from a constant
         * for the same reason — there is no constant left, and there should not be one, since
         * nothing in today's schema is allowed to depend on it.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("exercises", "aliases", "slots")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `seeded` INTEGER NOT NULL DEFAULT 0")
                }
            }
        }

        /**
         * Version 4 -> 5: the workout. One column on the journal saying which workout a row
         * was recorded during, and two on the catalog remembering how the user wants an
         * exercise handled.
         *
         * Purely additive and ALL THREE ARE NULLABLE WITH NO DEFAULT, which is the decision
         * worth spelling out. Every row already on the phone comes through as "recorded
         * outside any workout" and "nothing has been said about the rest" — and both of those
         * are true statements about those rows, not placeholders. The alternative, stamping
         * existing sets into an invented workout, would have manufactured history: the app
         * had no workouts when they were written, so no such workout ever happened.
         *
         * Nullable also keeps the property the journal is built on ([EventEntity.workoutId]):
         * a set can always be recorded without a workout being open. A NOT NULL column with a
         * sentinel default would have made "no workout" a special id that every reader has to
         * remember to exclude.
         *
         * `led_by_protocol` is INTEGER because SQLite has no boolean; Room reads a nullable
         * INTEGER back as a `Boolean?`, so null survives the round trip as null rather than
         * collapsing into false — which it must, since null means "infer it" and false means
         * "the user said no".
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `events` ADD COLUMN `workout_id` INTEGER")
                db.execSQL("ALTER TABLE `exercises` ADD COLUMN `default_rest_sec` INTEGER")
                db.execSQL("ALTER TABLE `exercises` ADD COLUMN `led_by_protocol` INTEGER")
            }
        }

        /**
         * Version 5 -> 6: what a planned session is made of.
         *
         * ONE NEW TABLE AND NOTHING ELSE — no existing table is touched, so every slot on
         * the phone comes through with an empty composition, which is exactly what it had.
         * That is also the shape of the feature: a slot with no exercises stays a complete
         * plan (see domain/Schedule.kt), so "nothing was migrated into it" is not a gap to
         * be filled in later, it is the resting state.
         *
         * The foreign key is declared here because it is declared on [SlotExerciseEntity],
         * and the two have to agree: Room compares the database against the entities on the
         * next open, and a key present in one place and missing in the other fails that check
         * rather than failing quietly. It is also what makes deleting a slot take its
         * composition with it, which is enforcement rather than bookkeeping — Room switches
         * foreign keys on at the connection level.
         *
         * `rest_sec` is nullable with no default: null means "use the rest this exercise
         * usually gets", and a NOT NULL column with a sentinel would turn that into a magic
         * number every reader has to remember.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `slot_exercises` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`slot_id` INTEGER NOT NULL, " +
                        "`exercise_id` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`rest_sec` INTEGER, " +
                        "FOREIGN KEY(`slot_id`) REFERENCES `slots`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_slot_exercises_slot_id` " +
                        "ON `slot_exercises` (`slot_id`)"
                )
            }
        }

        /**
         * Version 6 -> 7: the demo seed and the learned synonyms are gone, and so is
         * everything in the schema that existed to serve them — the `aliases` table and the
         * `seeded` column on the catalog and on the plan.
         *
         * THE FIRST MIGRATION THAT TAKES SOMETHING AWAY, which is why it is longer than the
         * five before it. SQLite cannot drop a column portably, so each affected table is
         * rebuilt: create the new shape, copy every row across by name, drop the old table,
         * rename. That is the sequence Room's own generated migrations use, and it is safe
         * here for one reason that has to be said out loud — `slot_exercises` has an
         * ON DELETE CASCADE against `slots`, and dropping a parent table WITH FOREIGN KEYS
         * ENABLED performs an implicit delete of its rows, which would take every planned
         * composition with it. Room turns foreign keys on in `onOpen`, which runs after
         * migrations, so they are off while this executes. The migration test writes a
         * composition before the upgrade and reads it back after, so that this is checked
         * rather than assumed.
         *
         * ── Nothing is lost, including the rows the seed created ────────────────────
         * The copy is unconditional: a row marked `seeded = 1` comes through exactly like
         * any other. There is no longer any such thing as demo data, so an exercise that was
         * demo data is simply an exercise, and deleting rows during an upgrade — silently,
         * with no dialog and no undo — is the opposite of what this app promises. A user who
         * wants the leftovers gone can delete them one at a time, seeing what goes.
         *
         * ── Why the id sequences are restored by hand ───────────────────────────────
         * `id INTEGER PRIMARY KEY AUTOINCREMENT` never reuses an id, and the guarantee is
         * kept in `sqlite_sequence`, which the drop takes with it: the rebuilt table's
         * counter restarts at its highest SURVIVING id, so ids above that — exercises the
         * user deleted — would be handed out a second time. The journal outlives the catalog
         * (an entry keeps the exercise_id of a row that is gone), so a reissued id would
         * silently re-attach old entries to a new exercise. Copying the counters back across
         * the rebuild is four lines and closes that.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TEMP TABLE `seq_before_v7` AS SELECT `name`, `seq` FROM `sqlite_sequence` " +
                        "WHERE `name` IN ('exercises', 'slots')"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_exercises` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`space_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`form` INTEGER NOT NULL, " +
                        "`created_at` TEXT NOT NULL, " +
                        "`edge_mm` REAL, " +
                        "`protocol_work_sec` REAL, " +
                        "`protocol_rest_sec` REAL, " +
                        "`default_rest_sec` INTEGER, " +
                        "`led_by_protocol` INTEGER)"
                )
                db.execSQL(
                    "INSERT INTO `_new_exercises` (`id`, `space_id`, `name`, `form`, `created_at`, " +
                        "`edge_mm`, `protocol_work_sec`, `protocol_rest_sec`, `default_rest_sec`, " +
                        "`led_by_protocol`) SELECT `id`, `space_id`, `name`, `form`, `created_at`, " +
                        "`edge_mm`, `protocol_work_sec`, `protocol_rest_sec`, `default_rest_sec`, " +
                        "`led_by_protocol` FROM `exercises`"
                )
                db.execSQL("DROP TABLE `exercises`")
                db.execSQL("ALTER TABLE `_new_exercises` RENAME TO `exercises`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_exercises_space_id_id` " +
                        "ON `exercises` (`space_id`, `id`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_slots` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`space_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`at_time` TEXT, " +
                        "`repeat_rule` TEXT NOT NULL, " +
                        "`anchor_date` TEXT NOT NULL, " +
                        "`created_at` TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `_new_slots` (`id`, `space_id`, `name`, `at_time`, `repeat_rule`, " +
                        "`anchor_date`, `created_at`) SELECT `id`, `space_id`, `name`, `at_time`, " +
                        "`repeat_rule`, `anchor_date`, `created_at` FROM `slots`"
                )
                db.execSQL("DROP TABLE `slots`")
                db.execSQL("ALTER TABLE `_new_slots` RENAME TO `slots`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_slots_space_id_id` ON `slots` (`space_id`, `id`)"
                )

                db.execSQL("DROP TABLE IF EXISTS `aliases`")

                // delete-then-insert rather than INSERT OR REPLACE: `sqlite_sequence` carries
                // no unique index on `name`, so "or replace" has nothing to match on and
                // quietly leaves TWO counters for the same table
                db.execSQL(
                    "DELETE FROM `sqlite_sequence` WHERE `name` IN (SELECT `name` FROM `seq_before_v7`)"
                )
                db.execSQL(
                    "INSERT INTO `sqlite_sequence` (`name`, `seq`) SELECT `name`, `seq` FROM `seq_before_v7`"
                )
                db.execSQL("DROP TABLE `seq_before_v7`")
            }
        }

        /**
         * Version 7 -> 8: every stored row gets a `uid` — the identity that survives leaving
         * this phone (see [Entities.kt][EventEntity] and domain/Uid.kt).
         *
         * ── Why five tables are rebuilt rather than altered ─────────────────────────
         * The column is NOT NULL and unique. SQLite will not add a NOT NULL column to a
         * populated table without a DEFAULT, and a default would be the same value on every
         * row, which is the exact opposite of an identity. So each table is rebuilt with the
         * column in place, filled row by row, and only THEN given its unique index — if a
         * single row were left without an id of its own, the index creation is what fails,
         * loudly, instead of the database quietly holding duplicates.
         *
         * ── The ids of old rows are seeded from the times those rows carry ──────────
         * A UUIDv7 begins with the millisecond it was minted at, and the point of that is
         * that plain string order is creation order. Stamping "now" onto a decade of history
         * would throw that away and sort every migrated row after every future one. So each
         * row's own recorded time is used where the table has one (`events.ts`,
         * `created_at` elsewhere), and the migration's own clock only where there is none —
         * `slot_exercises`, which records no time. Those lines then sort among themselves by
         * insertion order rather than by anything meaningful, which is the honest answer:
         * the table never knew when its rows were written.
         *
         * A time that cannot be parsed also falls back to the migration clock rather than
         * failing the upgrade. A uid seeded from the wrong millisecond is still a perfectly
         * good identity; refusing to open the database over one malformed timestamp is not.
         *
         * ── Foreign keys and id counters ────────────────────────────────────────────
         * Same two hazards as [MIGRATION_6_7], handled the same way. `programs` and `slots`
         * are parents of cascading children, and dropping a parent with foreign keys ENABLED
         * would delete those children — Room turns foreign keys on after migrations run, so
         * they are off here. And every rebuilt table has its AUTOINCREMENT counter carried
         * across, so an id belonging to a deleted row is never handed out a second time.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TEMP TABLE `seq_before_v8` AS SELECT `name`, `seq` FROM `sqlite_sequence` " +
                        "WHERE `name` IN ('events', 'exercises', 'slots', 'slot_exercises', 'programs')"
                )

                rebuildWithUid(
                    db,
                    table = "events",
                    ddl = "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ts` TEXT NOT NULL, " +
                        "`space_id` INTEGER NOT NULL, " +
                        "`author_id` INTEGER NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`payload` TEXT NOT NULL, " +
                        "`workout_id` INTEGER, " +
                        "`uid` TEXT NOT NULL",
                    columns = "`id`, `ts`, `space_id`, `author_id`, `type`, `payload`, `workout_id`",
                    timeColumn = "ts",
                    indices = listOf(
                        "CREATE INDEX IF NOT EXISTS `index_events_space_id_id` " +
                            "ON `events` (`space_id`, `id`)",
                    ),
                )

                rebuildWithUid(
                    db,
                    table = "exercises",
                    ddl = "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`space_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`form` INTEGER NOT NULL, " +
                        "`created_at` TEXT NOT NULL, " +
                        "`edge_mm` REAL, " +
                        "`protocol_work_sec` REAL, " +
                        "`protocol_rest_sec` REAL, " +
                        "`default_rest_sec` INTEGER, " +
                        "`led_by_protocol` INTEGER, " +
                        "`uid` TEXT NOT NULL",
                    columns = "`id`, `space_id`, `name`, `form`, `created_at`, `edge_mm`, " +
                        "`protocol_work_sec`, `protocol_rest_sec`, `default_rest_sec`, `led_by_protocol`",
                    timeColumn = "created_at",
                    indices = listOf(
                        "CREATE INDEX IF NOT EXISTS `index_exercises_space_id_id` " +
                            "ON `exercises` (`space_id`, `id`)",
                    ),
                )

                rebuildWithUid(
                    db,
                    table = "programs",
                    ddl = "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`space_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`prepare_sec` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`created_at` TEXT NOT NULL, " +
                        "`exercise_id` INTEGER, " +
                        "`category` TEXT NOT NULL, " +
                        "`uid` TEXT NOT NULL",
                    columns = "`id`, `space_id`, `name`, `prepare_sec`, `position`, `created_at`, " +
                        "`exercise_id`, `category`",
                    timeColumn = "created_at",
                    indices = listOf(
                        "CREATE INDEX IF NOT EXISTS `index_programs_space_id_id` " +
                            "ON `programs` (`space_id`, `id`)",
                    ),
                )

                rebuildWithUid(
                    db,
                    table = "slots",
                    ddl = "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`space_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`at_time` TEXT, " +
                        "`repeat_rule` TEXT NOT NULL, " +
                        "`anchor_date` TEXT NOT NULL, " +
                        "`created_at` TEXT NOT NULL, " +
                        "`uid` TEXT NOT NULL",
                    columns = "`id`, `space_id`, `name`, `at_time`, `repeat_rule`, `anchor_date`, " +
                        "`created_at`",
                    timeColumn = "created_at",
                    indices = listOf(
                        "CREATE INDEX IF NOT EXISTS `index_slots_space_id_id` ON `slots` (`space_id`, `id`)",
                    ),
                )

                rebuildWithUid(
                    db,
                    table = "slot_exercises",
                    ddl = "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`slot_id` INTEGER NOT NULL, " +
                        "`exercise_id` INTEGER NOT NULL, " +
                        "`position` INTEGER NOT NULL, " +
                        "`rest_sec` INTEGER, " +
                        "`uid` TEXT NOT NULL, " +
                        "FOREIGN KEY(`slot_id`) REFERENCES `slots`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE ",
                    columns = "`id`, `slot_id`, `exercise_id`, `position`, `rest_sec`",
                    timeColumn = null,
                    indices = listOf(
                        "CREATE INDEX IF NOT EXISTS `index_slot_exercises_slot_id` " +
                            "ON `slot_exercises` (`slot_id`)",
                    ),
                )

                db.execSQL(
                    "DELETE FROM `sqlite_sequence` WHERE `name` IN (SELECT `name` FROM `seq_before_v8`)"
                )
                db.execSQL(
                    "INSERT INTO `sqlite_sequence` (`name`, `seq`) SELECT `name`, `seq` FROM `seq_before_v8`"
                )
                db.execSQL("DROP TABLE `seq_before_v8`")
            }
        }

        /**
         * Version 8 -> 9: the journal's workout link, said in uids.
         *
         * One nullable column, so a plain `ALTER TABLE ADD COLUMN` will do — no rebuild, no
         * default, and every row that belonged to no workout stays saying exactly that.
         *
         * ── The backfill is the whole point ─────────────────────────────────────────
         * Adding the column empty and letting only new rows fill it would split the history:
         * rows written before this version would be found by the numeric link and rows written
         * after by the uid, and any reader that preferred one would lose the other half of a
         * workout. So every existing row is pointed at the uid of the start event its number
         * already named, in one statement — the ids are in the same table, so this is a
         * lookup and not a guess.
         *
         * A row whose `workout_id` names a start event that is NOT in this journal keeps its
         * dangling number and gets no uid. That is the honest outcome: the row says it belongs
         * to a workout this database has never seen, and inventing an identity for it would
         * turn "I do not know that workout" into a claim about one.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `events` ADD COLUMN `workout_uid` TEXT")
                db.execSQL(
                    "UPDATE `events` SET `workout_uid` = " +
                        "(SELECT `start`.`uid` FROM `events` AS `start` WHERE `start`.`id` = `events`.`workout_id`) " +
                        "WHERE `workout_id` IS NOT NULL"
                )
            }
        }

        /**
         * Version 9 -> 10: every entry names its exercise by identity as well as by number.
         *
         * NO SCHEMA CHANGE AT ALL — the link lives in the payload, so this migration is
         * nothing but a rewrite of the JSON already stored. It still has to be a migration
         * rather than something done lazily on read, and that is the point worth writing down.
         *
         * ── Why the backfill is not optional ────────────────────────────────────────
         * The reducers group entries of one exercise by [ExerciseLink.key], which is the
         * identity when the entry has one and the number otherwise. Leave the old rows alone
         * and a single exercise ends up under TWO keys — everything logged before the upgrade
         * under "id:5" and everything after under a uid. The detail screen would show half a
         * history, the records would be computed over half the sets, and nothing anywhere
         * would look broken. So the old rows are brought up to the new way of speaking, in one
         * pass, at upgrade time.
         *
         * ── What is deliberately left alone ─────────────────────────────────────────
         * An entry whose `exercise_id` names a catalog row that no longer exists gets no uid.
         * There is no identity to give it: the exercise it points at is gone, and inventing one
         * would attach that history to whatever is created next. It keeps its number, stays
         * grouped under it, and remains exactly as findable as it was.
         *
         * Body-weight entries name no exercise by design and are skipped for that reason
         * rather than by accident, along with any payload that will not parse — a damaged row
         * costs itself, never the upgrade (see [formFromEventOrNull] for the same rule on
         * reads).
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val uidOfExercise = HashMap<Long, String>()
                db.query("SELECT `id`, `uid` FROM `exercises`").use { c ->
                    while (c.moveToNext()) uidOfExercise[c.getLong(0)] = c.getString(1)
                }
                if (uidOfExercise.isEmpty()) return

                val rewritten = ArrayList<Pair<Long, String>>()
                db.query("SELECT `id`, `payload` FROM `events`").use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        withExerciseUid(c.getString(1), uidOfExercise)?.let { rewritten += id to it }
                    }
                }
                for ((id, payload) in rewritten) {
                    db.execSQL(
                        "UPDATE `events` SET `payload` = ? WHERE `id` = ?",
                        arrayOf<Any>(payload, id),
                    )
                }
            }
        }

        /**
         * Version 10 -> 11: a workout names the plan it was started from by identity as well
         * as by number.
         *
         * NO SCHEMA CHANGE — the link lives in the `workout_started` payload, so this is a
         * rewrite of stored JSON, the same shape of step as [MIGRATION_9_10] and for the same
         * reason: the readers compare plans through [xyz.oleolegka.gachimuchi.domain.SlotLink],
         * which prefers the identity whenever both sides have one. Leave the old start events
         * alone and a plan started from before the upgrade would be compared by number against
         * a slot that now speaks uids — right on this phone, and wrong the moment either side
         * of that comparison has travelled.
         *
         * ── What is deliberately left alone ─────────────────────────────────────────
         * A start event whose `slot_id` names a plan that has since been deleted gets no uid:
         * there is no identity to give it, and minting one would be a claim about a plan this
         * database has never held. It keeps its number and stays exactly as resolvable as it
         * was — which is to say, not at all, since the slot is gone.
         *
         * A workout started off-plan has no `slot_id` and is untouched, along with every event
         * of every other type and any payload that will not parse.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val uidOfSlot = HashMap<Long, String>()
                db.query("SELECT `id`, `uid` FROM `slots`").use { c ->
                    while (c.moveToNext()) uidOfSlot[c.getLong(0)] = c.getString(1)
                }
                if (uidOfSlot.isEmpty()) return

                // the type string is spelled out rather than taken from the domain constant,
                // on the same grounds as the column names in [MIGRATION_3_4]: a migration
                // describes the database as it was, and must not change meaning if a constant
                // in today's code is ever renamed
                val rewritten = ArrayList<Pair<Long, String>>()
                db.query(
                    "SELECT `id`, `payload` FROM `events` WHERE `type` = 'workout_started'"
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        withSlotUid(c.getString(1), uidOfSlot)?.let { rewritten += id to it }
                    }
                }
                for ((id, payload) in rewritten) {
                    db.execSQL(
                        "UPDATE `events` SET `payload` = ? WHERE `id` = ?",
                        arrayOf<Any>(payload, id),
                    )
                }
            }
        }

        /**
         * Version 11 -> 12: a workout carries the name it was started under, instead of
         * borrowing the plan's name every time a card is drawn.
         *
         * NO SCHEMA CHANGE — another rewrite of the `workout_started` payload, and the same
         * pass as [MIGRATION_10_11] over the same rows.
         *
         * ── Why this cannot be left to new workouts only ────────────────────────────
         * The screens stop asking the plan what a workout is called the moment this ships. A
         * start event without a snapshot is therefore a workout that loses its name and is
         * shown by its time of day — every workout on the phone, all at once, on upgrade. So
         * the snapshot is written for every start event that named a plan.
         *
         * ── The name written is TODAY'S name, and that is the honest best ───────────
         * What the slot was called on the day the workout was started is not recorded
         * anywhere; the app has been showing the slot's CURRENT name for that workout all
         * along. Freezing that is not a claim to have recovered history — it changes nothing
         * about what the user sees today and stops the name drifting from here on. The
         * alternative, leaving old workouts nameless, would throw away a name that is right
         * far more often than not.
         *
         * A start event whose plan has been deleted gets no name: there is nothing to copy,
         * and it falls back to its time of day like a workout nobody named.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val nameOfSlot = HashMap<Long, String>()
                db.query("SELECT `id`, `name` FROM `slots`").use { c ->
                    while (c.moveToNext()) nameOfSlot[c.getLong(0)] = c.getString(1)
                }
                if (nameOfSlot.isEmpty()) return

                // the type string is spelled out for the reason given in [MIGRATION_10_11]
                val rewritten = ArrayList<Pair<Long, String>>()
                db.query(
                    "SELECT `id`, `payload` FROM `events` WHERE `type` = 'workout_started'"
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        withSlotName(c.getString(1), nameOfSlot)?.let { rewritten += id to it }
                    }
                }
                for ((id, payload) in rewritten) {
                    db.execSQL(
                        "UPDATE `events` SET `payload` = ? WHERE `id` = ?",
                        arrayOf<Any>(payload, id),
                    )
                }
            }
        }

        /**
         * One `workout_started` payload with `name` filled in from the plan it names, or null
         * when there is nothing to do — no plan named, a name already there, a plan that is
         * gone, or a payload that will not parse.
         *
         * A name already there is left alone whether it came from a plan or from the user, and
         * a name that is present but BLANK counts as absent: a heading of three spaces is not
         * something anybody meant to keep.
         */
        private fun withSlotName(payload: String, nameOfSlot: Map<Long, String>): String? {
            val json = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(payload)
            }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null

            val alreadyNamed = (json["name"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull
            if (alreadyNamed != null && alreadyNamed.isNotBlank()) return null
            val slotId = (json["slot_id"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.toLongOrNull() ?: return null
            val name = nameOfSlot[slotId]?.trim()?.takeIf { it.isNotEmpty() } ?: return null

            return kotlinx.serialization.json.JsonObject(
                json + ("name" to kotlinx.serialization.json.JsonPrimitive(name))
            ).toString()
        }

        /**
         * One `workout_started` payload with `slot_uid` filled in from `slot_id`, or null when
         * there is nothing to do — no plan named, a uid already there, a plan that is gone, or
         * a payload that will not parse.
         *
         * "A uid already there" means a uid and not a KEY already there, for the reason spelled
         * out on [withExerciseUid]: `encodeDefaults` writes `"slot_uid": null` for a build that
         * knows the field and has nothing to put in it, and reading that as "done" would skip
         * exactly the rows this exists for.
         */
        private fun withSlotUid(payload: String, uidOfSlot: Map<Long, String>): String? =
            withResolvedUid(payload, "slot_id", "slot_uid", uidOfSlot)

        /**
         * One payload with `exercise_uid` filled in from `exercise_id`, or null when there is
         * nothing to do — no exercise named, a uid already there, an unknown exercise, or a
         * payload that will not parse.
         *
         * Returning null for "nothing to do" rather than the unchanged string is what keeps
         * the migration from rewriting every row in the journal to the value it already held.
         *
         * "A uid already there" means a uid, not a KEY already there. Payloads are written
         * with `encodeDefaults`, so a form serialised by a build that knows the field but has
         * nothing to put in it stores `"exercise_uid": null` — the key is present and the link
         * is not. Reading that as "done" would leave exactly the entries this migration exists
         * for untouched, and silently: they parse, they resolve by number, and the split
         * history only shows up as records computed over half the sets.
         */
        private fun withExerciseUid(payload: String, uidOfExercise: Map<Long, String>): String? =
            withResolvedUid(payload, "exercise_id", "exercise_uid", uidOfExercise)

        /**
         * One payload with [uidKey] filled in from the row number under [idKey], or null when
         * there is nothing to do.
         *
         * Shared by the two payload backfills because they are the same operation on two pairs
         * of keys, and the rule that is easy to get wrong — a KEY present with a null value is
         * not a link — is one that should have exactly one implementation.
         */
        private fun withResolvedUid(
            payload: String,
            idKey: String,
            uidKey: String,
            uidOfRow: Map<Long, String>,
        ): String? {
            val json = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(payload)
            }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null

            val alreadyLinked = (json[uidKey] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull
            if (alreadyLinked != null) return null
            val rowId = (json[idKey] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.toLongOrNull() ?: return null
            val uid = uidOfRow[rowId] ?: return null

            return kotlinx.serialization.json.JsonObject(
                json + (uidKey to kotlinx.serialization.json.JsonPrimitive(uid))
            ).toString()
        }

        /**
         * Rebuilds one table with a `uid` column on the end, gives every existing row an id of
         * its own, and only then creates the indices — the unique one included, so a row left
         * without an id fails the upgrade instead of passing it.
         *
         * [timeColumn] is the column whose value seeds the uid's leading timestamp, or null
         * for a table that records no time.
         */
        private fun rebuildWithUid(
            db: SupportSQLiteDatabase,
            table: String,
            ddl: String,
            columns: String,
            timeColumn: String?,
            indices: List<String>,
        ) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `_new_$table` ($ddl)")
            db.execSQL(
                "INSERT INTO `_new_$table` ($columns, `uid`) SELECT $columns, '' FROM `$table`"
            )
            db.execSQL("DROP TABLE `$table`")
            db.execSQL("ALTER TABLE `_new_$table` RENAME TO `$table`")

            val select = if (timeColumn == null) "SELECT `id`, NULL FROM `$table`"
            else "SELECT `id`, `$timeColumn` FROM `$table`"
            val rows = ArrayList<Pair<Long, String?>>()
            db.query(select).use { c ->
                while (c.moveToNext()) rows += c.getLong(0) to if (c.isNull(1)) null else c.getString(1)
            }
            val fallback = System.currentTimeMillis()
            for ((id, stamp) in rows) {
                db.execSQL(
                    "UPDATE `$table` SET `uid` = ? WHERE `id` = ?",
                    arrayOf<Any>(newUid(atMillis = epochMillisOf(stamp) ?: fallback), id),
                )
            }

            for (statement in indices) db.execSQL(statement)
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_uid` ON `$table` (`uid`)"
            )
        }

        /**
         * A journal timestamp ("2026-08-01T10:00:00") as epoch milliseconds, or null when it
         * will not parse.
         *
         * The stored string carries no zone — that is the very defect schema version 8's
         * sibling change is about — so it is read in the device's CURRENT zone. For seeding a
         * uid that is more than good enough: the value only has to put old rows in roughly
         * the right order relative to each other, and a whole-journal shift of a few hours
         * changes nothing about that order.
         */
        private fun epochMillisOf(raw: String?): Long? {
            if (raw == null) return null
            return runCatching {
                java.time.LocalDateTime.parse(raw)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
        }

        val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
            MIGRATION_11_12,
        )

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "gachimuchi.db",
            ).addMigrations(*MIGRATIONS).build().also { instance = it }
        }
    }
}
