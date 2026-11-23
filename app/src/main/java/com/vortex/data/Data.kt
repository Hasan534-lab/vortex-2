package com.vortex.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val lastOpenedAt: Long,
    val initialSetupDone: Boolean = false,
)

/** Logged = value the operator typed in as the real mixer value. Ask = value the controller requested. */
@Entity(tableName = "control_values", primaryKeys = ["projectId", "controlKey"])
data class ValueEntity(val projectId: Long, val controlKey: String, val logged: Double?, val ask: Double?)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val controlKey: String?,
    val section: String,
    val text: String,
    val status: String,   // PENDING, APPLIED, TEXT
    val sender: String,   // operator or controller
    val time: Long,
)

@Entity(tableName = "vocal_lines")
data class VocalLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val name: String,
    val position: Int,
)

/** Requests made while the Wi-Fi link was down. They are sent when the link comes back. */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val payload: String,
)

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY lastOpenedAt DESC") fun all(): Flow<List<ProjectEntity>>
    @Insert suspend fun insert(p: ProjectEntity): Long
    @Query("UPDATE projects SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("UPDATE projects SET lastOpenedAt = :t WHERE id = :id") suspend fun touch(id: Long, t: Long)
    @Query("DELETE FROM projects WHERE id = :id") suspend fun delete(id: Long)

    @Query("SELECT * FROM control_values WHERE projectId = :p") suspend fun values(p: Long): List<ValueEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertValue(v: ValueEntity)
    @Query("DELETE FROM control_values WHERE projectId = :p AND controlKey = :k") suspend fun deleteValue(p: Long, k: String)
    @Query("DELETE FROM control_values WHERE projectId = :p") suspend fun deleteValues(p: Long)

    @Query("SELECT * FROM messages WHERE projectId = :p ORDER BY time") suspend fun messages(p: Long): List<MessageEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMessage(m: MessageEntity): Long
    @Update suspend fun updateMessage(m: MessageEntity)
    @Query("DELETE FROM messages WHERE projectId = :p") suspend fun deleteMessages(p: Long)

    @Query("SELECT * FROM vocal_lines WHERE projectId = :p ORDER BY position") suspend fun vocalLines(p: Long): List<VocalLineEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVocal(v: VocalLineEntity): Long
    @Update suspend fun updateVocal(v: VocalLineEntity)
    @Query("DELETE FROM vocal_lines WHERE id = :id") suspend fun deleteVocal(id: Long)
    @Query("DELETE FROM vocal_lines WHERE projectId = :p") suspend fun deleteVocals(p: Long)
}

@Database(
    entities = [ProjectEntity::class, ValueEntity::class, MessageEntity::class, VocalLineEntity::class, OutboxEntity::class],
    version = 3, exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projects(): ProjectDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "vortex.db")
                .fallbackToDestructiveMigration() // development only: stage 1 data is dropped on upgrade
                .build().also { instance = it }
        }
    }
}
