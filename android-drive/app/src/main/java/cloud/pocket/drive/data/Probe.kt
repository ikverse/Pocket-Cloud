package cloud.pocket.drive.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import aws.sdk.kotlin.services.s3.S3Client

@Entity(tableName = "probe")
data class Probe(@PrimaryKey val id: Long)

fun probe(c: S3Client) = c
