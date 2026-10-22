package io.github.brenobarreto.posthog.detect

internal enum class TokenKind {
    IDENT,

    /** A string literal with a fixed value. [Token.text] is the value, [Token.start]/[Token.end] include the quotes. */
    STRING,

    /** Anything else that isn't punctuation: numbers, char literals, strings with interpolation. */
    OTHER,

    /** One punctuation character, or `?.`. */
    PUNCT,
}

internal class Token(val kind: TokenKind, val start: Int, val end: Int, val text: String)

/**
 * Splits source text into the few kinds of token the scanner needs. It skips comments and understands
 * string literals well enough that quotes and parentheses inside them (or inside comments) can't be
 * mistaken for code. It does not parse: regular expression literals in JavaScript and JSX text are not
 * recognised, which at worst hides the rest of that line.
 *
 * Quoted strings end at a line break, so one stray quote can't swallow the file.
 */
internal class Lexer(private val s: CharSequence, private val language: ScanLanguage) {
    private val tokens = ArrayList<Token>()
    private var i = 0
    private var braceDepth = 0
    // Brace depth of each open `${` in a JavaScript template literal, innermost last.
    private val interpolations = ArrayDeque<Int>()

    private val isJs = language == ScanLanguage.JAVASCRIPT
    private val isKotlin = language == ScanLanguage.KOTLIN

