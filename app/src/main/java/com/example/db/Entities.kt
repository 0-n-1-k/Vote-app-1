package com.example.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
        )
    ],
    indices = [
        Index(value = ["roll", "design_id"], unique = true)
    ]
)
data class VoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val roll: String,
    @ColumnInfo(name = "design_id")
    val designId: Int, // BETWEEN 1 AND 5
    val choice: String, // 'yes' or 'no'
    @ColumnInfo(name = "submitted_at")
    val submittedAt: Long // Unix timestamp (seconds) in UTC
)

@Entity(
    tableName = "voted_rolls",
    foreignKeys = [
        ForeignKey(
            entity = VoterEntity::class,
            parentColumns = ["roll"],
            childColumns = ["roll"],
            onDelete = ForeignKey.RESTRICT
        )
    ]
)
data class VotedRollEntity(
    @PrimaryKey
    val roll: String,
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
        )
    ]
)
data class ConfirmNonceEntity(
    @PrimaryKey
    val nonce: String,
    val roll: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long, // Unix timestamp in seconds
    @ColumnInfo(name = "expires_at")
    val expiresAt: Long // Unix timestamp in seconds (createdAt + 300)
)
