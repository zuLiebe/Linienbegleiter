package com.example.linienbegleiter.data.interchange

/**
 * Небольшой JSON-кодек без сторонних библиотек для форматов обмена приложения.
 */
internal object JsonCodec {
    fun parse(text: String): Any? = Parser(text).parse()

    fun stringify(value: Any?): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendQuoted(value)
            is Boolean, is Int, is Long -> append(value)
            is Float -> {
                require(value.isFinite())
                append(value)
            }
            is Double -> {
                require(value.isFinite())
                append(value)
            }
            is Map<*, *> -> {
                append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(',')
                    appendQuoted(requireNotNull(entry.key as? String))
                    append(':')
                    appendValue(entry.value)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            else -> error("Неподдерживаемое значение JSON: " + value.javaClass.simpleName)
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private class Parser(private val input: String) {
        private var index = 0

        fun parse(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            require(index == input.length) { "Лишние символы после JSON." }
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            require(index < input.length) { "Неожиданный конец JSON." }
            return when (input[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Недопустимый символ JSON в позиции " + index + ".")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            index++
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (consume('}')) return result
            while (true) {
                skipWhitespace()
                require(index < input.length && input[index] == '"') {
                    "Ожидался ключ JSON-объекта."
                }
                val key = parseString()
                skipWhitespace()
                require(consume(':')) { "Ожидался символ ':'." }
                require(!result.containsKey(key)) { "Повторяющийся ключ JSON: " + key + "." }
                result[key] = parseValue()
                skipWhitespace()
                if (consume('}')) return result
                require(consume(',')) { "Ожидалась запятая в JSON-объекте." }
            }
        }

        private fun parseArray(): List<Any?> {
            index++
            skipWhitespace()
            val result = mutableListOf<Any?>()
            if (consume(']')) return result
            while (true) {
                result += parseValue()
                skipWhitespace()
                if (consume(']')) return result
                require(consume(',')) { "Ожидалась запятая в JSON-массиве." }
            }
        }

        private fun parseString(): String {
            require(consume('"'))
            val result = StringBuilder()
            while (index < input.length) {
                val character = input[index++]
                when {
                    character == '"' -> return result.toString()
                    character == '\\' -> {
                        require(index < input.length) { "Незавершённая escape-последовательность." }
                        when (val escaped = input[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                require(index + 4 <= input.length) { "Неполный Unicode escape." }
                                val code = input.substring(index, index + 4).toIntOrNull(16)
                                    ?: error("Некорректный Unicode escape.")
                                result.append(code.toChar())
                                index += 4
                            }
                            else -> error("Некорректный escape в JSON.")
                        }
                    }
                    character < ' ' -> error("Управляющий символ в JSON-строке.")
                    else -> result.append(character)
                }
            }
            error("Незакрытая JSON-строка.")
        }

        private fun parseNumber(): Number {
            val start = index
            if (input[index] == '-') index++
            require(index < input.length)
            if (input[index] == '0') {
                index++
            } else {
                require(input[index] in '1'..'9')
                while (index < input.length && input[index].isDigit()) index++
            }
            if (index < input.length && input[index] == '.') {
                index++
                val fractionStart = index
                while (index < input.length && input[index].isDigit()) index++
                require(index > fractionStart) { "Некорректная дробная часть." }
            }
            if (index < input.length && input[index] in "eE") {
                index++
                if (index < input.length && input[index] in "+-") index++
                val exponentStart = index
                while (index < input.length && input[index].isDigit()) index++
                require(index > exponentStart) { "Некорректная степень числа." }
            }
            val raw = input.substring(start, index)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()?.takeIf(Double::isFinite)
            ?: error("Некорректное число JSON.")
        }

        private fun <T> parseLiteral(literal: String, result: T): T {
            require(input.startsWith(literal, index))
            index += literal.length
            return result
        }

        private fun skipWhitespace() {
            while (index < input.length && input[index] in " \t\r\n") index++
        }

        private fun consume(character: Char): Boolean {
            if (index < input.length && input[index] == character) {
                index++
                return true
            }
            return false
        }
    }
}

internal fun Any?.jsonObject(): Map<String, Any?>? = this as? Map<String, Any?>
internal fun Any?.jsonArray(): List<Any?>? = this as? List<Any?>
internal fun Any?.jsonString(): String? = this as? String
internal fun Any?.jsonDouble(): Double? = (this as? Number)?.toDouble()
