package com.example.linienbegleiter.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "editable_route_directions",
    foreignKeys = [
        ForeignKey(
            entity = EditableRouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["routeId"])]
)
data class EditableRouteDirectionEntity(
    @PrimaryKey val id: String,
    val routeId: String,
    val name: String,
    val startStopId: Long,
    val endStopId: Long,
    val initialBearingDegrees: Double,
    val isGeometryReversed: Boolean
)

@Entity(
    tableName = "editable_route_direction_stops",
    foreignKeys = [
        ForeignKey(
            entity = EditableRouteDirectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["directionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["directionId", "sequence"], unique = true),
        Index(value = ["directionId", "stopId"], unique = true)
    ]
)
data class EditableRouteDirectionStopEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val directionId: String,
    val stopId: Long,
    val sequence: Int
)
