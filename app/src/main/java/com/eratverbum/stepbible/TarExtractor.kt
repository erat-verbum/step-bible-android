package com.eratverbum.stepbible

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

object TarExtractor {

    private const val TAR_BLOCK_SIZE = 512
    private const val OFFSET_SIZE = 124
    private const val LEN_SIZE = 12
    private const val OFFSET_TYPE = 156
    private const val LEN_NAME = 100
    private const val MAX_LONG_NAME = 4096
    private const val BUFFER_SIZE = 64 * 1024

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

    private fun extractTar(raw: InputStream, destDir: File) {
        val buf = ByteArray(BUFFER_SIZE)
        var pendingPath: String? = null

        while (true) {
            val header = ByteArray(TAR_BLOCK_SIZE)
            var offset = 0
            while (offset < TAR_BLOCK_SIZE) {
                val read = raw.read(header, offset, TAR_BLOCK_SIZE - offset)
                if (read < 0) return
                offset += read
            }

            if (header.all { it == 0.toByte() }) return

            val size = parseOctal(header, OFFSET_SIZE, LEN_SIZE)
            val type = (header[OFFSET_TYPE].toInt() and 0xff).toChar()

            if (type == 'L' || type == 'K') {
                val longName = readLongName(raw, size)
                if (type == 'L') pendingPath = longName
                skipPadding(raw, size, buf)
                continue
            }
            if (type == 'x') {
                pendingPath = readExtendedPath(raw, size)
                skipPadding(raw, size, buf)
                continue
            }

            val name = parseString(header, 0, LEN_NAME)
            if (name.isEmpty() && pendingPath.isNullOrEmpty()) return

            val resolvedName = pendingPath ?: name
            pendingPath = null

            val cleanName = if (resolvedName.startsWith("./")) resolvedName.removePrefix("./") else resolvedName
            if (cleanName.split("/").any { it == ".." } || cleanName.startsWith("/"))
                throw IOException("Invalid tar entry path: $cleanName")
            val dest = File(destDir, cleanName)

            if (type == '5' || type == '\u0000' && cleanName.endsWith("/")) {
                dest.mkdirs()
            } else {
                dest.parentFile?.mkdirs()
                var remaining = size
                dest.outputStream().use { out ->
                    while (remaining > 0) {
                        val chunk = minOf(remaining, buf.size.toLong()).toInt()
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

    private fun readExact(raw: InputStream, size: Long): ByteArray {
        if (size <= 0 || size > MAX_LONG_NAME) throw IOException("Invalid entry size: $size")
        val data = ByteArray(size.toInt())
        var offset = 0
        while (offset < data.size) {
            val read = raw.read(data, offset, data.size - offset)
            if (read < 0) throw IOException("Unexpected EOF reading $size bytes")
            offset += read
        }
        return data
    }

    private fun skipPadding(stream: InputStream, size: Long, buf: ByteArray) {
        if (size > 0) {
            val padLen = ((TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE).toInt()
            var remaining = padLen
            while (remaining > 0) {
                val n = stream.read(buf, 0, minOf(remaining, buf.size))
                if (n < 0) break
                remaining -= n
            }
        }
    }

    private fun readLongName(raw: InputStream, size: Long): String? {
        if (size <= 0 || size > MAX_LONG_NAME) return null
        val data = readExact(raw, size)
        return String(data).trimEnd('\u0000')
    }

    private fun readExtendedPath(raw: InputStream, size: Long): String? {
        if (size <= 0 || size > MAX_LONG_NAME) return null
        val data = readExact(raw, size)
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
