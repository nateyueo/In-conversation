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
    val mastery: String = Mastery.UNFAMILIAR.name, val reviewCount: Int = 0,
    val lastReviewedAt: Long? = null
)

@Entity(tableName = "content_manifest")
data class ContentManifest(@PrimaryKey val datasetId: String, val contentVersion: Int, val entryCount: Int)

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries WHERE id = :id") suspend fun get(id: String): EntryEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(item: EntryEntity)
    @Update suspend fun update(item: EntryEntity)
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
    @Query("SELECT u.* FROM user_entries u JOIN entries e ON e.id=u.entryId WHERE e.kind=:kind")
    suspend fun inMode(kind: String): List<UserEntryEntity>
    // Read-modify-write is serialized in a Room transaction by the repository.
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN recordVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE entries ADD COLUMN contentVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE entries ADD COLUMN tagsJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE entries ADD COLUMN sourcesJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("CREATE TABLE IF NOT EXISTS content_manifest (datasetId TEXT NOT NULL, contentVersion INTEGER NOT NULL, entryCount INTEGER NOT NULL, PRIMARY KEY(datasetId))")
        // v1 samples are original demonstrations; quote presence never proves a film source.
        db.execSQL("UPDATE entries SET workTitle='言间自制原创演示' WHERE isSample=1")
    }
}

@Database(entities = [EntryEntity::class, UserEntryEntity::class, ContentManifest::class], version = 2, exportSchema = true)
abstract class YanjianDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun userEntryDao(): UserEntryDao
    companion object {
        @Volatile private var instance: YanjianDatabase? = null
        fun get(context: Context): YanjianDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, YanjianDatabase::class.java, "yanjian-local.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}

class UserRepository(private val db: YanjianDatabase) {
    suspend fun favorite(id: String): UserEntryEntity = db.withTransaction {
        val old = db.userEntryDao().get(id) ?: UserEntryEntity(id)
        old.copy(isFavorite = !old.isFavorite).also { db.userEntryDao().put(it) }
    }
    suspend fun hide(id: String) = db.withTransaction {
        val old = db.userEntryDao().get(id) ?: UserEntryEntity(id)
        db.userEntryDao().put(old.copy(isHidden = true))
    }
    suspend fun rate(id: String, grade: Mastery) = db.withTransaction {
        val old = db.userEntryDao().get(id) ?: UserEntryEntity(id)
        db.userEntryDao().put(old.copy(isFavorite = true, mastery = grade.name,
            reviewCount = old.reviewCount + 1, lastReviewedAt = System.currentTimeMillis()))
    }
}
