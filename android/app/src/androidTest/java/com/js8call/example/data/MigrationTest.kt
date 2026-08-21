package com.js8call.example.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The destructive fallback is gone, so a migration gap now crashes on
 * upgrade instead of silently wiping the database. These tests are the
 * proof each schema bump needs before it ships.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDb = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MessageDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate3To4_keepsDataAndAddsMailbox() {
        helper.createDatabase(testDb, 3).apply {
            execSQL(
                """
                INSERT INTO messages
                    (conversationId, direction, senderCallsign, text, timestamp,
                     snr, frequency, status, isRead, relayPath)
                VALUES ('KN4CRD', 0, 'KN4CRD', 'HELLO', 1724200000000,
                        -12, 1500.0, 0, 1, NULL)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO contacts (callsign, lastHeard, heardUs, starred)
                VALUES ('KN4CRD', 1724200000000, 1, 0)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            testDb, 4, true, MessageDatabase.MIGRATION_3_4
        )

        db.query("SELECT text FROM messages").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("HELLO", c.getString(0))
        }
        db.query("SELECT callsign FROM contacts").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("KN4CRD", c.getString(0))
        }

        // The new tables accept rows, and the delivery cascade holds.
        db.execSQL(
            """
            INSERT INTO mailbox_messages
                (originator, destination, text, receivedAt, state, origin)
            VALUES ('KA0XYZ', 'N0CALL', 'QRV 1400', 1724200000000, 0, 0)
            """.trimIndent()
        )
        db.execSQL(
            "INSERT INTO mailbox_group_delivery (msgId, callsign, deliveredAt) VALUES (1, 'N0CALL', 1724200000000)"
        )
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM mailbox_messages WHERE id = 1")
        db.query("SELECT COUNT(*) FROM mailbox_group_delivery").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
    }
}
