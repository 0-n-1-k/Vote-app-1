package com.example.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Data model for aggregated design statistics
data class DbDesignStats(
    val design_id: Int,
    val yes_votes: Int,
    val no_votes: Int,
    val total_votes: Int
)

@Dao
interface VoterDao {
    @Query("SELECT * FROM voters WHERE roll = :roll LIMIT 1")
    suspend fun getVoter(roll: String): VoterEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVoter(voter: VoterEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVoters(voters: List<VoterEntity>)

    @Query("SELECT COUNT(*) FROM voters")
    suspend fun getVotersCount(): Int
}

@Dao
interface VoteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVote(vote: VoteEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVotes(votes: List<VoteEntity>)

    @Query("SELECT * FROM votes WHERE roll = :roll")
    suspend fun getVotesForRoll(roll: String): List<VoteEntity>

    @Query("""
        SELECT 
            d.designId as design_id,
            SUM(CASE WHEN v.choice = 'yes' THEN 1 ELSE 0 END) as yes_votes,
            SUM(CASE WHEN v.choice = 'no' THEN 1 ELSE 0 END) as no_votes,
            COUNT(v.id) as total_votes
        FROM (SELECT 1 as designId UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5) d
        LEFT JOIN votes v ON d.designId = v.design_id
        GROUP BY d.designId
    """)
    suspend fun getVoteStats(): List<DbDesignStats>
}

@Dao
interface VotedRollDao {
    @Query("SELECT * FROM voted_rolls WHERE roll = :roll LIMIT 1")
    suspend fun getVotedRoll(roll: String): VotedRollEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVotedRoll(votedRoll: VotedRollEntity)

    @Query("SELECT * FROM voted_rolls ORDER BY voted_at DESC LIMIT :limit OFFSET :offset")
    suspend fun getVotedRollsPaginated(limit: Int, offset: Int): List<VotedRollEntity>

    @Query("SELECT COUNT(*) FROM voted_rolls")
    suspend fun getVotedRollsCount(): Int

    @Query("SELECT COUNT(*) FROM voted_rolls")
    fun getVotedRollsCountFlow(): Flow<Int>
}

@Dao
interface ConfigDao {
    @Query("SELECT * FROM config WHERE [key] = :key LIMIT 1")
    suspend fun getConfig(key: String): ConfigEntity?

    @Query("SELECT value FROM config WHERE [key] = :key LIMIT 1")
    suspend fun getConfigValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfig(config: ConfigEntity)

    @Query("DELETE FROM config WHERE [key] = :key")
    suspend fun deleteConfig(key: String)
}

@Dao
interface ManagementAuthDao {
    @Query("SELECT * FROM management_auth WHERE roll = :roll LIMIT 1")
    suspend fun getManagementAuth(roll: String): ManagementAuthEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertManagementAuth(auth: ManagementAuthEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertManagementAuths(auths: List<ManagementAuthEntity>)

    @Query("SELECT COUNT(*) FROM management_auth")
    suspend fun getManagementAuthsCount(): Int
}

@Dao
interface ConfirmNonceDao {
    @Query("SELECT * FROM confirm_nonces WHERE nonce = :nonce LIMIT 1")
    suspend fun getConfirmNonce(nonce: String): ConfirmNonceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConfirmNonce(nonce: ConfirmNonceEntity)

    @Query("DELETE FROM confirm_nonces WHERE nonce = :nonce")
    suspend fun deleteConfirmNonce(nonce: String)

    @Query("DELETE FROM confirm_nonces WHERE expires_at < :currentTimeSec")
    suspend fun deleteExpiredNonces(currentTimeSec: Long): Int
}
