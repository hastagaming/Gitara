package io.gitara.workflow

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class ExprError(message: String) : Exception(message)

class ExprContext(private val vars: Map<String, Any?>, val status: () -> String) {
    fun lookup(name: String): Any? = Expr.property(vars, name)
}

private class Tok(val kind: Char, val text: String)

private class Parser(private val tokens: List<Tok>, private val ctx: ExprContext) {
    private var pos = 0

    fun parse(): Any? {
        val value = or()
        if (peek().kind != 'e') throw ExprError("Unexpected '${peek().text}'")
        return value
    }

    private fun peek(): Tok = tokens[pos]

    private fun take(): Tok = tokens[pos++]

    private fun isOp(text: String): Boolean = peek().kind == 'o' && peek().text == text

    private fun expect(text: String) {
        if (!isOp(text)) throw ExprError("Expected '$text'")
        take()
    }

    private fun or(): Any? {
        var left = and()
        while (isOp("||")) {
            take()
            val right = and()
            left = if (Expr.truthy(left)) left else right
        }
        return left
    }

    private fun and(): Any? {
        var left = equality()
        while (isOp("&&")) {
            take()
            val right = equality()
            left = if (!Expr.truthy(left)) left else right
        }
        return left
    }

    private fun equality(): Any? {
        var left = relational()
        while (isOp("==") || isOp("!=")) {
            val op = take().text
            val right = relational()
            val equal = Expr.looseEquals(left, right)
            left = if (op == "==") equal else !equal
        }
        return left
    }

    private fun relational(): Any? {
        var left = unary()
        while (isOp("<") || isOp("<=") || isOp(">") || isOp(">=")) {
            val op = take().text
            val a = Expr.number(left)
            val b = Expr.number(unary())
            left = when (op) {
                "<" -> a < b
                "<=" -> a <= b
                ">" -> a > b
                else -> a >= b
            }
        }
        return left
    }

    private fun unary(): Any? {
        if (isOp("!")) {
            take()
            return !Expr.truthy(unary())
        }
        return postfix()
    }

    private fun postfix(): Any? {
        var value = primary()
        while (true) {
            if (isOp(".")) {
                take()
                val name = take()
                if (name.kind != 'i' && name.kind != 'n') throw ExprError("Expected a property name after '.'")
                value = Expr.property(value, name.text)
            } else if (isOp("[")) {
                take()
                val index = or()
                expect("]")
                value = Expr.index(value, index)
            } else {
                break
            }
        }
        return value
    }

    private fun primary(): Any? {
        val token = take()
        return when (token.kind) {
            's' -> token.text
            'n' -> token.text.toDoubleOrNull() ?: throw ExprError("Invalid number '${token.text}'")
            'i' -> when (token.text.lowercase(Locale.ROOT)) {
                "true" -> true
                "false" -> false
                "null" -> null
                else -> if (isOp("(")) call(token.text) else ctx.lookup(token.text)
            }
            'o' -> {
                if (token.text != "(") throw ExprError("Unexpected '${token.text}'")
                val value = or()
                expect(")")
                value
            }
            else -> throw ExprError("Unexpected end of expression")
        }
    }

    private fun call(name: String): Any? {
        take()
        val args = ArrayList<Any?>()
        if (!isOp(")")) {
            while (true) {
                args.add(or())
                if (isOp(",")) take() else break
            }
        }
        expect(")")
        return Expr.function(name, args, ctx)
    }
}

object Expr {
    private val statusCall = Regex("\\b(success|failure|always|cancelled)\\s*\\(", RegexOption.IGNORE_CASE)

