package com.eratverbum.stepbible

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

object TarGzExtractor {

    private const val TAG = "TarGz"

    fun extractFromApk(apkPath: String, entryName: String, destDir: File) {
        val zip = ZipFile(apkPath)
        val entry = zip.getEntry(entryName) ?: throw IOException("Entry not found: $entryName")
        val gz = GZIPInputStream(zip.getInputStream(entry))
        val buf = ByteArray(64 * 1024)

        try {
            while (true) {
                val header = ByteArray(512)
                var offset = 0
                while (offset < 512) {
                    val read = gz.read(header, offset, 512 - offset)
                    if (read < 0) return  // EOF
                    offset += read
                }

                // Check for end-of-archive (two zero blocks)
                if (header.all { it == 0.toByte() }) return

                val name = parseString(header, 0, 100)
                val size = parseOctal(header, 124, 12)
                val type = header[156].toInt().toChar()

                if (name.isEmpty()) return

                val cleanName = if (name.startsWith("./")) name.removePrefix("./") else name
                val dest = File(destDir, cleanName)

                if (type == '5' || type == '\u0000' && cleanName.endsWith("/")) {
                    dest.mkdirs()
                } else {
                    dest.parentFile?.mkdirs()
                    var remaining = size
                    dest.outputStream().use { out ->
                        while (remaining > 0) {
                            val chunk = minOf(remaining.toInt(), buf.size)
                            val read = gz.read(buf, 0, chunk)
                            if (read < 0) throw IOException("Unexpected EOF in $name")
                            out.write(buf, 0, read)
                            remaining -= read
                        }
                    }
                }

                // Skip padding to 512-byte boundary
                if (size > 0) {
                    val padding = (512 - (size % 512)) % 512
                    var skip = padding
                    while (skip > 0) {
                        val skipped = gz.read(buf, 0, minOf(skip.toInt(), buf.size))
                        if (skipped < 0) throw IOException("Unexpected EOF in tar padding")
                        skip -= skipped
                    }
                }
            }
        } finally {
            gz.close()
            zip.close()
        }
    }

    private fun parseString(data: ByteArray, start: Int, maxLen: Int): String {
        var end = start
        while (end < start + maxLen && data[end].toInt() != 0) end++
        return if (end > start) String(data, start, end - start) else ""
    }

    private fun parseOctal(data: ByteArray, start: Int, len: Int): Long {
        var result = 0L
        for (i in start until start + len) {
            val c = data[i].toInt().toChar()
            if (c >= '0' && c <= '7') {
                result = result * 8 + (c - '0')
            }
        }
        return result
    }
}
