package com.example.linienbegleiter.data.routes

import androidx.room.withTransaction
import com.example.linienbegleiter.data.local.RecordingDatabase
import java.util.Locale

/**
 * Route titles are unique across both GPS recordings and editable routes.
 * The existing schema keeps these data sets in separate tables, so uniqueness
 * is validated in their shared Room transaction.
 */
suspend fun RecordingDatabase.requireUniqueRouteTitle(
    title: String,
    exceptRecordingId: String? = null,
    exceptEditableRouteId: String? = null
) {
    val normalized = title.trim()
    require(normalized.isNotEmpty()) { "Введите название маршрута." }
    val recordingConflict = recordingDao().findIdByTitle(normalized, exceptRecordingId)
    val editableConflict = editableRouteDao().findIdByTitle(normalized, exceptEditableRouteId)
    check(recordingConflict == null && editableConflict == null) {
        "Маршрут с названием «$normalized» уже существует. Укажите другое название."
    }
}

suspend fun RecordingDatabase.uniqueRouteTitle(
    requestedTitle: String,
    exceptRecordingId: String? = null,
    exceptEditableRouteId: String? = null
): String {
    val base = requestedTitle.trim().ifEmpty { "Новый маршрут" }
    var candidate = base
    var suffix = 2
    while (
        recordingDao().findIdByTitle(candidate, exceptRecordingId) != null ||
        editableRouteDao().findIdByTitle(candidate, exceptEditableRouteId) != null
    ) {
        candidate = "$base $suffix"
        suffix++
    }
    return candidate
}

/**
 * Cleans up duplicate titles created by earlier versions without removing or
 * merging any route data.
 */
suspend fun RecordingDatabase.repairDuplicateRouteTitles() {
    withTransaction {
        editableRouteDao().detachLegacyRouteLinks()
        data class Entry(
            val createdAt: Long,
            val id: String,
            val title: String,
            val recording: Boolean
        )

        val entries = (
            recordingDao().getAllForBackup().map {
                Entry(it.createdAtEpochMillis, it.id, it.title, true)
            } + editableRouteDao().getAllForBackup().map {
                Entry(it.createdAtEpochMillis, it.id, it.title, false)
            }
        ).sortedWith(compareBy<Entry> { it.createdAt }.thenBy { it.title.lowercase(Locale.ROOT) })

        val used = mutableSetOf<String>()
        entries.forEach { entry ->
            val base = entry.title.trim().ifEmpty { "Маршрут" }
            var candidate = base
            var suffix = 2
            while (!used.add(candidate.lowercase(Locale.ROOT))) {
                candidate = "$base $suffix"
                suffix++
            }
            if (candidate != entry.title) {
                if (entry.recording) {
                    recordingDao().updateTitle(entry.id, candidate)
                } else {
                    editableRouteDao().updateTitle(entry.id, candidate)
                }
            }
        }
    }
}
