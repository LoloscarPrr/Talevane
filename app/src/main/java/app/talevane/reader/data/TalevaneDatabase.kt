package app.talevane.reader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BookEntity::class], version = 1, exportSchema = false)
abstract class TalevaneDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    companion object {
        @Volatile private var instance: TalevaneDatabase? = null
        fun get(context: Context): TalevaneDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TalevaneDatabase::class.java, "talevane.db").build().also { instance = it }
        }
    }
}
