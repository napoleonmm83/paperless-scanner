package com.paperless.scanner.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.reflect.TypeToken
import com.paperless.scanner.di.GsonProvider

@Entity(tableName = "pending_uploads")
@TypeConverters(Converters::class)
data class PendingUpload(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,
    val title: String? = null,
    val tagIds: List<Int> = emptyList(),
    val documentTypeId: Int? = null,
    val correspondentId: Int? = null,
    val status: UploadStatus = UploadStatus.PENDING,
    val errorMessage: String? = null,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAttemptAt: Long? = null,
    val isMultiPage: Boolean = false,
    val additionalUris: List<String> = emptyList(),
    /**
     * Custom fields for the document.
     * Map of field ID to value (as String). All values are serialized as Strings:
     * - Text fields: direct string value
     * - Integer/Monetary: numeric string (e.g., "123", "99.99")
     * - Date: ISO format string (e.g., "2024-01-15")
     * - Boolean: "true" or "false"
     * - Select: the selected option value as string
     */
    val customFields: Map<Int, String> = emptyMap()
)

enum class UploadStatus {
    PENDING,
    UPLOADING,
    COMPLETED,
    FAILED
}

class Converters {
    private val gson = GsonProvider.instance

    @TypeConverter
    fun fromIntList(value: List<Int>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toIntList(value: String): List<Int> {
        val type = object : TypeToken<List<Int>>() {}.type
        return try {
            gson.fromJson(value, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        val type = object : TypeToken<List<String>>() {}.type
        return try {
            gson.fromJson(value, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromUploadStatus(status: UploadStatus): String {
        return status.name
    }

    @TypeConverter
    fun toUploadStatus(value: String): UploadStatus {
        return try {
            UploadStatus.valueOf(value)
        } catch (e: Exception) {
            UploadStatus.PENDING
        }
    }

    @TypeConverter
    fun fromIntStringMap(value: Map<Int, String>): String {
        return gson.toJson(value)
    }

    @TypeConverter
    fun toIntStringMap(value: String): Map<Int, String> {
        val type = object : TypeToken<Map<Int, String>>() {}.type
        return try {
            gson.fromJson(value, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
