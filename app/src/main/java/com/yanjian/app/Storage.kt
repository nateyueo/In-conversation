package com.yanjian.app

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery

@Entity(tableName = "entries", indices = [Index(value = ["kind"]), Index(value = ["text"])])
data class EntryEntity(
    @PrimaryKey val id: String,
    val kind: String, val text: String, val meaning: String,
    val workTitle: String, val quote: String, val category: String,
    val genre: String, val context: String, val isSample: Boolean = true,
    @ColumnInfo(defaultValue = "0") val recordVersion: Int = 0,
    @ColumnInfo(defaultValue = "0") val contentVersion: Int = 0,
    @ColumnInfo(defaultValue = "'[]'") val tagsJson: String = "[]",
    @ColumnInfo(defaultValue = "'[]'") val sourcesJson: String = "[]"
)

@Entity(tableName = "user_entries", primaryKeys = ["entryId"])
data class UserEntryEntity(
    val entryId: String, val isFavorite: Boolean = false, val isHidden: Boolean = false,
    val mastery: String = Mastery.NEW.name, val reviewCount: Int = 0,
    val lastReviewedAt: Long? = null,
    val collectedAt: Long? = null, val dueAt: Long? = null,
    @ColumnInfo(defaultValue = "'demo-1'") val schedulerVersion: String = "demo-1",
    @ColumnInfo(defaultValue = "0") val revision: Long = 0
)

@Entity(tableName = "content_manifest")
data class ContentManifest(@PrimaryKey val datasetId: String, val contentVersion: Int, val entryCount: Int)

// JSON records hold versioned session drafts, import journals, and interface snapshots.
@Entity(tableName = "app_records")
data class AppRecord(@PrimaryKey val key: String, val type: String, val json: String)

@Entity(tableName = "activity_events", indices = [Index(value = ["kind", "localDate"])])
data class ActivityEvent(
    @PrimaryKey val eventId: String, val entryId: String, val kind: String, val type: String,
    val occurredAt: Long, val localDate: String, val zoneId: String,
    val sessionId: String = "", val previousState: String = "", val nextState: String = "",
    val undoneAt: Long? = null
)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE id = :id") suspend fun get(id: String): EntryEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(item: EntryEntity)
    @Update suspend fun update(item: EntryEntity)
    @Query("DELETE FROM entries WHERE id LIKE 'personal:%'") suspend fun deletePersonal()
    @Query("DELETE FROM entries WHERE id=:id AND id LIKE 'personal:%'") suspend fun deletePersonal(id: String)
    @Query("SELECT * FROM entries WHERE id LIKE 'personal:%'") suspend fun personal(): List<EntryEntity>
    @Query("SELECT e.* FROM entries e JOIN user_entries u ON e.id=u.entryId WHERE u.isFavorite=1 AND e.kind=:kind ORDER BY e.id LIMIT :limit OFFSET :offset")
    suspend fun userContent(kind: String, limit: Int, offset: Int): List<EntryEntity>
    @RawQuery suspend fun query(query: SupportSQLiteQuery): List<EntryEntity>
    @Query("SELECT * FROM content_manifest WHERE datasetId='yanjian-text'") suspend fun manifest(): ContentManifest?
    @Upsert suspend fun activate(manifest: ContentManifest)
}

@Dao
interface UserEntryDao {
    @Query("SELECT * FROM user_entries WHERE entryId=:id") suspend fun get(id: String): UserEntryEntity?
    @Query("SELECT * FROM user_entries") suspend fun all(): List<UserEntryEntity>
    @Upsert suspend fun put(item: UserEntryEntity)
    @Query("DELETE FROM user_entries") suspend fun clear()
    @Query("DELETE FROM user_entries WHERE entryId=:id") suspend fun delete(id: String)
    @Query("SELECT u.* FROM user_entries u JOIN entries e ON e.id=u.entryId WHERE e.kind=:kind")
    suspend fun inMode(kind: String): List<UserEntryEntity>
}

