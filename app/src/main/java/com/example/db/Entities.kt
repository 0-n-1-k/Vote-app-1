package com.example.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "elections")
data class ElectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    @ColumnInfo(name = "ends_at")
    var endsAt: Long = 0, // Unix timestamp in seconds
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0 // Unix timestamp in seconds
)

@Entity(tableName = "voters")
data class VoterEntity(
    @PrimaryKey
    val roll: String, // length <= 30
    val name: String, // length <= 100
    @ColumnInfo(name = "is_management")
    val isManagement: Int = 0 // 0 or 1
)

@Entity(
    tableName = "votes",
    foreignKeys = [
        ForeignKey(
            entity = VoterEntity::class,
            parentColumns = ["roll"],
            childColumns = ["roll"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ElectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["election_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["roll", "election_id", "design_id"], unique = true)
    ]
)
data class VoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val roll: String,
    @ColumnInfo(name = "election_id")
    val electionId: Long,
    @ColumnInfo(name = "design_id")
    val designId: Int, // BETWEEN 1 AND 5
    val choice: String, // 'yes' or 'no'
    @ColumnInfo(name = "submitted_at")
    val submittedAt: Long // Unix timestamp (seconds) in UTC
)

@Entity(
    tableName = "voted_rolls",
    primaryKeys = ["roll", "election_id"],
    foreignKeys = [
        ForeignKey(
            entity = VoterEntity::class,
            parentColumns = ["roll"],
            childColumns = ["roll"],
            onDelete = ForeignKey.RESTRICT
        ),
        ForeignKey(
            entity = ElectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["election_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class VotedRollEntity(
    val roll: String,
    @ColumnInfo(name = "election_id")
    val electionId: Long,
    @ColumnInfo(name = "voted_at")
    val votedAt: Long // Unix timestamp (seconds) in UTC
)

@Entity(tableName = "config")
data class ConfigEntity(
    @PrimaryKey
    val key: String,
    val value: String
)

@Entity(
    tableName = "management_auth",
    foreignKeys = [
        ForeignKey(
            entity = VoterEntity::class,
            parentColumns = ["roll"],
            childColumns = ["roll"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ManagementAuthEntity(
    @PrimaryKey
    val roll: String,
    @ColumnInfo(name = "password_hash")
    val passwordHash: String
)

@Entity(
    tableName = "confirm_nonces",
    foreignKeys = [
        ForeignKey(
            entity = VoterEntity::class,
            parentColumns = ["roll"],
            childColumns = ["roll"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ElectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["election_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ConfirmNonceEntity(
    @PrimaryKey
    val nonce: String,
    val roll: String,
    @ColumnInfo(name = "election_id")
    val electionId: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long, // Unix timestamp in seconds
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long // Unix timestamp in seconds (createdAt + 300)
)

@Entity(
    tableName = "voting_options",
    foreignKeys = [
        ForeignKey(
            entity = ElectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["election_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class VotingOptionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "election_id")
    val electionId: Long,
    @ColumnInfo(name = "option_text")
    val optionText: String,
    @ColumnInfo(name = "image_data")
    val imageData: String // Base64 image
)
