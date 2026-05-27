package com.example.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        VoterEntity::class,
        VoteEntity::class,
        VotedRollEntity::class,
        ConfigEntity::class,
        ManagementAuthEntity::class,
        ConfirmNonceEntity::class,
        ElectionEntity::class,
        VotingOptionEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun voterDao(): VoterDao
    abstract fun voteDao(): VoteDao
    abstract fun votedRollDao(): VotedRollDao
    abstract fun configDao(): ConfigDao
    abstract fun managementAuthDao(): ManagementAuthDao
    abstract fun confirmNonceDao(): ConfirmNonceDao
    abstract fun electionDao(): ElectionDao
    abstract fun votingOptionDao(): VotingOptionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voting_db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
