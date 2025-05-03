package com.messenger

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update

@Entity
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverAddress: String,
    val userId: String,
    val userName: String,
    val userToken: String,
    val privateKey: String
)

@Entity
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val senderId: String, // Whoever sent the message
    val session: Long,
    val channelId: String, // UserID of the contact you are in DM with, or groupID if it is a group chat
    val isGroupChannel: Boolean,
    val timestamp: String,
    val messageContent: String
)

@Entity(primaryKeys = ["id", "isGroupChannel", "session"])
data class Contact(
    val id: String,
    val session: Long,
    val isGroupChannel: Boolean,
    val publicKey: String,
    val name: String,
    val priority: Long
)

@Entity(primaryKeys = ["groupId", "session", "members"])
data class GroupMember(
    val groupId: String,
    val session: Long,
    val member: String
)

@Dao
interface MessageDao {
    @Insert
    suspend fun insertMessage(message: Message): Long

    @Query("SELECT * FROM Message WHERE channelId = :channelId AND session = :sessionId ORDER BY timestamp ASC")
    suspend fun getAllMessagesInChannel(channelId: String, sessionId: Long): List<Message>
}

@Dao
interface ContactDao {
    @Insert
    suspend fun insertContact(contact: Contact)

    @Update
    suspend fun updateContact(contact: Contact)

    @Query("SELECT * FROM Contact WHERE session = :sessionId ORDER BY priority DESC")
    suspend fun getAllContacts(sessionId: Long): List<Contact>

    @Query("SELECT * FROM Contact WHERE id = :id AND session = :sessionId")
    suspend fun getContactById(id: String, sessionId: Long): Contact?
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insertSession(session: Session): Long

    @Query("SELECT * FROM Session")
    suspend fun getAllSessions(): List<Session>

    @Query("SELECT * FROM Session WHERE id = :id")
    suspend fun getSessionById(id: Long): Session?
}

@Database(entities = [Message::class, Contact::class, Session::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun sessionDao(): SessionDao
}

object DatabaseProvider {
    private var instance: AppDatabase? = null

    fun getDatabase(context: Context): AppDatabase {
        if (instance == null) {
            instance = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "chat_database"
            ).build()
        }
        return instance!!
    }
}