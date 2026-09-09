package com.mindtype.ai.keyboard.engine.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "user_words")
data class UserWordEntity(
    @androidx.room.PrimaryKey val word: String,
    val frequency: Int
)

@Entity(
    tableName = "user_bigrams",
    primaryKeys = ["previousWord", "nextWord"],
    indices = [Index(value = ["previousWord", "frequency"])]
)
data class UserBigramEntity(
    val previousWord: String,
    val nextWord: String,
    val frequency: Int
)

@Entity(
    tableName = "user_trigrams",
    primaryKeys = ["firstWord", "secondWord", "nextWord"],
    indices = [Index(value = ["firstWord", "secondWord", "frequency"])]
)
data class UserTrigramEntity(
    val firstWord: String,
    val secondWord: String,
    val nextWord: String,
    val frequency: Int
)

@Dao
interface UserLearningDao {
    @Query("SELECT word, frequency FROM user_words WHERE word LIKE :prefix || '%' ORDER BY frequency DESC, word ASC LIMIT :limit")
    suspend fun getCompletions(prefix: String, limit: Int): List<UserWordEntity>

    @Query("SELECT previousWord, nextWord, frequency FROM user_bigrams WHERE previousWord = :previous ORDER BY frequency DESC, nextWord ASC LIMIT :limit")
    suspend fun getNextWords(previous: String, limit: Int): List<UserBigramEntity>

    @Query("SELECT firstWord, secondWord, nextWord, frequency FROM user_trigrams WHERE firstWord = :first AND secondWord = :second ORDER BY frequency DESC, nextWord ASC LIMIT :limit")
    suspend fun getNextWordsForContext(first: String, second: String, limit: Int): List<UserTrigramEntity>

    // Saturating SQL updates avoid read-modify-write races and integer overflow.
    @Query("UPDATE user_words SET frequency = CASE WHEN frequency < 2147483647 THEN frequency + 1 ELSE frequency END WHERE word = :word")
    suspend fun incrementWord(word: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWordIfMissing(word: UserWordEntity)

    @Query("UPDATE user_bigrams SET frequency = CASE WHEN frequency < 2147483647 THEN frequency + 1 ELSE frequency END WHERE previousWord = :previous AND nextWord = :next")
    suspend fun incrementBigram(previous: String, next: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBigramIfMissing(bigram: UserBigramEntity)

    @Query("UPDATE user_trigrams SET frequency = CASE WHEN frequency < 2147483647 THEN frequency + 1 ELSE frequency END WHERE firstWord = :first AND secondWord = :second AND nextWord = :next")
    suspend fun incrementTrigram(first: String, second: String, next: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTrigramIfMissing(trigram: UserTrigramEntity)

    @Transaction
    suspend fun learn(firstWord: String?, previousWord: String?, typedWord: String) {
        if (incrementWord(typedWord) == 0) insertWordIfMissing(UserWordEntity(typedWord, 1))
        if (previousWord != null) {
            if (incrementBigram(previousWord, typedWord) == 0) {
                insertBigramIfMissing(UserBigramEntity(previousWord, typedWord, 1))
            }
            if (firstWord != null) {
                if (incrementTrigram(firstWord, previousWord, typedWord) == 0) {
                    insertTrigramIfMissing(UserTrigramEntity(firstWord, previousWord, typedWord, 1))
                }
            }
        }
    }

    /** Periodic disk bounds; invoked from the engine's serialized worker. */
    @Transaction
    suspend fun pruneToBounds() {
        pruneWords(MAX_USER_WORDS)
        pruneBigrams(MAX_USER_BIGRAMS)
        pruneTrigrams(MAX_USER_TRIGRAMS)
    }

    @Query("DELETE FROM user_words WHERE word NOT IN (SELECT word FROM user_words ORDER BY frequency DESC, word ASC LIMIT :maxRows)")
    suspend fun pruneWords(maxRows: Int)

    @Query("DELETE FROM user_bigrams WHERE (previousWord, nextWord) NOT IN (SELECT previousWord, nextWord FROM user_bigrams ORDER BY frequency DESC, previousWord ASC, nextWord ASC LIMIT :maxRows)")
    suspend fun pruneBigrams(maxRows: Int)

    @Query("DELETE FROM user_trigrams WHERE (firstWord, secondWord, nextWord) NOT IN (SELECT firstWord, secondWord, nextWord FROM user_trigrams ORDER BY frequency DESC, firstWord ASC, secondWord ASC, nextWord ASC LIMIT :maxRows)")
    suspend fun pruneTrigrams(maxRows: Int)

    companion object {
        private const val MAX_USER_WORDS = 10_000
        private const val MAX_USER_BIGRAMS = 20_000
        private const val MAX_USER_TRIGRAMS = 20_000
    }
}

@Database(
    entities = [UserWordEntity::class, UserBigramEntity::class, UserTrigramEntity::class],
    version = 2,
    exportSchema = false
)
abstract class UserLearningDatabase : RoomDatabase() {
    abstract fun userDao(): UserLearningDao

    companion object {
        @Volatile private var INSTANCE: UserLearningDatabase? = null

        fun getInstance(context: Context): UserLearningDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                UserLearningDatabase::class.java,
                "user_learning_db"
            ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_trigrams` (`firstWord` TEXT NOT NULL, `secondWord` TEXT NOT NULL, `nextWord` TEXT NOT NULL, `frequency` INTEGER NOT NULL, PRIMARY KEY(`firstWord`, `secondWord`, `nextWord`))"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_trigrams_firstWord_secondWord_frequency` ON `user_trigrams` (`firstWord`, `secondWord`, `frequency`)"
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_user_bigrams_previousWord_frequency` ON `user_bigrams` (`previousWord`, `frequency`)"
                )
            }
        }
    }
}
