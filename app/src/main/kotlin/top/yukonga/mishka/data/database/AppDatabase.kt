package top.yukonga.mishka.data.database

import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 改 [version] 是三件事一起做：写 Migration → 在 `AppDatabaseBuilder` 的 `addMigrations`
 * 注册 → 跑一次 `:app:assembleDebug` 让 KSP 把新 schema 导出到 `app/schemas/` 并提交。
 * 少任一步都不会在构建期报错。
 */
@Database(
    entities = [ImportedEntity::class, PendingEntity::class, SelectionEntity::class],
    version = 4,
)
@ColumnTypeConverters(ProfileTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun importedDao(): ImportedDao
    abstract fun pendingDao(): PendingDao
    abstract fun selectionDao(): SelectionDao
}

// v2: 为 imported / pending 增加 userAgent 列（per-profile UA 覆写）
val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE imported ADD COLUMN userAgent TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE pending ADD COLUMN userAgent TEXT NOT NULL DEFAULT ''")
    }
}

// v3: 为 imported / pending 增加 ageSecretKey 列（per-profile age 解密密钥）
val MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE imported ADD COLUMN ageSecretKey TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE pending ADD COLUMN ageSecretKey TEXT NOT NULL DEFAULT ''")
    }
}

// v4: 为每个订阅保存脚本/YAML 覆写的选择顺序。
val MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE imported ADD COLUMN overrideIds TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE imported ADD COLUMN overrideSortPreference TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE pending ADD COLUMN overrideIds TEXT NOT NULL DEFAULT ''")
        connection.execSQL("ALTER TABLE pending ADD COLUMN overrideSortPreference TEXT NOT NULL DEFAULT ''")
    }
}
