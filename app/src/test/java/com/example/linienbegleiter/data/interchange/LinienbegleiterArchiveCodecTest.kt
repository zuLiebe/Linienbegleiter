package com.example.linienbegleiter.data.interchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class LinienbegleiterArchiveCodecTest {
    @Test
    fun archiveRoundTripContainsVersionedPayloadAndChecksum() {
        val payload = mapOf(
            "routes" to listOf(
                mapOf("id" to "route-1", "name" to "Hauptbahnhof", "color" to 0xFF123456.toInt())
            )
        )
        val output = ByteArrayOutputStream()
        LinienbegleiterArchiveCodec.write(payload, output)

        val bundle = LinienbegleiterArchiveCodec.read(ByteArrayInputStream(output.toByteArray()))
        val parsed = JsonCodec.parse(bundle.payloadJson).jsonObject()

        assertEquals(1, bundle.formatVersion)
        assertEquals("route-1", parsed?.get("routes").jsonArray()?.single().jsonObject()?.get("id"))
        assertTrue(output.size() > 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun archiveRejectsChangedPayloadChecksum() {
        val output = ByteArrayOutputStream()
        LinienbegleiterArchiveCodec.write(mapOf("routes" to emptyList<Any>()), output)
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                files[entry.name] = input.readBytes()
            }
        }
        val corrupted = files.getValue("data.json").copyOf()
        corrupted[0] = if (corrupted[0] == '{'.code.toByte()) '['.code.toByte() else '{'.code.toByte()
        files["data.json"] = corrupted

        val tampered = ByteArrayOutputStream()
        ZipOutputStream(tampered).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        LinienbegleiterArchiveCodec.read(ByteArrayInputStream(tampered.toByteArray()))
    }
}
