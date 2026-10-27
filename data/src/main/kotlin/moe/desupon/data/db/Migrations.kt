package moe.desupon.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object Migrations {
    /** Subscription sets, global selection state and disabled nodes; `|`-joined URLs split into primary and mirrors. */
    val V1_V2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `subscription` ADD COLUMN `mirrors` TEXT")
            db.execSQL("ALTER TABLE `subscription` ADD COLUMN `updateIntervalOverrideH` INTEGER")
            splitJoinedUrls(db)
            db.execSQL("CREATE TABLE IF NOT EXISTS `sub_set` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `position` INTEGER NOT NULL)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sub_set_member` (`setId` INTEGER NOT NULL, `subId` INTEGER NOT NULL, PRIMARY KEY(`setId`, `subId`), " +
                    "FOREIGN KEY(`setId`) REFERENCES `sub_set`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`subId`) REFERENCES `subscription`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sub_set_member_subId` ON `sub_set_member` (`subId`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `selection_state` (`slot` INTEGER NOT NULL, `activeSetId` INTEGER NOT NULL, " +
                    "`location` TEXT NOT NULL, `pinCountry` TEXT, PRIMARY KEY(`slot`))",
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS `disabled_node` (`nodeId` TEXT NOT NULL, PRIMARY KEY(`nodeId`))")
        }
    }

    /** Priority chains per set. */
    val V2_V3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sub_chain` (`setId` INTEGER NOT NULL, `subId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`setId`, `subId`), FOREIGN KEY(`subId`) REFERENCES `subscription`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sub_chain_subId` ON `sub_chain` (`subId`)")
        }
    }

    /**
     * Staged probe statistics. Old RTTs timed a cold connection, so they stay only until the first new sample replaces them
     * (rttSamples 0); recent counters start from the lifetime ones.
     */
    val V3_V4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (column in listOf(
                "`ewmaConnectMs` REAL NOT NULL DEFAULT 0",
                "`madMs` REAL NOT NULL DEFAULT 0",
                "`lastFailAt` INTEGER",
                "`okRecent` REAL NOT NULL DEFAULT 0",
                "`failRecent` REAL NOT NULL DEFAULT 0",
                "`rttSamples` INTEGER NOT NULL DEFAULT 0",
                "`ewmaKbps` REAL",
                "`throttled` INTEGER NOT NULL DEFAULT 0",
            )) {
                db.execSQL("ALTER TABLE `probe_stat` ADD COLUMN $column")
            }
            db.execSQL("UPDATE `probe_stat` SET `okRecent` = MIN(`ok`, 5), `failRecent` = MIN(`failStreak`, 5)")
        }
    }

    /** Row kinds: pasted local subscriptions become lists; configs keep their text in the row. */
    val V4_V5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `subscription` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'SUBSCRIPTION'")
            db.execSQL("ALTER TABLE `subscription` ADD COLUMN `source` TEXT")
            db.execSQL("UPDATE `subscription` SET `kind` = 'LIST' WHERE `local` = 1")
        }
    }

    val ALL = arrayOf(V1_V2, V2_V3, V3_V4, V4_V5)

    private fun splitJoinedUrls(db: SupportSQLiteDatabase) {
        val rows = ArrayList<Pair<Long, String>>()
        db.query("SELECT `id`, `url` FROM `subscription` WHERE `url` LIKE '%|%'").use { c ->
            while (c.moveToNext()) rows += c.getLong(0) to c.getString(1)
        }
        for ((id, joined) in rows) {
            val parts = joined.split('|').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val primary = parts.firstOrNull() ?: continue
            val mirrors = parts.drop(1).joinToString("\n").ifEmpty { null }
            db.execSQL("UPDATE `subscription` SET `url` = ?, `mirrors` = ? WHERE `id` = ?", arrayOf<Any?>(primary, mirrors, id))
        }
    }
}
