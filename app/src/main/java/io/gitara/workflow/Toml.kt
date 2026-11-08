package io.gitara.workflow

class TomlException(message: String, val line: Int) : Exception("line $line: $message")

object Toml {

    fun parse(text: String): Map<String, Any?> = Parser(text.removePrefix("\uFEFF")).parse()

    private class Parser(private val src: String) {
        private var pos = 0
        private var line = 1
        private val root = LinkedHashMap<String, Any?>()
        private var current: MutableMap<String, Any?> = root

        fun parse(): Map<String, Any?> {
            while (true) {
                skipBlank()
                if (eof()) break
                if (peek() == '[') parseTableHeader() else parseKeyValue()
            }
            return root
        }

        private fun eof(): Boolean = pos >= src.length

        private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

        private fun next(): Char {
            val c = src[pos++]
            if (c == '\n') line++
            return c
        }

        private fun fail(message: String): Nothing = throw TomlException(message, line)

        private fun expect(c: Char) {
            if (peek() != c) fail("Expected '$c'")
            pos++
        }

        private fun skipSpaces() {
            while (!eof() && (peek() == ' ' || peek() == '\t')) pos++
        }

        private fun skipComment() {
            while (!eof() && peek() != '\n') pos++
        }

        private fun skipBlank() {
            while (!eof()) {
                val c = peek()
                when {
                    c == ' ' || c == '\t' || c == '\r' || c == '\n' -> next()
                    c == '#' -> skipComment()
                    else -> return
                }
            }
        }

        private fun endOfLine() {
            skipSpaces()
            if (peek() == '#') skipComment()
            if (eof()) return
            when (peek()) {
                '\r' -> {
                    pos++
                    if (peek() == '\n') next()
                }
                '\n' -> next()
                else -> fail("Unexpected characters after value")
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun childMap(parent: MutableMap<String, Any?>, key: String): MutableMap<String, Any?> {
            return when (val existing = parent[key]) {
                null -> LinkedHashMap<String, Any?>().also { parent[key] = it }
                is MutableMap<*, *> -> existing as MutableMap<String, Any?>
                is MutableList<*> -> {
                    val last = existing.lastOrNull()
                    if (last is MutableMap<*, *>) last as MutableMap<String, Any?> else fail("Key '$key' is not a table")
                }
                else -> fail("Key '$key' is not a table")
            }
        }

        private fun parseTableHeader() {
            pos++
            val isArray = peek() == '['
            if (isArray) pos++
            val path = parseKeyPath()
            skipSpaces()
            expect(']')
            if (isArray) expect(']')
            endOfLine()
            current = if (isArray) openArrayTable(path) else openTable(path)
        }

        private fun openTable(path: List<String>): MutableMap<String, Any?> {
            var table: MutableMap<String, Any?> = root
            for (key in path) table = childMap(table, key)
            return table
        }

        @Suppress("UNCHECKED_CAST")
        private fun openArrayTable(path: List<String>): MutableMap<String, Any?> {
            var parent: MutableMap<String, Any?> = root
            for (key in path.dropLast(1)) parent = childMap(parent, key)
            val last = path.last()
            val list: MutableList<Any?> = when (val existing = parent[last]) {
                null -> ArrayList<Any?>().also { parent[last] = it }
                is MutableList<*> -> existing as MutableList<Any?>
                else -> fail("Key '$last' is not an array of tables")
            }
            val table = LinkedHashMap<String, Any?>()
            list.add(table)
            return table
        }

        private fun parseKeyValue() {
            val path = parseKeyPath()
            skipSpaces()
            expect('=')
            skipSpaces()
            val value = parseValue()
            endOfLine()
            var target = current
            for (key in path.dropLast(1)) target = childMap(target, key)
            val last = path.last()
            if (target.containsKey(last)) fail("Duplicate key '$last'")
            target[last] = value
        }

        private fun parseKeyPath(): List<String> {
            val keys = ArrayList<String>()
            while (true) {
                skipSpaces()
                keys.add(parseSingleKey())
                skipSpaces()
                if (peek() == '.') pos++ else break
            }
            return keys
        }

        private fun parseSingleKey(): String {
            val c = peek()
            if (c == '"') {
                pos++
                return readBasic(false)
            }
            if (c == '\'') {
                pos++
                return readLiteral(false)
            }
            val start = pos
            while (!eof()) {
                val ch = peek()
                if (ch.isLetterOrDigit() || ch == '_' || ch == '-') pos++ else break
            }
            if (pos == start) fail("Invalid key")
            return src.substring(start, pos)
        }

        private fun parseValue(): Any? {
            skipSpaces()
            if (eof()) fail("Missing value")
            return when (peek()) {
                '"' -> {
                    if (src.startsWith("\"\"\"", pos)) {
                        pos += 3
                        readBasic(true).replace("\r\n", "\n")
                    } else {
                        pos++
                        readBasic(false)
                    }
                }
                '\'' -> {
                    if (src.startsWith("'''", pos)) {
                        pos += 3
                        readLiteral(true).replace("\r\n", "\n")
                    } else {
                        pos++
                        readLiteral(false)
                    }
                }
                '[' -> parseArray()
                '{' -> parseInline()
                else -> parseScalar()
            }
        }

        private fun parseArray(): List<Any?> {
            pos++
            val list = ArrayList<Any?>()
            while (true) {
                skipBlank()
                if (peek() == ']') {
                    pos++
                    return list
                }
                list.add(parseValue())
                skipBlank()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> fail("Expected ',' or ']' in array")
                }
            }
        }

        private fun parseInline(): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipSpaces()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                val path = parseKeyPath()
                skipSpaces()
                expect('=')
                skipSpaces()
                val value = parseValue()
                var target: MutableMap<String, Any?> = map
                for (key in path.dropLast(1)) target = childMap(target, key)
                val last = path.last()
                if (target.containsKey(last)) fail("Duplicate key '$last'")
                target[last] = value
                skipSpaces()
                when (peek()) {
                    ',' -> {
                        pos++
                        skipSpaces()
                    }
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> fail("Expected ',' or '}' in inline table")
                }
            }
        }

