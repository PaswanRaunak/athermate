package io.ather.pro.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TripEntity::class,
        TelemetrySampleEntity::class,
        TripBaselineEntity::class,
        MetaEntity::class
    ],
    version = 1,
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
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /** Test helper — clears singleton between JVM tests. */
        internal fun clearInstanceForTests() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}
