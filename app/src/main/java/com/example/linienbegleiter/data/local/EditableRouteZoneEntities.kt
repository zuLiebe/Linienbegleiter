package com.example.linienbegleiter.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "editable_route_zones",
    foreignKeys = [
        ForeignKey(
            entity = EditableRouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("routeId")
    ]
)
data class EditableRouteZoneEntity(
    @PrimaryKey
    val id: String,
    val routeId: String,
    val title: String,
    val centerLatitude: Double,
    val centerLongitude: Double,
    val radiusMeters: Double,
    val targetZoom: Float = 18.0f,
    val metadataJson: String? = null
)

@Entity(
    tableName = "editable_route_zone_annotations",
    foreignKeys = [
        ForeignKey(
            entity = EditableRouteZoneEntity::class,
            parentColumns = ["id"],
            childColumns = ["zoneId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("zoneId")
    ]
)
data class EditableRouteZoneAnnotationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val zoneId: String,
    val annotationType: String, // "PLATFORM", "APPROACH_LINE", "ARROW", "LABEL"
    val label: String? = null,
    val geometryJson: String // GeoJSON representation of point or line string
)
