package xyz.oleolegka.gachimuchi.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.contentOrNull
import xyz.oleolegka.gachimuchi.data.WriteTime
import xyz.oleolegka.gachimuchi.data.opDateOfPayload
import xyz.oleolegka.gachimuchi.domain.exerciseIdentityKey
import xyz.oleolegka.gachimuchi.domain.fmtNum
import xyz.oleolegka.gachimuchi.domain.freeExerciseName
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
    version = 18,
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
         * Version 12 -> 13: the catalog can say that an exercise is trained ONE LIMB AT A TIME.
         *
         * One column, and it takes a whole table rebuild to add it. `one_sided` is NOT NULL
         * (see [ExerciseEntity.oneSided] for why there is no third state worth a nullable
         * column), and SQLite refuses to add a NOT NULL column to a populated table without a
         * DEFAULT — a default that would then stay on the column forever, leaving an upgraded
         * phone with `one_sided INTEGER NOT NULL DEFAULT 0` where a fresh install has
         * `one_sided INTEGER NOT NULL`. Room's identity hash does not include default clauses,
         * so both shapes would pass every check the app makes on open; data/SchemaParityTest
         * is what catches it, and this rebuild is the answer, exactly as in [MIGRATION_2_3].
         *
         * ── What every existing row comes through as, and why that is true ──────────
         * False: nothing in the catalog was one-sided before there was a way to say so. This
         * is not a placeholder standing in for an unknown — the fact genuinely did not exist,
         * and the user marking a fingerboard exercise one-sided next week is new information
         * rather than a correction.
         *
         * What that marking then exposes is the sets already in the journal, which named no
         * hand because nothing asked them to. They do not become "both hands" and they are not
         * rewritten: the reducers report them as a record whose side is unknown
         * ([xyz.oleolegka.gachimuchi.domain.ExerciseRecord.sideMissing]). Guessing here would
         * mean writing a hand into history that nobody recorded.
         *
         * ── The two hazards of a rebuild, and why neither bites ─────────────────────
         * `exercises` is nobody's foreign-key parent — the plan and the programs deliberately
         * point at it WITHOUT one (see [SlotExerciseEntity] and [ProgramEntity]), so dropping
         * it deletes nothing by cascade. The AUTOINCREMENT counter is carried across by hand
         * for the reason [MIGRATION_6_7] spells out: a rebuilt table restarts its counter at
         * the highest surviving id, and a reissued exercise id would silently adopt the
         * journal entries of an exercise the user deleted.
         *
         * The unique index on `uid` is recreated with the rest. It is not decoration: it is
         * the thing that would fail the upgrade loudly if the copy ever lost an identity.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TEMP TABLE `seq_before_v13` AS SELECT `name`, `seq` FROM `sqlite_sequence` " +
                        "WHERE `name` = 'exercises'"
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
                        "`led_by_protocol` INTEGER, " +
                        "`uid` TEXT NOT NULL, " +
                        "`one_sided` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `_new_exercises` (`id`, `space_id`, `name`, `form`, `created_at`, " +
                        "`edge_mm`, `protocol_work_sec`, `protocol_rest_sec`, `default_rest_sec`, " +
                        "`led_by_protocol`, `uid`, `one_sided`) " +
                        "SELECT `id`, `space_id`, `name`, `form`, `created_at`, `edge_mm`, " +
                        "`protocol_work_sec`, `protocol_rest_sec`, `default_rest_sec`, " +
                        "`led_by_protocol`, `uid`, 0 FROM `exercises`"
                )
                db.execSQL("DROP TABLE `exercises`")
                db.execSQL("ALTER TABLE `_new_exercises` RENAME TO `exercises`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_exercises_space_id_id` " +
                        "ON `exercises` (`space_id`, `id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercises_uid` ON `exercises` (`uid`)"
                )

                // delete-then-insert rather than INSERT OR REPLACE, for the reason given in
                // [MIGRATION_6_7]: `sqlite_sequence` carries no unique index on `name`
                db.execSQL(
                    "DELETE FROM `sqlite_sequence` WHERE `name` IN (SELECT `name` FROM `seq_before_v13`)"
                )
                db.execSQL(
                    "INSERT INTO `sqlite_sequence` (`name`, `seq`) SELECT `name`, `seq` FROM `seq_before_v13`"
                )
                db.execSQL("DROP TABLE `seq_before_v13`")
            }
        }

        /**
         * Version 13 -> 14: what share of body weight an exercise lifts, and what every
         * body-weight set already in the journal was lifted at.
         *
         * ── Two halves, and only one of them is a column ────────────────────────────
         * `bodyweight_share` is nullable with no default, so a plain ALTER TABLE will do and
         * every exercise comes through as "nobody has said" — which is true, and which keeps
         * the charts of a catalog nobody has filled in exactly as they were.
         *
         * The other half is a payload backfill, and it is the reason this migration is not
         * three lines. Every own-weight set is stamped with the body weight the scales last
         * showed ON OR BEFORE that set's own day.
         *
         * ── Why the backfill is not optional ────────────────────────────────────────
         * The snapshot is what makes a body-weight set worth anything on the tonnage chart.
         * Leave the old rows empty and the first time somebody fills in a share for pull-ups
         * their chart switches from counting reps to counting kilograms — and every day
         * before the upgrade draws as ZERO, because those sets carry no weight to multiply.
         * A history that reads as years of nothing followed by a sudden wall is worse than
         * the flat rep count it replaced. So the old sets are given the number they were
         * actually performed at, once, here.
         *
         * ── This is a lookup, not an invention ──────────────────────────────────────
         * The weight comes from the user's own weigh-ins, matched BY DAY: the last one
         * recorded on or before the set's `op_date`. A set logged before the scales were ever
         * used gets nothing and stays worth nothing — there is no honest number to give it,
         * and picking the earliest later weigh-in would be claiming to know what somebody
         * weighed before they had ever weighed themselves.
         *
         * Matching by day rather than by write order is what makes back-dated training come
         * out right: a session typed up a fortnight late must be stamped with what the scales
         * said THEN, not with this morning's reading.
         *
         * Hold sets are stamped along with strength sets even though nothing computes a hold
         * volume today. The snapshot is a fact about the set — what you weighed when you hung
         * off that edge — and a field present on half the journal is the split-history problem
         * [MIGRATION_9_10] exists to prevent, arriving later and harder to fix.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `exercises` ADD COLUMN `bodyweight_share` REAL")

                // the type strings are spelled out for the reason given in [MIGRATION_10_11]:
                // a migration describes the database as it was and must not change meaning if
                // a constant in today's code is renamed
                val weighIns = ArrayList<Pair<String, Double>>()
                db.query(
                    "SELECT `payload` FROM `events` WHERE `type` = 'bodyweight' ORDER BY `id`"
                ).use { c ->
                    while (c.moveToNext()) {
                        val json = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(c.getString(0))
                        }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: continue
                        val day = (json["op_date"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.contentOrNull ?: continue
                        val kg = (json["weight_kg"] as? kotlinx.serialization.json.JsonPrimitive)
                            ?.contentOrNull?.toDoubleOrNull() ?: continue
                        if (kg > 0) weighIns += day to kg
                    }
                }
                if (weighIns.isEmpty()) return
                // stable, so several weigh-ins on one day resolve to the last one written
                val byDay = weighIns.sortedBy { it.first }

                val rewritten = ArrayList<Pair<Long, String>>()
                db.query(
                    "SELECT `id`, `payload` FROM `events` " +
                        "WHERE `type` IN ('strength_set', 'hold_set')"
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        withBodyweightSnapshot(c.getString(1), byDay)?.let { rewritten += id to it }
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
         * Version 14 -> 15: the identity of an exercise becomes a constraint, and an exercise
         * can be hidden.
         *
         * ── What was actually wrong ────────────────────────────────────────────────
         * §12-A has said since it was written that an exercise is its name plus its edge plus
         * its protocol. The readers obeyed it. The WRITER did not: creating an exercise looked
         * for a row with the same normalized name and returned it, edge and protocol thrown
         * away without a word. Adding hangs on a 15 mm edge while 20 mm hangs existed gave you
         * the 20 mm row and welded two histories together, permanently and invisibly. The rule
         * had no expression in the schema at all — no index, no constraint, nothing that could
         * have refused.
         *
         * So `identity_key` is added, carrying the four defining values folded into one string
         * (see [xyz.oleolegka.gachimuchi.domain.ExerciseIdentity]), with a UNIQUE index over it.
         * A single string rather than a five-column UNIQUE index because three of those columns
         * are nullable and SQLite counts NULLs as distinct — such an index would have permitted
         * any number of rows called "Bench press" with no edge and no protocol, which is every
         * strength exercise there is.
         *
         * ── The key is computed by TODAY's code, on purpose ────────────────────────
         * The opposite of the rule [MIGRATION_10_11] follows for type strings. A type string
         * describes data as it was written and must not move; a key describes how THIS BUILD
         * looks rows up, and a migration that seeded a stale format would leave every row
         * invisible to the lookup that prevents duplicates. If the format ever changes, the
         * migration that changes it rewrites every key, and this one keeps producing whatever
         * the code it runs inside considers a key.
         *
         * ── Duplicates already in the catalog, and why the upgrade renames them ────
         * A UNIQUE index cannot be created over data that violates it: on a catalog holding two
         * rows of one identity, `CREATE UNIQUE INDEX` fails, the migration throws, and — with
         * destructive fallback deliberately off — the app does not open at all. On the one
         * device holding the history. That is not an acceptable way to find out.
         *
         * Three ways out were available. MERGING the rows means repointing every set that names
         * the loser, inside payloads, in a migration — the most dangerous edit in the app,
         * performed without anybody watching. GIVING ONE ROW A KEY NOBODY ELSE HAS while leaving
         * its name alone hides the problem: two rows called "Hangs", identical on screen, one of
         * which silently receives every future set. What happens instead is that the second row
         * is RENAMED — "Hangs", then "Hangs (2)" — which changes one word of the user's data,
         * keeps both histories exactly where they were, keeps every event pointing at the row it
         * always pointed at, and puts the collision where it can be seen and dealt with (rename
         * it properly, or hide it). A duplicate that is visible is a duplicate that can be
         * fixed; a duplicate that is tidied away is a lie about the catalog.
         *
         * In practice this should touch nothing: the old lookup deduplicated by name, so a
         * journal written only by this app cannot hold two rows of one identity. It exists for
         * a catalog that arrived from a restored backup, or was edited by hand.
         *
         * ── And `hidden` ──────────────────────────────────────────────────────────
         * NOT NULL with false for every existing row, which is what they all are. It rides
         * along in the same table rebuild rather than waiting for a version of its own, because
         * the rebuild is the expensive part and doing it twice would buy nothing. Hiding is not
         * deleting — see [ExerciseEntity.hidden].
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TEMP TABLE `seq_before_v15` AS SELECT `name`, `seq` FROM `sqlite_sequence` " +
                        "WHERE `name` = 'exercises'"
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
                        "`led_by_protocol` INTEGER, " +
                        "`uid` TEXT NOT NULL, " +
                        "`one_sided` INTEGER NOT NULL, " +
                        "`bodyweight_share` REAL, " +
                        "`hidden` INTEGER NOT NULL, " +
                        "`identity_key` TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `_new_exercises` (`id`, `space_id`, `name`, `form`, `created_at`, " +
                        "`edge_mm`, `protocol_work_sec`, `protocol_rest_sec`, `default_rest_sec`, " +
                        "`led_by_protocol`, `uid`, `one_sided`, `bodyweight_share`, `hidden`, " +
                        "`identity_key`) " +
                        "SELECT `id`, `space_id`, `name`, `form`, `created_at`, `edge_mm`, " +
                        "`protocol_work_sec`, `protocol_rest_sec`, `default_rest_sec`, " +
                        "`led_by_protocol`, `uid`, `one_sided`, `bodyweight_share`, 0, '' " +
                        "FROM `exercises`"
                )
                db.execSQL("DROP TABLE `exercises`")
                db.execSQL("ALTER TABLE `_new_exercises` RENAME TO `exercises`")

                fillIdentityKeys(db)

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_exercises_space_id_id` " +
                        "ON `exercises` (`space_id`, `id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercises_uid` ON `exercises` (`uid`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercises_space_id_identity_key` " +
                        "ON `exercises` (`space_id`, `identity_key`)"
                )

                // delete-then-insert rather than INSERT OR REPLACE, for the reason given in
                // [MIGRATION_6_7]: `sqlite_sequence` carries no unique index on `name`
                db.execSQL(
                    "DELETE FROM `sqlite_sequence` WHERE `name` IN (SELECT `name` FROM `seq_before_v15`)"
                )
                db.execSQL(
                    "INSERT INTO `sqlite_sequence` (`name`, `seq`) SELECT `name`, `seq` FROM `seq_before_v15`"
                )
                db.execSQL("DROP TABLE `seq_before_v15`")
            }
        }

        /**
         * Version 15 -> 16: the day an entry belongs to becomes a column with an index, and the
         * moment a row was written stops being ambiguous.
         *
         * ── Three columns, all nullable, so this is an ALTER and not a rebuild ─────
         * Every rebuild in this file exists because some column had to be NOT NULL. None of
         * these does. `op_date` is genuinely absent on the reversing and correcting events (see
         * [EventEntity.opDate]); `ts_utc` and `tz_offset_min` are absent for a row whose `ts`
         * will not parse, which is not something this app can write but is something a merged
         * journal can hold. Making them NOT NULL would have meant inventing a value for exactly
         * the rows nothing is known about, and paying for it with a table rebuild of the one
         * table it would be worst to get wrong.
         *
         * The index is created afterwards, with the same name and shape Room generates from
         * [EventEntity], so an upgraded phone and a fresh install agree — data/SchemaParityTest
         * is what checks that they do.
         *
         * ── THE ZONE OF OLD ROWS IS AN ASSUMPTION, AND THIS IS IT ──────────────────
         * The stored `ts` is a local wall clock with no zone and no offset. There is NO OTHER
         * SOURCE — not in the row, not in the payload, not anywhere in the database — so every
         * existing row is read in THE DEVICE'S ZONE AS IT IS AT UPGRADE TIME. For a journal
         * written and upgraded in one place that is exactly right. For rows written abroad it is
         * wrong by however far the user travelled, silently, and nothing later can detect it.
         *
         * That is accepted rather than worked around, because the alternatives are worse:
         * leaving the old rows empty makes every reader carry a null branch forever for rows
         * that do have a defensible instant, and refusing to guess at all would mean the columns
         * describe only the future, which is a schema that answers "when did this happen" for
         * half the journal.
         *
         * ── The day comes out of the payload, and the key is spelled out ──────────
         * `op_date` is copied from the JSON each row already carries. The key is written here as
         * a literal rather than taken from a constant for the reason [MIGRATION_10_11] gives
         * about type strings: this describes data as it was written, and must not change meaning
         * if something in today's code is renamed. A payload that will not parse, or whose day
         * is not an ISO day, gets no column value and goes on being read out of the payload
         * exactly as it was before — a damaged row costs itself and never the upgrade.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `events` ADD COLUMN `op_date` TEXT")
                db.execSQL("ALTER TABLE `events` ADD COLUMN `ts_utc` TEXT")
                db.execSQL("ALTER TABLE `events` ADD COLUMN `tz_offset_min` INTEGER")

                val rows = ArrayList<Triple<Long, String, String>>()
                db.query("SELECT `id`, `ts`, `payload` FROM `events`").use { c ->
                    while (c.moveToNext()) {
                        rows += Triple(c.getLong(0), c.getString(1), c.getString(2))
                    }
                }
                val zone = java.time.ZoneId.systemDefault()
                // two statements rather than one with nulls in it: a row may have a day and no
                // readable time, or the other way round, and writing each only where there is
                // something to write keeps a null out of the bind arguments entirely
                for ((id, ts, payload) in rows) {
                    opDateOfPayload(payload)?.let { day ->
                        db.execSQL(
                            "UPDATE `events` SET `op_date` = ? WHERE `id` = ?",
                            arrayOf<Any>(day, id),
                        )
                    }
                    WriteTime.ofLocal(ts, zone)?.let { written ->
                        db.execSQL(
                            "UPDATE `events` SET `ts_utc` = ?, `tz_offset_min` = ? WHERE `id` = ?",
                            arrayOf<Any>(written.utc, written.offsetMin, id),
                        )
                    }
                }

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_events_space_id_op_date` " +
                        "ON `events` (`space_id`, `op_date`)"
                )
            }
        }

        /**
         * Version 16 -> 17: the length of ONE hold is now recorded on the set that used it.
         *
         * [xyz.oleolegka.gachimuchi.domain.HoldSet.holdSec] existed in the payload from the
         * start, but nothing ever wrote it — the entry card asked for added weight and reps and
         * never for how long a hang lasted, so every hold set in every journal has always
         * carried a null there. The cost was quiet: a protocol-led hold could still get a
         * length by falling back to its exercise's work:rest snapshot ([HoldSet.workSec], see
         * [xyz.oleolegka.gachimuchi.domain.holdSecondsUnderTension]), but the record axis
         * ([xyz.oleolegka.gachimuchi.domain.evaluateHoldRecord]) reads `hold_sec` ALONE and
         * never that fallback, so "hung longer" could not fire, ever, for any hold in any
         * journal — and a hold with no protocol at all (a plank) had no length recorded
         * anywhere, not even for the volume chart.
         *
         * ── The backfill, and its one honest limit ───────────────────────────────────
         * No column changes here — `hold_sec` is a payload field, not a table column — so this
         * is a payload rewrite exactly like [MIGRATION_13_14]'s body-weight snapshot. Every
         * existing `hold_set` row that carries a protocol snapshot (`work_sec`) and no
         * `hold_sec` of its own gets `hold_sec` set to that snapshot: for a protocol-led hold
         * the two ARE the same number by definition (§12-A), so this invents nothing.
         *
         * A hold with no protocol snapshot — the unweighted plank, an old hangboard set logged
         * before the exercise carried a protocol at all — is left exactly as it was. There is no
         * honest number to give it: nobody recorded how long that hold lasted, and inventing an
         * average would be training data the user never produced.
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val rewritten = ArrayList<Pair<Long, String>>()
                db.query(
                    "SELECT `id`, `payload` FROM `events` WHERE `type` = 'hold_set'"
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        withHoldSecFromProtocol(c.getString(1))?.let { rewritten += id to it }
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
         * Version 17 -> 18: the hangboard edge (the lip width, in mm) leaves the domain model
         * entirely, and the §12-A sibling switcher that compared exercises by it leaves with it.
         *
         * ── What was wrong ───────────────────────────────────────────────────────
         * Nothing was wrong with the column; the owner simply does not want it any more. It is
         * a climbing-specific value with no comparison this app has any business making — "it
         * lives in the name, I don't need edge-based comparison" — and keeping a column, an
         * identity dimension and a whole switcher screen around for a fact the app no longer
         * cares to model would be dead weight pretending to be a feature.
         *
         * ── Why FOLDING rather than DROPPING ─────────────────────────────────────
         * An edge on file is a number the user hand-recorded standing at the hangboard, and
         * dropping the column outright would erase it as though it had never been said. So
         * before the column goes, every row that has one gets it folded into its own NAME —
         * "Hangs" with a 20 mm edge becomes "Hangs 20mm" — unconditionally, for every row with
         * a non-null edge and not only the ones that are about to collide. It is a fact the user
         * recorded and it stays legible even for a row whose edge nothing else on the phone
         * shares. The number goes through [xyz.oleolegka.gachimuchi.domain.fmtNum] for the same
         * reason the old edge field's own comment gave: "20" rather than "20.0", an edge is
         * written the way it is spoken.
         *
         * ── Why RENAMING beats MERGING here too ──────────────────────────────────
         * Folding the edge into two different names can still produce a collision under the new
         * identity rule — a row already called "Hangs 20mm" with no edge of its own, and another
         * called "Hangs" with a 20 mm edge that has just been folded to the same words. Exactly
         * the argument [MIGRATION_14_15] makes for its own duplicates applies again: merging
         * would repoint sets inside payloads with nobody watching, and leaving one row's key
         * silently different from what it displays would hide a duplicate rather than resolve
         * it. So a collision here is broken the same way — by [freeExerciseName] appending
         * " (2)" — reusing [fillIdentityKeys] rather than a second mechanism, because "how a
         * clash is broken" is a rule this schema already has exactly one implementation of.
         *
         * ── Sequence, and why it is in this order ────────────────────────────────
         * 1. While `edge_mm` still exists, every row's `name` is updated in place, for every row
         *    where `edge_mm IS NOT NULL` — this has to happen before the column carrying the
         *    value is gone.
         * 2. The `exercises` table is rebuilt without `edge_mm`, following the exact
         *    `_new_exercises` pattern [MIGRATION_14_15] uses (temp `sqlite_sequence`
         *    preservation, `INSERT INTO _new_exercises SELECT ...`, `DROP TABLE`, rename,
         *    recreate indices, restore `sqlite_sequence`).
         * 3. [fillIdentityKeys] recomputes `identity_key` for every row under the NEW rule
         *    (name + form + protocol, no edge), resolving any collision the fold produced.
         *
         * ── What is deliberately NOT done here, and why ──────────────────────────
         * NO PAYLOAD REWRITE. [xyz.oleolegka.gachimuchi.domain.HoldSet.edgeMm] and
         * `PortableExercise.edgeMm` are removed from their Kotlin classes in this same change,
         * which stops this app from writing or reading `edge_mm` going forward; both JSON
         * configs already set `ignoreUnknownKeys`, so an event payload or a backup file still
         * carrying an `"edge_mm"` key from before this migration decodes exactly as before,
         * minus a field that is no longer asked for — inert, not corrupted. Unlike `hold_sec` in
         * [MIGRATION_16_17], nothing here reads that snapshot back out to compute anything, so
         * there is no defect to backfill and no honest number this migration could write into a
         * payload that was already complete for what it recorded.
         *
         * A separate Python bot is documented ([xyz.oleolegka.gachimuchi.domain.HoldSet]'s own
         * KDoc, before this change) to read and write this same payload shape; this app's own
         * exports diverging from what it still expects is a known, accepted consequence and is
         * out of scope for this repository.
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val edged = ArrayList<Pair<Long, String>>()
                db.query(
                    "SELECT `id`, `name`, `edge_mm` FROM `exercises` WHERE `edge_mm` IS NOT NULL"
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val name = c.getString(1)
                        val edge = c.getDouble(2)
                        edged += id to "$name ${fmtNum(edge)}mm"
                    }
                }
                for ((id, name) in edged) {
                    db.execSQL("UPDATE `exercises` SET `name` = ? WHERE `id` = ?", arrayOf<Any>(name, id))
                }

                db.execSQL(
                    "CREATE TEMP TABLE `seq_before_v18` AS SELECT `name`, `seq` FROM `sqlite_sequence` " +
                        "WHERE `name` = 'exercises'"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_exercises` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`space_id` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`form` INTEGER NOT NULL, " +
                        "`created_at` TEXT NOT NULL, " +
                        "`protocol_work_sec` REAL, " +
                        "`protocol_rest_sec` REAL, " +
                        "`default_rest_sec` INTEGER, " +
                        "`led_by_protocol` INTEGER, " +
                        "`uid` TEXT NOT NULL, " +
                        "`one_sided` INTEGER NOT NULL, " +
                        "`bodyweight_share` REAL, " +
                        "`hidden` INTEGER NOT NULL, " +
                        "`identity_key` TEXT NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO `_new_exercises` (`id`, `space_id`, `name`, `form`, `created_at`, " +
                        "`protocol_work_sec`, `protocol_rest_sec`, `default_rest_sec`, " +
                        "`led_by_protocol`, `uid`, `one_sided`, `bodyweight_share`, `hidden`, " +
                        "`identity_key`) " +
                        "SELECT `id`, `space_id`, `name`, `form`, `created_at`, " +
                        "`protocol_work_sec`, `protocol_rest_sec`, `default_rest_sec`, " +
                        "`led_by_protocol`, `uid`, `one_sided`, `bodyweight_share`, `hidden`, '' " +
                        "FROM `exercises`"
                )
                db.execSQL("DROP TABLE `exercises`")
                db.execSQL("ALTER TABLE `_new_exercises` RENAME TO `exercises`")

                fillIdentityKeys(db)

                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_exercises_space_id_id` " +
                        "ON `exercises` (`space_id`, `id`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercises_uid` ON `exercises` (`uid`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_exercises_space_id_identity_key` " +
                        "ON `exercises` (`space_id`, `identity_key`)"
                )

                // delete-then-insert rather than INSERT OR REPLACE, for the reason given in
                // [MIGRATION_6_7]: `sqlite_sequence` carries no unique index on `name`
                db.execSQL(
                    "DELETE FROM `sqlite_sequence` WHERE `name` IN (SELECT `name` FROM `seq_before_v18`)"
                )
                db.execSQL(
                    "INSERT INTO `sqlite_sequence` (`name`, `seq`) SELECT `name`, `seq` FROM `seq_before_v18`"
                )
                db.execSQL("DROP TABLE `seq_before_v18`")
            }
        }

        /**
         * One `hold_set` payload with `hold_sec` filled in from its own `work_sec` snapshot, or
         * null when there is nothing to do — `hold_sec` already stated, no protocol snapshot to
         * copy from, or a payload that will not parse.
         *
         * "Already stated" means a NUMBER already there, for the reason spelled out on
         * [withExerciseUid]: `encodeDefaults` writes `"hold_sec": null` for a build that knows
         * the field and has nothing to put in it, and reading that key's mere presence as "done"
         * would skip every row this migration exists for.
         */
        private fun withHoldSecFromProtocol(payload: String): String? {
            val json = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(payload)
            }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null

            val already = (json["hold_sec"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.toDoubleOrNull()
            if (already != null) return null

            val work = (json["work_sec"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.toDoubleOrNull()
            if (work == null || work <= 0) return null

            return kotlinx.serialization.json.JsonObject(
                json + ("hold_sec" to kotlinx.serialization.json.JsonPrimitive(work))
            ).toString()
        }

        /**
         * Gives every catalog row the key its own columns say it should have, renaming the
         * second row of a colliding pair so that the unique index can be created at all.
         *
         * In id order, so that the row that has been there longest keeps its name and the later
         * one is the one that is marked. Keys are tracked per profile because the index is, and
         * because two profiles are not each other's duplicates.
         *
         * Reads `name`, `form`, `protocol_work_sec` and `protocol_rest_sec` ONLY — no `edge_mm`,
         * since schema version 18 (see [MIGRATION_17_18]). That is [exerciseIdentityKey]'s
         * signature today, and this function's whole point is to key every row by TODAY's rule;
         * see [MIGRATION_14_15]'s own KDoc for why that is deliberate rather than pinned to
         * whatever the calling migration's version happened to look like.
         */
        private fun fillIdentityKeys(db: SupportSQLiteDatabase) {
            data class Row(
                val id: Long,
                val spaceId: Long,
                val name: String,
                val form: Int,
                val work: Double?,
                val rest: Double?,
            )

            val rows = ArrayList<Row>()
            db.query(
                "SELECT `id`, `space_id`, `name`, `form`, `protocol_work_sec`, " +
                    "`protocol_rest_sec` FROM `exercises` ORDER BY `id`"
            ).use { c ->
                while (c.moveToNext()) {
                    rows += Row(
                        id = c.getLong(0),
                        spaceId = c.getLong(1),
                        name = c.getString(2),
                        form = c.getInt(3),
                        work = if (c.isNull(4)) null else c.getDouble(4),
                        rest = if (c.isNull(5)) null else c.getDouble(5),
                    )
                }
            }

            val taken = HashMap<Long, MutableSet<String>>()
            for (row in rows) {
                val used = taken.getOrPut(row.spaceId) { HashSet() }
                val name = freeExerciseName(row.name, row.form, row.work, row.rest, used)
                val key = exerciseIdentityKey(name, row.form, row.work, row.rest)
                used += key
                db.execSQL(
                    "UPDATE `exercises` SET `name` = ?, `identity_key` = ? WHERE `id` = ?",
                    arrayOf<Any>(name, key, row.id),
                )
            }
        }

        /**
         * One set payload with `bodyweight_kg` filled in from the weigh-ins, or null when there
         * is nothing to do — not an own-weight set, a snapshot already there, no weigh-in on or
         * before its day, or a payload that will not parse.
         *
         * "A snapshot already there" means a NUMBER already there and not a KEY already there,
         * for the reason spelled out on [withExerciseUid]: `encodeDefaults` writes
         * `"bodyweight_kg": null` for a build that knows the field and has nothing to put in
         * it, and reading that as "done" would skip exactly the rows this exists for.
         */
        private fun withBodyweightSnapshot(
            payload: String,
            byDay: List<Pair<String, Double>>,
        ): String? {
            val json = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(payload)
            }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null

            val ownWeight = (json["own_weight"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.toBooleanStrictOrNull() ?: false
            if (!ownWeight) return null

            val already = (json["bodyweight_kg"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.toDoubleOrNull()
            if (already != null) return null

            val day = (json["op_date"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull ?: return null
            val kg = byDay.lastOrNull { it.first <= day }?.second ?: return null

            return kotlinx.serialization.json.JsonObject(
                json + ("bodyweight_kg" to kotlinx.serialization.json.JsonPrimitive(kg))
            ).toString()
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
            MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
            MIGRATION_16_17, MIGRATION_17_18,
        )

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "gachimuchi.db",
            ).addMigrations(*MIGRATIONS).build().also { instance = it }
        }
    }
}
