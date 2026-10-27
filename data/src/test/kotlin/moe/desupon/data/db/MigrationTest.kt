package moe.desupon.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.desupon.data.repo.NodeRepository
import moe.desupon.data.repo.SelectionRepository
import moe.desupon.model.node.NodeId
import moe.desupon.model.selection.LocationChoice
import moe.desupon.model.selection.SetId
import moe.desupon.model.subscription.SubId
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Builds an exported schema, so the test exercises exactly what is on the device. */
    private fun createSchema(file: File, version: Int): SQLiteDatabase {
        val schema = Json.parseToJsonElement(File(SCHEMA_DIR, "$version.json").readText()).jsonObject.getValue("database").jsonObject
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        for (entity in schema.getValue("entities").jsonArray) {
            val e = entity.jsonObject
            val table = e.getValue("tableName").jsonPrimitive.content
            db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach { db.execSQL(it.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
        }
        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
        db.version = version
        return db
    }

    private fun open(file: File): DesuponDatabase =
        Room.databaseBuilder(ApplicationProvider.getApplicationContext(), DesuponDatabase::class.java, file.absolutePath)
            .addMigrations(*Migrations.ALL)
            .allowMainThreadQueries()
            .build()

    private fun createV1(file: File) {
        val db = createSchema(file, 1)
        db.execSQL(
            "INSERT INTO subscription (id, url, local, hwid, createdAt) VALUES " +
                "(1, 'https://a.example.com/s/tok | https://b.example.com/s/tok|https://a.example.com/s/tok', 0, 'h1', 1), " +
                "(2, 'https://c.example.com/s/tok', 0, 'h2', 2), (3, '', 1, 'h3', 3)",
        )
        db.execSQL(
            "INSERT INTO node (id, protocol, displayName, country, capability, outboundJson, isEndpoint, udpOnly, labels) " +
                "VALUES ('n1', 'TROJAN', 'NL-1', 'NL', 'SUPPORTED', '{}', 0, 0, '')",
        )
        db.execSQL("INSERT INTO node_sub (nodeId, subId, position) VALUES ('n1', 1, 0)")
        db.execSQL(
            "INSERT INTO probe_stat (nodeId, networkKey, ewmaRttMs, ok, fail, failStreak, updatedAt) VALUES ('n1', 'wifi|k', 50.0, 1, 0, 0, 1)",
        )
        db.close()
    }

    @Test
    fun version2MigratesWithSetsIntactAndNoChains() = runTest {
        val file = File(tmp.root, "desupon.db")
        createSchema(file, 2).apply {
            execSQL("INSERT INTO subscription (id, url, local, hwid, createdAt) VALUES (1, 'https://a.example.com/s/tok', 0, 'h1', 1), (2, '', 1, 'h2', 2)")
            execSQL("INSERT INTO sub_set (id, name, position) VALUES (5, 'X', 0)")
            execSQL("INSERT INTO sub_set_member (setId, subId) VALUES (5, 1), (5, 2)")
            execSQL("INSERT INTO selection_state (slot, activeSetId, location, pinCountry) VALUES (0, 5, 'country:NL', NULL)")
            close()
        }
        val db = open(file)
        try {
            val selection = SelectionRepository(db, NodeRepository(db.nodeDao()))
            val x = SetId(5)
            assertEquals(x, selection.activeSetId.first())
            assertEquals(LocationChoice.Country("NL"), selection.location.first())
            val sets = selection.subscriptionSets.first()
            assertEquals(setOf(SubId(1), SubId(2)), sets.first { it.set.id == x }.set.members)
            assertTrue(sets.all { it.set.chain.isEmpty() })
            assertTrue(selection.setChain(x, listOf(SubId(2), SubId(1))))
            assertEquals(listOf(SubId(2), SubId(1)), selection.selection.first().chain)
        } finally {
            db.close()
        }
    }

    @Test
    fun version1MigratesWithDataIntact() = runTest {
        val file = File(tmp.root, "desupon.db")
        createV1(file)
        val db = open(file)
        try {
            val subs = db.subscriptionDao().getAll().associateBy { it.id }
            assertEquals("https://a.example.com/s/tok", subs.getValue(1).url)
            assertEquals("https://b.example.com/s/tok", subs.getValue(1).mirrors)
            assertEquals("https://c.example.com/s/tok", subs.getValue(2).url)
            assertNull(subs.getValue(2).mirrors)
            assertTrue(subs.getValue(3).local)
            assertEquals("LIST", subs.getValue(3).kind)
            assertEquals("SUBSCRIPTION", subs.getValue(1).kind)
            assertNull(subs.getValue(1).updateIntervalOverrideH)
            assertEquals(1, db.probeStatDao().forNetwork("wifi|k").size)

            val nodes = NodeRepository(db.nodeDao())
            val selection = SelectionRepository(db, nodes)
            assertEquals(SetId.ALL, selection.activeSetId.first())
            val set = selection.createSet("X", setOf(SubId(1)))!!
            assertTrue(selection.setActiveSet(set))
            assertTrue(selection.setLocation(LocationChoice.Node(NodeId("n1"))))
            selection.setNodeEnabled(NodeId("n1"), false)
            assertEquals(LocationChoice.Any, selection.location.first(), "the only NL node is gone, so the pin widens to any")
        } finally {
            db.close()
        }
    }

    @Test
    fun version4MigratesPastedListsToTheListKind() = runTest {
        val file = File(tmp.root, "desupon.db")
        createSchema(file, 4).apply {
            execSQL(
                "INSERT INTO subscription (id, url, local, name, hwid, createdAt) VALUES " +
                    "(1, 'https://a.example.com/s/tok', 0, NULL, 'h1', 1), (2, '', 1, 'Мой список', 'h2', 2)",
            )
            execSQL("INSERT INTO sub_chain (setId, subId, position) VALUES (0, 2, 0), (0, 1, 1)")
            close()
        }
        val db = open(file)
        try {
            val subs = db.subscriptionDao().getAll().associateBy { it.id }
            assertEquals("SUBSCRIPTION", subs.getValue(1).kind)
            assertEquals("LIST", subs.getValue(2).kind)
            assertEquals("Мой список", subs.getValue(2).name)
            assertNull(subs.getValue(2).source)
            val selection = SelectionRepository(db, NodeRepository(db.nodeDao()))
            assertEquals(listOf(SubId(2), SubId(1)), selection.selection.first().chain)

            val config = db.subscriptionDao().insert(SubscriptionEntity(url = "", local = true, hwid = "h3", createdAt = 3, kind = "CONFIG", source = "x"))
            assertEquals("x", db.subscriptionDao().get(config)!!.source)
        } finally {
            db.close()
        }
    }

    @Test
    fun version3MigratesProbeStatsKeepingHistory() = runTest {
        val file = File(tmp.root, "desupon.db")
        createSchema(file, 3).apply {
            execSQL(
                "INSERT INTO probe_stat (nodeId, networkKey, ewmaRttMs, ok, fail, failStreak, lastStage, lastOkAt, updatedAt) VALUES " +
                    "('n1', 'wifi|k', 250.0, 9, 2, 1, 'GET204', 100, 100), ('n2', 'wifi|k', 0.0, 0, 7, 7, 'TLS', NULL, 100)",
            )
            close()
        }
        val db = open(file)
        try {
            val key = moe.desupon.model.network.NetworkKey(moe.desupon.model.network.Transport.WIFI, "k")
            val rows = db.probeStatDao().forNetwork("wifi|k").associate { it.nodeId to it.toModel(key) }
            val n1 = rows.getValue("n1")
            assertEquals(250.0, n1.ewmaRttMs)
            assertEquals(9, n1.ok)
            assertEquals(1, n1.failStreak)
            assertEquals(0, n1.rttSamples, "the old cold RTT is replaced by the first staged sample")
            assertEquals(5.0, n1.okRecent)
            assertEquals(1.0, n1.failRecent)
            assertEquals(0.0, n1.ewmaConnectMs)
            assertNull(n1.ewmaKbps)
            assertEquals(false, n1.throttled)
            assertEquals(5.0, rows.getValue("n2").failRecent)

            val next = n1.copy(ewmaConnectMs = 120.0, madMs = 9.5, lastFailAtMs = 7L, rttSamples = 2, ewmaKbps = 900.0, throttled = true)
            db.probeStatDao().upsert(listOf(next.toEntity(200)))
            assertEquals(next, db.probeStatDao().forNetwork("wifi|k").first { it.nodeId == "n1" }.toModel(key))
        } finally {
            db.close()
        }
    }

    private companion object {
        val SCHEMA_DIR = File("schemas/moe.desupon.data.db.DesuponDatabase")
    }
}
