package com.eratverbum.stepbible

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object TarExtractor {

    fun extractFromApk(apkPath: String, entryName: String, destDir: File) {
        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry(entryName)
                ?: throw IOException("Entry not found: $entryName")
            val raw = zip.getInputStream(entry)
            try {
                extractTar(raw, destDir)
            } finally {
                raw.close()
            }
        }
    }

    private fun extractTar(raw: java.io.InputStream, destDir: File) {
        val buf = ByteArray(64 * 1024)
        var pendingPath: String? = null

        while (true) {
            val header = ByteArray(512)
            var offset = 0
            while (offset < 512) {
                val read = raw.read(header, offset, 512 - offset)
                if (read < 0) return
                offset += read
            }

            if (header.all { it == 0.toByte() }) return

            val size = parseOctal(header, 124, 12)
            val type = header[156].toInt().toChar()

            if (type == 'L' || type == 'K') {
                val longName = readLongName(raw, size, buf)
                if (type == 'L') pendingPath = longName
                skipPadding(raw, size, buf)
                continue
            }
            if (type == 'x') {
                pendingPath = readExtendedPath(raw, size, buf)
                skipPadding(raw, size, buf)
                continue
            }

            val name = parseString(header, 0, 100)
            if (name.isEmpty() && pendingPath.isNullOrEmpty()) return

            val resolvedName = pendingPath ?: name
            pendingPath = null

            val cleanName = if (resolvedName.startsWith("./")) resolvedName.removePrefix("./") else resolvedName
            if (cleanName.contains("..") || cleanName.startsWith("/"))
                throw IOException("Invalid tar entry path: $cleanName")
            val dest = File(destDir, cleanName)

            if (type == '5' || type == '\u0000' && cleanName.endsWith("/")) {
                dest.mkdirs()
            } else if (size > 0) {
                dest.parentFile?.mkdirs()
                var remaining = size
                dest.outputStream().use { out ->
                    while (remaining > 0) {
                        val chunk = minOf(remaining.toInt(), buf.size)
                        val read = raw.read(buf, 0, chunk)
                        if (read < 0) throw IOException("Unexpected EOF in $cleanName")
                        out.write(buf, 0, read)
                        remaining -= read
                    }
                }
            }

            skipPadding(raw, size, buf)
        }
    }

    private fun skipPadding(stream: java.io.InputStream, size: Long, buf: ByteArray) {
        if (size > 0) {
            val padLen = ((512 - (size % 512)) % 512).toInt()
            var remaining = padLen
            while (remaining > 0) {
                val n = stream.read(buf, 0, minOf(remaining, buf.size))
                if (n < 0) break
                remaining -= n
            }
        }
    }

    private fun readLongName(raw: java.io.InputStream, size: Long, buf: ByteArray): String? {
        if (size <= 0 || size > 4096) return null
        val data = ByteArray(size.toInt())
        var offset = 0
        while (offset < data.size) {
            val read = raw.read(data, offset, data.size - offset)
            if (read < 0) return null
            offset += read
        }
        return String(data).trimEnd('\u0000')
    }

    private fun readExtendedPath(raw: java.io.InputStream, size: Long, buf: ByteArray): String? {
        if (size <= 0) return null
        val data = ByteArray(size.toInt())
        var offset = 0
        while (offset < data.size) {
            val read = raw.read(data, offset, data.size - offset)
            if (read < 0) throw IOException("Unexpected EOF in extended header")
            offset += read
        }
        val text = String(data)
        val pathPrefix = " path="
        var idx = text.indexOf(pathPrefix)
        if (idx >= 0) {
            val start = idx + pathPrefix.length
            val end = text.indexOf('\n', start)
            return if (end >= start) text.substring(start, end) else text.substring(start)
        }
        return null
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
