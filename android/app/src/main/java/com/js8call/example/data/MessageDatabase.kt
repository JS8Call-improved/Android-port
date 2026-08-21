package com.js8call.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for storing JS8 messages and heard-station contacts.
 */
@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        MailboxEntity::class,
        MailboxGroupDeliveryEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class MessageDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    abstract fun contactDao(): ContactDao

    abstract fun mailboxDao(): MailboxDao

    companion object {
        private const val DATABASE_NAME = "js8_messages.db"

        @Volatile
        private var INSTANCE: MessageDatabase? = null

        fun getInstance(context: Context): MessageDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        // Adding the contacts table must not wipe stored messages.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `contacts` (
                        `callsign` TEXT NOT NULL,
                        `lastHeard` INTEGER NOT NULL,
                        `snr` INTEGER,
                        `offset` REAL,
                        `grid` TEXT,
                        `info` TEXT,
                        `heardUs` INTEGER NOT NULL DEFAULT 0,
                        `starred` INTEGER NOT NULL DEFAULT 0,
                        `comment` TEXT,
                        PRIMARY KEY(`callsign`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_contacts_lastHeard` ON `contacts` (`lastHeard`)")
            }
        }

        // The store-and-forward mailbox: messages held for other stations,
        // with per-callsign delivery tracking for group destinations.
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mailbox_messages` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `originator` TEXT NOT NULL,
                        `destination` TEXT NOT NULL,
                        `text` TEXT NOT NULL,
                        `receivedAt` INTEGER NOT NULL,
                        `originatedAt` INTEGER,
                        `relayPath` TEXT,
                        `snr` INTEGER,
                        `offsetHz` REAL,
                        `state` INTEGER NOT NULL,
                        `deliveredAt` INTEGER,
                        `origin` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mailbox_messages_destination` ON `mailbox_messages` (`destination`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mailbox_messages_state` ON `mailbox_messages` (`state`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_mailbox_messages_receivedAt` ON `mailbox_messages` (`receivedAt`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `mailbox_group_delivery` (
                        `msgId` INTEGER NOT NULL,
                        `callsign` TEXT NOT NULL,
                        `deliveredAt` INTEGER NOT NULL,
                        PRIMARY KEY(`msgId`, `callsign`),
                        FOREIGN KEY(`msgId`) REFERENCES `mailbox_messages`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        private fun buildDatabase(context: Context): MessageDatabase {
            // No destructive fallback: this database now holds traffic we
            // promised a third party we would forward, so a migration gap
            // must fail loudly instead of silently wiping it.
            return Room.databaseBuilder(
                context.applicationContext,
                MessageDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .build()
        }
    }
}
