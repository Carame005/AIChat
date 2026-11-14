package com.example.caramelai.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CharacterEntity::class,
        PersonaEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun characterDao(): CharacterDao
    abstract fun personaDao(): PersonaDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS personas (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        avatarUri TEXT,
                        description TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN personaId TEXT",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN variantsJson TEXT NOT NULL DEFAULT '[]'",
                )
                db.execSQL(
                    "ALTER TABLE messages ADD COLUMN activeVariantIndex INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN summary TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN summaryUpToMessageId TEXT",
                )
                db.execSQL(
                    "ALTER TABLE conversations ADD COLUMN pinnedMemory TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP INDEX IF EXISTS index_messages_conversationId")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_conversationId_createdAt " +
                        "ON messages(conversationId, createdAt)",
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "caramel_ai.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
    }
}
