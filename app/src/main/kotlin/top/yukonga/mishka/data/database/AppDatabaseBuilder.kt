package top.yukonga.mishka.data.database

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

@Volatile
private var INSTANCE: AppDatabase? = null

fun getAppDatabase(context: Context): AppDatabase {
    return INSTANCE ?: synchronized(AppDatabase::class) {
        INSTANCE ?: Room.databaseBuilder(
            context.applicationContext,
            AppDatabase::class.java,
            context.getDatabasePath("mishka.db").absolutePath,
        )
            .setDriver(BundledSQLiteDriver())
            // 新增的 MIGRATION 必须补进这里：漏注册对全新安装毫无影响（建表走当前 schema），
            // 升级用户首次启动即 crash
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
            .also { INSTANCE = it }
    }
}
