package xyz.oleolegka.gachimuchi.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The local database (SQLite via Room). The schema repeats the server one (`bot/db.py`):
 * append-only journal + catalog + aliases + slots.
 *
 * There is NO encryption (SQLCipher) here yet, unlike on the server: on the phone the
 * database sits in the app's internal storage, and the key would have to be kept right
 * next to it anyway. If it is ever needed, that is a separate step (SQLCipher for
 * Android, or a Keystore-wrapped key).
 */
@Database(
    entities = [EventEntity::class, ExerciseEntity::class, AliasEntity::class, SlotEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun exercises(): ExerciseDao
    abstract fun aliases(): AliasDao
    abstract fun slots(): SlotDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "gachimuchi.db",
            ).build().also { instance = it }
        }
    }
}