    fun interpolate(text: String, ctx: ExprContext): String {
        if (!text.contains("\${{")) return text
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("\${{", i)
            if (start < 0) {
                sb.append(text, i, text.length)
                break
            }
            sb.append(text, i, start)
            val end = findClose(text, start + 3)
            if (end < 0) throw ExprError("Missing '}}' after '\${{'")
            sb.append(toText(evaluate(text.substring(start + 3, end), ctx)))
            i = end + 2
        }
        return sb.toString()
    }

    fun shouldRun(condition: String?, ctx: ExprContext): Boolean {
        val raw = condition?.trim().orEmpty()
        if (raw.isEmpty()) return ctx.status() == "success"
        val expression = if (raw.startsWith("\${{") && raw.endsWith("}}")) raw.substring(3, raw.length - 2) else raw
        val value = evaluate(expression, ctx)
        return if (statusCall.containsMatchIn(expression)) truthy(value) else ctx.status() == "success" && truthy(value)
    }

    fun evaluate(expression: String, ctx: ExprContext): Any? = Parser(tokenize(expression), ctx).parse()

    private fun findClose(text: String, from: Int): Int {
        var quoted = false
        var i = from
        while (i < text.length) {
            val c = text[i]
            if (c == '\'') {
                quoted = !quoted
            } else if (!quoted && c == '}' && i + 1 < text.length && text[i + 1] == '}') {
                return i
            }
            i++
        }
        return -1
    }

    private fun tokenize(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c.isWhitespace()) {
                i++
            } else if (c == '\'') {
                val sb = StringBuilder()
                i++
                while (true) {
                    if (i >= src.length) throw ExprError("Unterminated string")
                    if (src[i] == '\'') {
                        if (i + 1 < src.length && src[i + 1] == '\'') {
                            sb.append('\'')
                            i += 2
                        } else {
                            i++
                            break
                        }
                    } else {
                        sb.append(src[i])
                        i++
                    }
                }
                out.add(Tok('s', sb.toString()))
            } else if (c.isDigit()) {
                val start = i
                while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                out.add(Tok('n', src.substring(start, i)))
            } else if (c.isLetter() || c == '_') {
                val start = i
                while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '-')) i++
                out.add(Tok('i', src.substring(start, i)))
            } else {
                val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                if (two == "==" || two == "!=" || two == "<=" || two == ">=" || two == "&&" || two == "||") {
                    out.add(Tok('o', two))
                    i += 2
                } else if ("()[].,!<>".indexOf(c) >= 0) {
                    out.add(Tok('o', c.toString()))
                    i++
                } else {
                    throw ExprError("Unexpected character '$c'")
                }
            }
        }
        out.add(Tok('e', ""))
        return out
    }

    fun property(obj: Any?, name: String): Any? {
        if (obj !is Map<*, *>) return null
        for ((key, value) in obj) {
            if (key == name) return value
        }
        for ((key, value) in obj) {
            if (key is String && key.equals(name, ignoreCase = true)) return value
        }
        return null
    }

    fun index(obj: Any?, index: Any?): Any? {
        if (obj is List<*>) {
            val n = number(index)
            if (n.isNaN()) return null
            return obj.getOrNull(n.toInt())
        }
        return property(obj, toText(index))
    }

    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Double -> value != 0.0 && !value.isNaN()
        is Number -> value.toDouble() != 0.0
        is String -> value.isNotEmpty()
        else -> true
    }

    fun number(value: Any?): Double = when (value) {
        null -> 0.0
        is Boolean -> if (value) 1.0 else 0.0
        is Number -> value.toDouble()
        is String -> if (value.isBlank()) 0.0 else value.trim().toDoubleOrNull() ?: Double.NaN
        else -> Double.NaN
    }

    fun looseEquals(a: Any?, b: Any?): Boolean {
        if (a == null && b == null) return true
        if (a is String && b is String) return a.equals(b, ignoreCase = true)
        if (a is Boolean && b is Boolean) return a == b
        if ((a is Map<*, *> || a is List<*>) || (b is Map<*, *> || b is List<*>)) return a === b
        val x = number(a)
        val y = number(b)
        return !x.isNaN() && !y.isNaN() && x == y
    }

    fun toText(value: Any?): String = when (value) {
        null -> ""
        is Boolean -> if (value) "true" else "false"
        is Double -> if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        is String -> value
        is Map<*, *> -> toJson(value)
        is List<*> -> toJson(value)
        else -> value.toString()
    }

    private fun toJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Map<*, *> -> JSONObject(value).toString(2)
        is List<*> -> JSONArray(value).toString(2)
        else -> toText(value)
    }

    fun function(name: String, args: List<Any?>, ctx: ExprContext): Any? {
        return when (name.lowercase(Locale.ROOT)) {
            "success" -> ctx.status() == "success"
            "failure" -> ctx.status() == "failure"
            "cancelled" -> ctx.status() == "cancelled"
            "always" -> true
            "contains" -> {
                need(args, 2, name)
                val haystack = args[0]
                if (haystack is List<*>) {
                    haystack.any { looseEquals(it, args[1]) }
                } else {
                    toText(haystack).lowercase(Locale.ROOT).contains(toText(args[1]).lowercase(Locale.ROOT))
                }
            }
            "startswith" -> {
                need(args, 2, name)
                toText(args[0]).lowercase(Locale.ROOT).startsWith(toText(args[1]).lowercase(Locale.ROOT))
            }
            "endswith" -> {
                need(args, 2, name)
                toText(args[0]).lowercase(Locale.ROOT).endsWith(toText(args[1]).lowercase(Locale.ROOT))
            }
            "format" -> {
                need(args, 1, name)
                format(toText(args[0]), args)
            }
            "join" -> {
                need(args, 1, name)
                val separator = if (args.size > 1) toText(args[1]) else ","
                val list = args[0]
                if (list is List<*>) list.joinToString(separator) { toText(it) } else toText(list)
            }
            "tojson" -> {
                need(args, 1, name)
                toJson(args[0])
            }
            else -> throw ExprError("Unknown function '$name'")
        }
    }

    private fun need(args: List<Any?>, count: Int, name: String) {
        if (args.size < count) throw ExprError("$name() needs at least $count argument(s)")
    }

    private fun format(pattern: String, args: List<Any?>): String {
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '{' && i + 1 < pattern.length && pattern[i + 1] == '{') {
                sb.append('{')
                i += 2
            } else if (c == '}' && i + 1 < pattern.length && pattern[i + 1] == '}') {
                sb.append('}')
                i += 2
            } else if (c == '{') {
                val close = pattern.indexOf('}', i)
                if (close < 0) throw ExprError("Invalid format() string")
                val index = pattern.substring(i + 1, close).toIntOrNull() ?: throw ExprError("Invalid format() placeholder")
                sb.append(toText(args.getOrNull(index + 1)))
                i = close + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}