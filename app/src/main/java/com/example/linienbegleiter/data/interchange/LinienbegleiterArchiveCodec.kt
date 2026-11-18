package com.example.linienbegleiter.data.interchange

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Кодек резервного ZIP Linienbegleiter с версией формата и проверкой SHA-256.
 */
object LinienbegleiterArchiveCodec {
    private const val FORMAT = "LINIENBEGLEITER_ARCHIVE"
    private const val VERSION = 1
    private const val MAX_ARCHIVE_DATA_BYTES = 128 * 1024 * 1024

    data class Bundle(val payloadJson: String, val formatVersion: Int)

    fun write(payload: Map<String, Any?>, output: OutputStream) {
        val payloadBytes = JsonCodec.stringify(payload).toByteArray(Charsets.UTF_8)
        require(payloadBytes.size <= MAX_ARCHIVE_DATA_BYTES) { "Резервная копия слишком велика." }
        val manifest = JsonCodec.stringify(
            mapOf(
                "format" to FORMAT,
                "format_version" to VERSION,
                "payload_file" to "data.json",
                "sha256" to sha256(payloadBytes)
            )
        ).toByteArray(Charsets.UTF_8)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("data.json"))
            zip.write(payloadBytes)
            zip.closeEntry()
            zip.finish()
        }
    }

    fun read(input: InputStream): Bundle {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(input, Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && !entry.name.contains('/') && !entry.name.contains('\\')) {
                    "В резервной копии обнаружен неожиданный путь."
                }
                require(entry.name == "manifest.json" || entry.name == "data.json") {
                    "В архиве есть неизвестный файл."
                }
                require(!entries.containsKey(entry.name)) { "В архиве повторяется " + entry.name + "." }
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_ARCHIVE_DATA_BYTES) { "Резервная копия превышает лимит размера." }
                    bytes.write(buffer, 0, count)
                }
                entries[entry.name] = bytes.toByteArray()
                zip.closeEntry()
            }
        }
        val manifest = entries["manifest.json"]?.toString(Charsets.UTF_8)
            ?.let(JsonCodec::parse)?.jsonObject()
            ?: error("В резервной копии отсутствует manifest.json.")
        val data = entries["data.json"] ?: error("В резервной копии отсутствует data.json.")
        require(manifest["format"].jsonString() == FORMAT) { "Это не архив Linienbegleiter." }
        val version = manifest["format_version"].jsonDouble()?.toInt()
            ?: error("В архиве не указана версия формата.")
        require(version == VERSION) { "Версия архива " + version + " не поддерживается." }
        require(manifest["payload_file"].jsonString() == "data.json") {
            "Архив указывает на неизвестный файл данных."
        }
        require(manifest["sha256"].jsonString() == sha256(data)) {
            "Контрольная сумма архива не совпадает."
        }
        val payloadJson = data.toString(Charsets.UTF_8)
        require(JsonCodec.parse(payloadJson).jsonObject() != null) {
            "Файл данных архива должен содержать JSON-объект."
        }
        return Bundle(payloadJson, version)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
}