    fun lex(): List<Token> {
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '/' && peek(1) == '/' -> skipLineComment()
                c == '/' && peek(1) == '*' -> skipBlockComment()
                c == '"' -> if (isJs) lexQuoted('"') else lexJvmString()
                c == '\'' -> if (isJs) lexQuoted('\'') else lexCharLiteral()
                c == '`' -> if (isJs) lexTemplate(continuation = false) else if (isKotlin) lexBacktickIdentifier() else punct(1)
                isIdentStart(c) -> lexIdentifier()
                c.isDigit() -> lexNumber()
                c == '?' && peek(1) == '.' && !peek(2).isDigit() -> punct(2)
                c == '{' -> {
                    braceDepth++
                    punct(1)
                }
                c == '}' -> closeBrace()
                else -> punct(1)
            }
        }
        return tokens
    }

    private fun peek(offset: Int): Char = if (i + offset < s.length) s[i + offset] else '\u0000'

    private fun emit(kind: TokenKind, start: Int, end: Int, text: String) {
        tokens += Token(kind, start, end, text)
    }

    private fun punct(length: Int) {
        emit(TokenKind.PUNCT, i, i + length, s.substring(i, i + length))
        i += length
    }

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_' || c == '$'

    private fun lexIdentifier() {
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '$')) i++
        emit(TokenKind.IDENT, start, i, s.substring(start, i))
    }

    private fun lexNumber() {
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        if (peek(0) == '.' && peek(1).isDigit()) {
            i++
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        }
        emit(TokenKind.OTHER, start, i, "")
    }

    private fun lexBacktickIdentifier() {
        val start = i
        i++
        while (i < s.length && s[i] != '`' && s[i] != '\n') i++
        val name = s.substring(start + 1, i)
        if (i < s.length && s[i] == '`') i++
        emit(TokenKind.IDENT, start, i, name)
    }

    private fun skipLineComment() {
        while (i < s.length && s[i] != '\n') i++
    }

    private fun skipBlockComment() {
        var depth = 1
        i += 2
        while (i < s.length && depth > 0) {
            when {
                s[i] == '*' && peek(1) == '/' -> {
                    depth--
                    i += 2
                }
                isKotlin && s[i] == '/' && peek(1) == '*' -> { // Kotlin block comments nest
                    depth++
                    i += 2
                }
                else -> i++
            }
        }
    }

    /** A JavaScript string in single or double quotes. */
    private fun lexQuoted(quote: Char) {
        val start = i
        i++
        val value = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> i = appendEscape(value, i)
                c == quote -> {
                    i++
                    emit(TokenKind.STRING, start, i, value.toString())
                    return
                }
                c == '\n' || c == '\r' -> break
                else -> {
                    value.append(c)
                    i++
                }
            }
        }
        emit(TokenKind.OTHER, start, i, "") // unterminated
    }

    /** A Java or Kotlin string: regular, or triple-quoted (Kotlin raw string, Java text block). */
    private fun lexJvmString() {
        val start = i
        if (peek(1) == '"' && peek(2) == '"') {
            val close = indexOf("\"\"\"", start + 3)
            if (close < 0) {
                i = s.length
                emit(TokenKind.OTHER, start, i, "")
                return
            }
            val content = s.substring(start + 3, close)
            i = close + 3
            emit(if (isKotlin && hasKotlinTemplate(content)) TokenKind.OTHER else TokenKind.STRING, start, i, content)
            return
        }
        i++
        val value = StringBuilder()
        var templated = false
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> i = appendEscape(value, i)
                c == '"' -> {
                    i++
                    emit(if (templated) TokenKind.OTHER else TokenKind.STRING, start, i, value.toString())
                    return
                }
                c == '\n' || c == '\r' -> break
                isKotlin && c == '$' && (isIdentStart(peek(1)) || peek(1) == '{') -> {
                    templated = true
                    i = if (peek(1) == '{') skipKotlinTemplateBlock(i + 1) else i + 1
                }
                else -> {
                    value.append(c)
                    i++
                }
            }
        }
        emit(TokenKind.OTHER, start, i, "")
    }

    /** Skips from the `{` at [open] to just past its matching `}`, stepping over nested strings. */
    private fun skipKotlinTemplateBlock(open: Int): Int {
        var depth = 0
        var j = open
        while (j < s.length && s[j] != '\n') {
            when (s[j]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return j + 1
                '"' -> {
                    j++
                    while (j < s.length && s[j] != '"' && s[j] != '\n') j += if (s[j] == '\\') 2 else 1
                }
            }
            j++
        }
        return j
    }

    private fun hasKotlinTemplate(text: String): Boolean =
        text.indices.any { text[it] == '$' && it + 1 < text.length && (isIdentStart(text[it + 1]) || text[it + 1] == '{') }

    /** A Java/Kotlin char literal such as `'"'`; recognised so its quote can't start a string. */
    private fun lexCharLiteral() {
        val start = i
        i++
        var steps = 0
        while (i < s.length && s[i] != '\'' && s[i] != '\n' && steps++ < 12) i += if (s[i] == '\\') 2 else 1
        if (i < s.length && s[i] == '\'') i++
        emit(TokenKind.OTHER, start, minOf(i, s.length), "")
    }

    /**
     * A JavaScript template literal. Without `${}` it is a string literal. With interpolation the text
     * pieces become OTHER tokens and the expressions inside are lexed as code, between `{` and `}` tokens.
     * [continuation] is true when resuming after the `}` that closed an interpolation.
     */
    private fun lexTemplate(continuation: Boolean) {
        val start = i
        if (!continuation) i++
        val value = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> i = appendEscape(value, i)
                c == '`' -> {
                    i++
                    if (continuation) emit(TokenKind.OTHER, start, i, "") else emit(TokenKind.STRING, start, i, value.toString())
                    return
                }
                c == '$' && peek(1) == '{' -> {
                    if (i > start) emit(TokenKind.OTHER, start, i, "")
                    emit(TokenKind.PUNCT, i, i + 2, "{")
                    i += 2
                    braceDepth++
                    interpolations.addLast(braceDepth)
                    return
                }
                else -> {
                    value.append(c)
                    i++
                }
            }
        }
        emit(TokenKind.OTHER, start, i, "") // unterminated
    }

    private fun closeBrace() {
        if (isJs && interpolations.isNotEmpty() && interpolations.last() == braceDepth) {
            interpolations.removeLast()
            braceDepth--
            punct(1)
            lexTemplate(continuation = true)
        } else {
            if (braceDepth > 0) braceDepth--
            punct(1)
        }
    }

    /** Appends the character(s) the escape at [at] stands for and returns the index after it. */
    private fun appendEscape(out: StringBuilder, at: Int): Int {
        if (at + 1 >= s.length) return s.length
        val next = s[at + 1]
        return when (next) {
            'n' -> { out.append('\n'); at + 2 }
            't' -> { out.append('\t'); at + 2 }
            'r' -> { out.append('\r'); at + 2 }
            'b' -> { out.append('\b'); at + 2 }
            'f' -> { out.append('\u000C'); at + 2 }
            '0' -> { out.append('\u0000'); at + 2 }
            'u' -> hex(out, at + 2, 4, at)
            'x' -> if (isJs) hex(out, at + 2, 2, at) else { out.append(next); at + 2 }
            else -> { out.append(next); at + 2 }
        }
    }

    private fun hex(out: StringBuilder, from: Int, digits: Int, escapeStart: Int): Int {
        val code = if (from + digits <= s.length) s.substring(from, from + digits).toIntOrNull(16) else null
        return if (code != null) {
            out.append(code.toChar())
            from + digits
        } else {
            out.append(s[escapeStart + 1])
            escapeStart + 2
        }
    }

    private fun indexOf(needle: String, from: Int): Int {
        var j = from
        while (j + needle.length <= s.length) {
            if (needle.indices.all { s[j + it] == needle[it] }) return j
            j++
        }
        return -1
    }
}
