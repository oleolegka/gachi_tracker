package xyz.oleolegka.gachimuchi.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The local database (SQLite via Room). The schema repeats the server one (`bot/db.py`):
 * append-only journal + catalog + aliases + slots, plus the local-only program tables
 * added in version 2.
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
        AliasEntity::class,
        SlotEntity::class,
        SlotExerciseEntity::class,
        ProgramEntity::class,
        ProgramGroupEntity::class,
        ProgramBlockEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun exercises(): ExerciseDao
    abstract fun aliases(): AliasDao
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
         * Purely additive and both defaulted, so every existing program comes through as
         * "not linked, no heading", which is exactly what it was. No foreign key is declared
         * on the link — see [ProgramEntity] for why a hand-written protocol must not be
         * deletable by way of the catalog.
         *
         * `category` is NOT NULL with a default because the entity declares a non-null
         * String: Room compares the database against the entities on the next open, and a
         * nullable column here would fail that check rather than fail quietly.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `programs` ADD COLUMN `exercise_id` INTEGER")
                db.execSQL("ALTER TABLE `programs` ADD COLUMN `category` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Version 3 -> 4: the demo-seed marker on the catalog, the aliases and the slots.
         *
         * Purely additive: one boolean column per table, NOT NULL with a default of 0, so
         * every row already on the phone reads as "the user's own" — which is the safe
         * answer, because the only thing this flag is ever used for is DELETION.
         *
         * That default is also why the migration does not try to be clever and stamp the
         * demo rows an older build wrote. It cannot tell them apart from the user's: the
         * seed's exercises are deduplicated by name, so a hand-made "Bench press" and a
         * seeded one are the same row shape, and a migration that guessed wrong would
         * quietly arm a delete button against real data. Recognising that older demo data is
         * done at wipe time instead (data/seed/DemoCleanup.kt), where the journal can be
         * consulted and a row that carries real records can be spared.
         *
         * NOT NULL with a default rather than nullable, because [ExerciseEntity] and friends
         * declare a non-null Boolean: Room compares the database against the entities on the
         * next open and a nullable column here would fail that check.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("exercises", "aliases", "slots")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `$COLUMN_SEEDED` INTEGER NOT NULL DEFAULT 0")
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

        val MIGRATIONS: Array<Migration> =
            arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "gachimuchi.db",
            ).addMigrations(*MIGRATIONS).build().also { instance = it }
        }
    }
}
