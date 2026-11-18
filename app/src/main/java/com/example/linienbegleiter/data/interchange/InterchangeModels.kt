package com.example.linienbegleiter.data.interchange

/**
 * Координата в нейтральном формате обмена маршрутами.
 */
data class RouteCoordinate(
    val latitude: Double,
    val longitude: Double,
    val timeEpochMillis: Long? = null,
    val elevationMeters: Double? = null
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(elevationMeters == null || elevationMeters.isFinite())
    }
}

/**
 * Остановка, импортированная из файла или подготовленная для экспорта.
 */
data class InterchangeStop(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val sequence: Int
) {
    init {
        require(name.isNotBlank())
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(sequence >= 0)
    }
}

/**
 * Маршрут в нейтральном формате обмена до сохранения в Room или записи в файл.
 *
 * Каждый элемент [segments] является самостоятельным непрерывным участком.
 */
data class InterchangeRoute(
    val title: String,
    val segments: List<List<RouteCoordinate>>,
    val stops: List<InterchangeStop> = emptyList(),
    val colorArgb: Int? = null,
    val lineWidth: Float = 5f,
    val metadataJson: String? = null,
    val warnings: List<String> = emptyList()
) {
    init {
        require(title.isNotBlank())
        require(lineWidth.isFinite() && lineWidth in 2f..14f)
        require(segments.flatten().isNotEmpty() || stops.isNotEmpty())
    }

    val points: List<RouteCoordinate>
        get() = segments.flatten()
}
