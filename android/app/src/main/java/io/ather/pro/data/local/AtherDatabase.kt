package io.ather.pro.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TripEntity::class,
        TelemetrySampleEntity::class,
        TripBaselineEntity::class,
        MetaEntity::class,
        RideSampleEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AtherDatabase : RoomDatabase() {
    abstract fun dashboardDao(): DashboardDao

    companion object {
        private const val DB_NAME = "ather_dashboard.db"

        @Volatile
        private var INSTANCE: AtherDatabase? = null

        fun getInstance(context: Context): AtherDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AtherDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `ride_history` (`timestamp` INTEGER NOT NULL, `speedKmh` REAL, `odometerKm` REAL, `rangeKm` REAL, PRIMARY KEY(`timestamp`))")
            }
        }

        /** Test helper — clears singleton between JVM tests. */
        internal fun clearInstanceForTests() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
