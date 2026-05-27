package com.example.db

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.example.security.BCrypt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

object DatabaseSeeder {
    private const val TAG = "DatabaseSeeder"

    // Clear and run the idempotent seeding pipeline
    suspend fun seedDatabase(context: Context, database: AppDatabase, votingEndTimeStr: String): Boolean = withContext(Dispatchers.IO) {
        val voterDao = database.voterDao()
        val authDao = database.managementAuthDao()
        val configDao = database.configDao()

        try {
            Log.d(TAG, "Starting database seed transaction block...")
            
            // 1. Parse voters.csv
            val votersToInsert = mutableListOf<VoterEntity>()
            context.assets.open("voters.csv").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var headerRead = false
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line!!.trim()
                        if (currentLine.isEmpty()) continue
                        if (!headerRead) {
                            headerRead = true
                            continue
                        }
                        // Simple CSV split (handling no active commas within names for simplicity)
                        val parts = currentLine.split(",")
                        if (parts.size >= 3) {
                            val roll = parts[0].trim()
                            val name = parts[1].trim()
                            val isMgmt = parts[2].trim().toIntOrNull() ?: 0
                            votersToInsert.add(VoterEntity(roll, name, isMgmt))
                        }
                    }
                }
            }

            // 2. Parse management_passwords.csv
            val passwordsToHash = mutableMapOf<String, String>()
            context.assets.open("management_passwords.csv").use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    var headerRead = false
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line!!.trim()
                        if (currentLine.isEmpty()) continue
                        if (!headerRead) {
                            headerRead = true
                            continue
                        }
                        val parts = currentLine.split(",")
                        if (parts.size >= 2) {
                            val roll = parts[0].trim()
                            val password = parts[1].trim()
                            passwordsToHash[roll] = password
                        }
                    }
                }
            }

            // Database transaction to write voters and auth
            database.withTransaction {
                // We will run this inside the Room transaction coroutine block
                // Insert voters
                for (voter in votersToInsert) {
                    // Check limits
                    if (voter.roll.length > 30) throw IllegalArgumentException("Voter roll too long: ${voter.roll}")
                    if (voter.name.length > 100) throw IllegalArgumentException("Voter name too long: ${voter.name}")
                    
                    // Insert directly as suspend call
                    voterDao.insertVoter(voter)
                }

                // Verify management passes and hash
                for ((roll, plainPass) in passwordsToHash) {
                    val matchingVoter = votersToInsert.find { it.roll == roll }
                    if (matchingVoter == null || matchingVoter.isManagement != 1) {
                        val msg = "SEED TRANSACTION ABORTED: Roll '$roll' in management_passwords.csv is not a valid manager in voters.csv"
                        Log.e(TAG, msg)
                        throw IllegalStateException(msg)
                    }

                    // Hash password using BCrypt (OpenBSD compatible Blowfish encryption)
                    val salt = BCrypt.gensalt(10)
                    val hashed = BCrypt.hashpw(plainPass, salt) ?: throw IllegalStateException("BCrypt hashing failed")
                    
                    authDao.insertManagementAuth(ManagementAuthEntity(roll, hashed))
                }

                // Insert Configs
                configDao.insertConfig(ConfigEntity("voting_open", "1"))
                configDao.insertConfig(ConfigEntity("voting_start_time", "2026-05-01 00:00:00"))
                
                // To support standard SQLite datetime('now', 'utc') compatibility,
                // we parse the ISO8601 string and convert it to SQLite space-separated format 'YYYY-MM-DD HH:MM:SS'.
                // e.g. "2026-08-01T14:00:00Z" -> "2026-08-01 14:00:00"
                val sqliteFormattedTime = votingEndTimeStr
                    .replace("T", " ")
                    .replace("Z", "")
                
                configDao.insertConfig(ConfigEntity("voting_end_time", sqliteFormattedTime))
                Log.d(TAG, "Config table seeded with voting_end_time = '$sqliteFormattedTime'")
            }

            Log.d(TAG, "Database seeded successfully and transaction committed.")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "Seeding Exception raised: ", e)
            return@withContext false
        }
    }
}
