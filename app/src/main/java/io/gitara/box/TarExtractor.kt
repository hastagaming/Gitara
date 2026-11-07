package io.gitara.box

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

private const val BLOCK = 512

internal class TarEntry(
    val name: String,
    val linkName: String,
    val type: Char,
    val mode: Int
)

internal class TarReader(private val input: InputStream) {
    private val header = ByteArray(BLOCK)
    private var remaining = 0L
    private var padding = 0L

    fun next(): TarEntry? {
        copyContent(null)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        while (true) {
            if (!readBlock()) return null
            if (header.all { it == 0.toByte() }) return null
            var name = text(0, 100)
            val mode = octal(100, 8).toInt()
            val size = size()
            val type = if (header[156].toInt() == 0) '0' else (header[156].toInt() and 0xFF).toChar()
            val link = text(157, 100)
            if (text(257, 6).startsWith("ustar")) {
                val prefix = text(345, 155)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
            }
            when (type) {
                'L' -> longName = readText(size).trimEnd('\n')
                'K' -> longLink = readText(size).trimEnd('\n')
                'x' -> {
                    for (line in readText(size).split('\n')) {
                        val space = line.indexOf(' ')
                        val eq = line.indexOf('=')
                        if (space < 0 || eq < space) continue
                        val key = line.substring(space + 1, eq)
                        val value = line.substring(eq + 1)
                        if (key == "path") paxPath = value
                        if (key == "linkpath") paxLink = value
                    }
                }
                'g' -> skipFully(size + pad(size))
                else -> {
                    remaining = size
                    padding = pad(size)
                    return TarEntry(paxPath ?: longName ?: name, paxLink ?: longLink ?: link, type, mode)
                }
            }
        }
    }

    fun copyContent(out: OutputStream?) {
        val buffer = ByteArray(32 * 1024)
        var left = remaining
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw IOException("Truncated tar entry")
            out?.write(buffer, 0, read)
            left -= read
        }
        skipFully(padding)
        remaining = 0
        padding = 0
    }

    private fun readText(size: Long): String {
        if (size > 1_000_000L) throw IOException("Tar metadata entry is too large")
        val data = ByteArray(size.toInt())
        var read = 0
        while (read < data.size) {
            val n = input.read(data, read, data.size - read)
            if (n < 0) throw IOException("Truncated tar metadata")
            read += n
        }
        skipFully(pad(size))
        return String(data, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun pad(size: Long): Long = (BLOCK - (size % BLOCK)) % BLOCK

    private fun skipFully(count: Long) {
        val buffer = ByteArray(4096)
        var left = count
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw IOException("Truncated tar archive")
            left -= read
        }
    }

    private fun readBlock(): Boolean {
        var read = 0
        while (read < BLOCK) {
            val n = input.read(header, read, BLOCK - read)
            if (n < 0) {
                if (read == 0) return false
                throw IOException("Truncated tar header")
            }
            read += n
        }
        return true
    }

    private fun text(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && header[end].toInt() != 0) end++
        return String(header, offset, end - offset, Charsets.UTF_8)
    }

    private fun octal(offset: Int, length: Int): Long {
        val s = String(header, offset, length, Charsets.US_ASCII).trim { it == '\u0000' || it == ' ' }
        return if (s.isEmpty()) 0L else s.toLong(8)
    }

    private fun size(): Long {
        if ((header[124].toInt() and 0x80) != 0) {
            var value = (header[124].toInt() and 0x7F).toLong()
            for (i in 125 until 136) value = (value shl 8) or (header[i].toLong() and 0xFF)
            return value
        }
        return octal(124, 12)
    }
}

private class CountingStream(
    input: InputStream,
    private val onProgress: (Long) -> Unit
) : FilterInputStream(input) {
    private var total = 0L
    private var lastReport = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) advance(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) advance(read)
        return read
    }

    private fun advance(count: Int) {
        total += count
        val now = SystemClock.elapsedRealtime()
        if (now - lastReport >= 150) {
            lastReport = now
            onProgress(total)
        }
    }
}

object TarExtractor {

    fun extractGzip(archive: File, dest: File, onProgress: (Long) -> Unit) {
        dest.mkdirs()
        val root = dest.absoluteFile
        archive.inputStream().use { raw ->
            GZIPInputStream(CountingStream(raw, onProgress), 64 * 1024).use { gzip ->
                extract(TarReader(gzip), root)
            }
        }
    }

    private fun extract(reader: TarReader, root: File) {
        while (true) {
            val entry = reader.next() ?: break
            val name = clean(entry.name)
            if (name == null) {
                reader.copyContent(null)
                continue
            }
            val target = File(root, name)
            when (entry.type) {
                '5' -> {
                    target.mkdirs()
                    chmod(target, (entry.mode and 0x1FF) or 0x1C0)
                }
                '0', '7' -> {
                    target.parentFile?.mkdirs()
                    remove(target)
                    target.outputStream().buffered().use { reader.copyContent(it) }
                    chmod(target, (entry.mode and 0x1FF) or 0x180)
                }
                '2' -> {
                    target.parentFile?.mkdirs()
                    remove(target)
                    try {
                        Os.symlink(entry.linkName, target.path)
                    } catch (e: ErrnoException) {
                        throw IOException("Cannot create symlink ${entry.name}: ${e.message}")
                    }
                    reader.copyContent(null)
                }
                '1' -> {
                    val sourceName = clean(entry.linkName)
                    val source = if (sourceName != null) File(root, sourceName) else null
                    if (source != null && source.isFile) {
                        target.parentFile?.mkdirs()
                        remove(target)
                        source.copyTo(target, overwrite = true)
                        chmod(target, (entry.mode and 0x1FF) or 0x180)
                    }
                    reader.copyContent(null)
                }
                else -> reader.copyContent(null)
            }
        }
    }

    private fun clean(raw: String): String? {
        val parts = raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) throw IOException("Unsafe path in archive: $raw")
        return parts.joinToString("/")
    }

    private fun chmod(file: File, mode: Int) {
        try {
            Os.chmod(file.path, mode)
        } catch (e: ErrnoException) {
            // permissions are best effort; proot fakes ownership anyway
        }
    }

    private fun remove(file: File) {
        try {
            Os.remove(file.path)
        } catch (e: ErrnoException) {
            // nothing to remove
        }
    }
}