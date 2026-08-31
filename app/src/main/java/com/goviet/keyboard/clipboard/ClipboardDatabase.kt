package com.goviet.keyboard.clipboard

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "clipboard_items")
data class ClipboardEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface ClipboardDao {
    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC LIMIT 30")
    fun getAll(): Flow<List<ClipboardEntity>>

    @Query("SELECT * FROM clipboard_items ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatest(): ClipboardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ClipboardEntity)

    @Delete
    suspend fun delete(item: ClipboardEntity)

    @Query("DELETE FROM clipboard_items WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM clipboard_items WHERE text = :text")
    suspend fun deleteByText(text: String)

    @Query("DELETE FROM clipboard_items")
    suspend fun clearAll()
}

@Database(entities = [ClipboardEntity::class], version = 1, exportSchema = false)
abstract class ClipboardDatabase : RoomDatabase() {
    abstract fun clipboardDao(): ClipboardDao

    companion object {
        @Volatile
        private var INSTANCE: ClipboardDatabase? = null

        fun getInstance(context: Context): ClipboardDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ClipboardDatabase::class.java,
                    "clipboard_database"
                )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class ClipboardRepository(private val clipboardDao: ClipboardDao) {
    private val mutex = Mutex()

    val allClipboardItems: Flow<List<ClipboardEntity>> = clipboardDao.getAll()

    suspend fun insert(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            mutex.withLock {
                val latest = clipboardDao.getLatest()
                val currentLatestText = latest?.text?.trim()

                if (trimmed == currentLatestText) {
                    return
                }

                clipboardDao.deleteByText(trimmed)
                clipboardDao.insert(ClipboardEntity(text = trimmed, timestamp = System.currentTimeMillis()))
            }
        }
    }

    suspend fun delete(item: ClipboardEntity) {
        mutex.withLock {
            clipboardDao.delete(item)
        }
    }

    suspend fun deleteById(id: Int) {
        mutex.withLock {
            clipboardDao.deleteById(id)
        }
    }

    suspend fun deleteByText(text: String) {
        mutex.withLock {
            clipboardDao.deleteByText(text)
        }
    }

    suspend fun clear() {
        mutex.withLock {
            clipboardDao.clearAll()
        }
    }
}