        private fun parseScalar(): Any {
            val start = pos
            while (!eof()) {
                val c = peek()
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == ',' || c == ']' || c == '}' || c == '#') break
                pos++
            }
            val token = src.substring(start, pos)
            if (token.isEmpty()) fail("Missing value")
            if (token == "true") return true
            if (token == "false") return false
            val clean = token.replace("_", "")
            val isFloat = clean.contains('.') || clean.contains('e') || clean.contains('E')
            return try {
                if (isFloat) clean.toDouble() else clean.toLong()
            } catch (e: NumberFormatException) {
                fail("Unsupported value '$token'")
            }
        }

        private fun trimLeadingNewline() {
            if (peek() == '\r') pos++
            if (peek() == '\n') next()
        }

        private fun readBasic(multi: Boolean): String {
            val sb = StringBuilder()
            if (multi) trimLeadingNewline()
            while (true) {
                if (eof()) fail("Unterminated string")
                val c = next()
                when {
                    c == '"' -> {
                        if (!multi) return sb.toString()
                        var run = 1
                        while (peek() == '"') {
                            pos++
                            run++
                        }
                        if (run >= 3) {
                            repeat(run - 3) { sb.append('"') }
                            return sb.toString()
                        }
                        repeat(run) { sb.append('"') }
                    }
                    c == '\\' -> readEscape(sb, multi)
                    c == '\n' && !multi -> fail("Newline inside a single-line string")
                    else -> sb.append(c)
                }
            }
        }

        private fun readEscape(sb: StringBuilder, multi: Boolean) {
            if (eof()) fail("Invalid escape sequence")
            when (val e = next()) {
                'b' -> sb.append('\b')
                't' -> sb.append('\t')
                'n' -> sb.append('\n')
                'f' -> sb.append('\u000C')
                'r' -> sb.append('\r')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                'u' -> sb.appendCodePoint(readHex(4))
                'U' -> sb.appendCodePoint(readHex(8))
                ' ', '\t', '\r', '\n' -> {
                    if (!multi) fail("Invalid escape sequence")
                    while (!eof()) {
                        val c = peek()
                        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') next() else break
                    }
                }
                else -> fail("Invalid escape sequence '\\$e'")
            }
        }

        private fun readHex(count: Int): Int {
            if (pos + count > src.length) fail("Invalid unicode escape")
            val value = src.substring(pos, pos + count).toLongOrNull(16) ?: fail("Invalid unicode escape")
            pos += count
            if (value > 0x10FFFFL || !Character.isValidCodePoint(value.toInt())) fail("Invalid unicode code point")
            return value.toInt()
        }

        private fun readLiteral(multi: Boolean): String {
            val sb = StringBuilder()
            if (multi) trimLeadingNewline()
            while (true) {
                if (eof()) fail("Unterminated string")
                val c = next()
                if (c == '\'') {
                    if (!multi) return sb.toString()
                    var run = 1
                    while (peek() == '\'') {
                        pos++
                        run++
                    }
                    if (run >= 3) {
                        repeat(run - 3) { sb.append('\'') }
                        return sb.toString()
                    }
                    repeat(run) { sb.append('\'') }
                } else if (c == '\n' && !multi) {
                    fail("Newline inside a single-line string")
                } else {
                    sb.append(c)
                }
            }
        }
    }
}