@Dao
interface StateDao {
    @Query("SELECT * FROM app_records WHERE `key`=:key") suspend fun get(key: String): AppRecord?
    @Query("SELECT * FROM app_records") suspend fun records(): List<AppRecord>
    @Upsert suspend fun put(record: AppRecord)
    @Query("DELETE FROM app_records WHERE `key`=:key") suspend fun delete(key: String)
    @Query("DELETE FROM app_records") suspend fun clearRecords()
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun event(event: ActivityEvent)
    @Query("SELECT * FROM activity_events WHERE eventId=:id") suspend fun event(id: String): ActivityEvent?
    @Query("SELECT * FROM activity_events") suspend fun events(): List<ActivityEvent>
    @Update suspend fun update(event: ActivityEvent)
    @Query("DELETE FROM activity_events") suspend fun clearEvents()
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN recordVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE entries ADD COLUMN contentVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE entries ADD COLUMN tagsJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE entries ADD COLUMN sourcesJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("CREATE TABLE IF NOT EXISTS content_manifest (datasetId TEXT NOT NULL, contentVersion INTEGER NOT NULL, entryCount INTEGER NOT NULL, PRIMARY KEY(datasetId))")
        db.execSQL("UPDATE entries SET workTitle='言间自制原创演示' WHERE isSample=1")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_entries ADD COLUMN collectedAt INTEGER")
        db.execSQL("ALTER TABLE user_entries ADD COLUMN dueAt INTEGER")
        db.execSQL("ALTER TABLE user_entries ADD COLUMN schedulerVersion TEXT NOT NULL DEFAULT 'demo-1'")
        db.execSQL("ALTER TABLE user_entries ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE user_entries SET mastery='NEW' WHERE reviewCount=0")
        // Historical missing dates remain unknown. This is an explicitly simplified schedule.
        db.execSQL("UPDATE user_entries SET dueAt=lastReviewedAt + CASE mastery WHEN 'KNOWN' THEN 604800000 WHEN 'FAMILIAR' THEN 259200000 ELSE 86400000 END WHERE lastReviewedAt IS NOT NULL AND reviewCount>0")
        db.execSQL("CREATE TABLE IF NOT EXISTS app_records (`key` TEXT NOT NULL, type TEXT NOT NULL, json TEXT NOT NULL, PRIMARY KEY(`key`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS activity_events (eventId TEXT NOT NULL, entryId TEXT NOT NULL, kind TEXT NOT NULL, type TEXT NOT NULL, occurredAt INTEGER NOT NULL, localDate TEXT NOT NULL, zoneId TEXT NOT NULL, sessionId TEXT NOT NULL, previousState TEXT NOT NULL, nextState TEXT NOT NULL, undoneAt INTEGER, PRIMARY KEY(eventId))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_events_kind_localDate ON activity_events(kind, localDate)")
    }
}

@Database(entities = [EntryEntity::class, UserEntryEntity::class, ContentManifest::class, AppRecord::class, ActivityEvent::class], version = 3, exportSchema = true)
abstract class YanjianDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun userEntryDao(): UserEntryDao
    abstract fun stateDao(): StateDao
    companion object {
        @Volatile private var instance: YanjianDatabase? = null
        fun get(context: Context): YanjianDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, YanjianDatabase::class.java, "yanjian-local.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}

class UserRepository(private val db: YanjianDatabase) {
    suspend fun favorite(id: String): UserEntryEntity = db.withTransaction {
        val old = db.userEntryDao().get(id) ?: UserEntryEntity(id)
        val next = old.copy(isFavorite = !old.isFavorite, collectedAt = if (!old.isFavorite) System.currentTimeMillis() else old.collectedAt, revision = old.revision + 1)
        db.userEntryDao().put(next)
        if (next.isFavorite) db.entryDao().get(id)?.let { db.stateDao().event(newEvent(it, "favorite")) }
        next
    }
    suspend fun hide(id: String) = db.withTransaction {
        val old = db.userEntryDao().get(id) ?: UserEntryEntity(id)
        db.userEntryDao().put(old.copy(isHidden = true, revision = old.revision + 1))
    }
    suspend fun rate(id: String, grade: Mastery) = db.withTransaction {
        val old = db.userEntryDao().get(id) ?: UserEntryEntity(id)
        db.userEntryDao().put(scored(old, grade))
    }
}
