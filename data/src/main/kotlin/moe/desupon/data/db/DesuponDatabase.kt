package moe.desupon.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import moe.desupon.data.split.RuDefaults
import java.io.File

@Database(
    entities = [
        SubscriptionEntity::class,
        NodeEntity::class,
        NodeSubCrossRef::class,
        ProbeStatEntity::class,
        NetworkMemoryEntity::class,
        SplitRuleEntity::class,
        TierLearningEntity::class,
        SubSetEntity::class,
        SubSetMemberEntity::class,
        SelectionStateEntity::class,
        DisabledNodeEntity::class,
        SubChainEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
internal abstract class DesuponDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao

    abstract fun nodeDao(): NodeDao

    abstract fun probeStatDao(): ProbeStatDao

    abstract fun networkMemoryDao(): NetworkMemoryDao

    abstract fun splitRuleDao(): SplitRuleDao

    abstract fun tierLearningDao(): TierLearningDao

    abstract fun subSetDao(): SubSetDao

    abstract fun selectionDao(): SelectionDao

    companion object {
        const val FILE_NAME = "desupon.db"

        fun create(context: Context): DesuponDatabase =
            Room.databaseBuilder(context, DesuponDatabase::class.java, File(context.noBackupFilesDir, FILE_NAME).absolutePath)
                .addCallback(SeedDefaults)
                .addMigrations(*Migrations.ALL)
                .build()
    }
}

/** Seeds the RU split rules once, when the database file is created. */
internal object SeedDefaults : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        for (rule in RuDefaults.rules) {
            val row = ContentValues().apply {
                put("position", rule.order)
                put("enabled", if (rule.enabled) 1 else 0)
                put("kind", rule.kind.name)
                put("value", rule.value)
                put("action", rule.action.name)
            }
            db.insert("split_rule", SQLiteDatabase.CONFLICT_ABORT, row)
        }
    }
}
