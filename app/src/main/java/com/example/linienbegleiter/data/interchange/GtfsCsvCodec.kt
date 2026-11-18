package com.example.linienbegleiter.data.interchange

/**
 * RFC 4180-подобный CSV кодек для текстовых таблиц GTFS.
 */
internal object GtfsCsvCodec {
    data class Table(val headers: List<String>, val rows: List<Map<String, String>>)

    fun parse(source: String): Table {
        val text = source.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        var field = StringBuilder()
        var inQuotes = false
        var afterQuote = false
        var index = 0

        fun finishField() {
            row += field.toString()
            field = StringBuilder()
            afterQuote = false
        }
        fun finishRow() {
            finishField()
            if (row.any(String::isNotEmpty)) rows += row
            row = mutableListOf()
        }

        while (index < text.length) {
            val character = text[index]
            if (inQuotes) {
                when (character) {
                    '"' -> if (index + 1 < text.length && text[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else {
                        inQuotes = false
                        afterQuote = true
                    }
                    else -> field.append(character)
                }
            } else {
                when {
                    afterQuote && (character == ' ' || character == '\t') -> Unit
                    character == ',' -> finishField()
                    character == '\r' -> {
                        if (index + 1 < text.length && text[index + 1] == '\n') index++
                        finishRow()
                    }
                    character == '\n' -> finishRow()
                    character == '"' && field.isEmpty() && !afterQuote -> inQuotes = true
                    character == '"' -> error("Кавычка внутри некавыченного CSV-поля.")
                    else -> {
                        field.append(character)
                        afterQuote = false
                    }
                }
            }
            index++
        }
        require(!inQuotes) { "CSV заканчивается внутри кавычек." }
        if (row.isNotEmpty() || field.isNotEmpty() || afterQuote) finishRow()
        require(rows.isNotEmpty()) { "CSV-файл пуст." }

        val headers = rows.first().map(String::trim)
        require(headers.none(String::isBlank)) { "В CSV есть пустое имя столбца." }
        require(headers.distinct().size == headers.size) { "В CSV повторяются имена столбцов." }
        val records = rows.drop(1).map { values ->
            headers.mapIndexed { column, header ->
                header to values.getOrElse(column) { "" }
            }.toMap(LinkedHashMap())
        }
        return Table(headers, records)
    }

    fun write(table: Table): String = buildString {
        append(table.headers.joinToString(",") { it.csvEscape() })
        append("\r\n")
        table.rows.forEach { row ->
            append(table.headers.joinToString(",") { header ->
                (row[header] ?: "").csvEscape()
            })
            append("\r\n")
        }
    }

    private fun String.csvEscape(): String =
        if (any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + replace("\"", "\"\"") + "\""
        } else {
            this
        }
}
