package com.example.linienbegleiter.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "editable_routes",
    foreignKeys = [
        ForeignKey(
            entity = OriginalTrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceOriginalTrackId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["sourceOriginalTrackId"]),
        Index(value = ["parentRouteId"])
    ]
)
data class EditableRouteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtEpochMillis: Long,
    val sourceOriginalTrackId: String?,
    @ColumnInfo(defaultValue = "'MANUAL'")
    val routeKind: String = "MANUAL",
    val parentRouteId: String? = null,
    val distanceMeters: Double,
    @ColumnInfo(defaultValue = "0") val routeColorArgb: Int = 0,
    @ColumnInfo(defaultValue = "5.0") val routeLineWidth: Float = 5f,
    val routeMetadataJson: String? = null
)

@Entity(
    tableName = "editable_route_points",
    foreignKeys = [
        ForeignKey(
            entity = EditableRouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["routeId", "sequence"], unique = true)]
)
data class EditableRoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: String,
    val sequence: Int,
    val latitude: Double,
    val longitude: Double
)

@Entity(
    tableName = "editable_route_stops",
    foreignKeys = [
        ForeignKey(
            entity = EditableRouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["routeId", "sequence"], unique = true),
        Index(value = ["routeId", "routePointSequence"])
    ]
)
data class EditableRouteStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: String,
    val sequence: Int,
    val routePointSequence: Int?,
    val name: String,
    val latitude: Double,
    val longitude: Double
)
