package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.ConversationDao
import com.example.data.dao.MessageDao
import com.example.data.dao.UserDao
import com.example.data.model.ConversationEntity
import com.example.data.model.MessageEntity
import com.example.data.model.UserConversationStateEntity
import com.example.data.model.UserDeviceEntity
import com.example.data.model.UserEntity

@Database(
    entities = [
        UserEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        UserConversationStateEntity::class,
        UserDeviceEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class EasappDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: EasappDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN usernameNormalized TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE users SET usernameNormalized = LOWER(TRIM(REPLACE(username, '@', '')))")
                db.execSQL("DROP INDEX IF EXISTS index_users_username")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_users_usernameNormalized ON users(usernameNormalized)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN type TEXT NOT NULL DEFAULT 'text'")
                db.execSQL("ALTER TABLE messages ADD COLUMN mediaUrl TEXT")
                db.execSQL("UPDATE messages SET type = 'image', mediaUrl = attachmentUri WHERE attachmentUri IS NOT NULL AND attachmentUri != ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_conversation_states` (
                        `userId` TEXT NOT NULL,
                        `conversationId` TEXT NOT NULL,
                        `otherUserId` TEXT NOT NULL,
                        `hidden` INTEGER NOT NULL DEFAULT 0,
                        `deletedAt` INTEGER NOT NULL DEFAULT 0,
                        `lastMessage` TEXT NOT NULL DEFAULT '',
                        `lastMessageAt` INTEGER NOT NULL DEFAULT 0,
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`userId`, `conversationId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_conversation_states_userId` ON `user_conversation_states` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_conversation_states_conversationId` ON `user_conversation_states` (`conversationId`)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `user_devices` (
                        `userId` TEXT NOT NULL,
                        `deviceId` TEXT NOT NULL,
                        `fcmToken` TEXT NOT NULL,
                        `platform` TEXT NOT NULL DEFAULT 'android',
                        `updatedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`userId`, `deviceId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_devices_userId` ON `user_devices` (`userId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_devices_fcmToken` ON `user_devices` (`fcmToken`)")
            }
        }

        fun setInstanceForTest(db: EasappDatabase?) {
            INSTANCE = db
        }

        fun getInstance(context: Context): EasappDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EasappDatabase::class.java,
                    "easapp_database.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
