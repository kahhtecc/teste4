package br.com.supervilab.core

/**
 * JSON mínimo, sem dependências: objetos viram Map<String, Any?>, arrays viram List<Any?>,
 * números viram Long ou Double, além de String, Boolean e null.
 */
object Json {
    class ErroJson(msg: String) : Exception(msg)

    fun parse(texto: String): Any? {
        val p = Parser(texto)
        p.pularEspacos()
        val v = p.valor()
        p.pularEspacos()
        if (p.i != texto.length) throw ErroJson("lixo após o JSON na posição ${p.i}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun objeto(texto: String): Map<String, Any?> =
        parse(texto) as? Map<String, Any?> ?: throw ErroJson("esperava objeto JSON")

    fun stringify(v: Any?): String = StringBuilder().also { escrever(it, v) }.toString()

    private fun escrever(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> escreverString(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float, is Double -> {
                val d = (v as Number).toDouble()
                if (d.isNaN() || d.isInfinite()) sb.append("null")
                else if (d == Math.floor(d) && Math.abs(d) < 1e15) sb.append(d.toLong()).append(".0")
                else sb.append(d.toString())
            }
            is Map<*, *> -> {
                sb.append('{')
                var primeiro = true
                for ((k, valor) in v) {
                    if (!primeiro) sb.append(',')
                    primeiro = false
                    escreverString(sb, k.toString()); sb.append(':'); escrever(sb, valor)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var primeiro = true
                for (e in v) { if (!primeiro) sb.append(','); primeiro = false; escrever(sb, e) }
                sb.append(']')
            }
            else -> escreverString(sb, v.toString())
        }
    }

    private fun escreverString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun pularEspacos() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun valor(): Any? {
            if (i >= s.length) throw ErroJson("fim inesperado")
            return when (val c = s[i]) {
                '{' -> objeto(); '[' -> lista(); '"' -> string()
                't' -> literal("true", true); 'f' -> literal("false", false); 'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) numero() else throw ErroJson("caractere inesperado '$c' em $i")
            }
        }
        fun literal(txt: String, v: Any?): Any? {
            if (!s.startsWith(txt, i)) throw ErroJson("literal inválido em $i")
            i += txt.length; return v
        }
        fun numero(): Any {
            val ini = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(ini, i)
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLongOrNull() ?: t.toDouble()
        }
        fun string(): String {
            i++ // abre aspas
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ErroJson("string sem fim")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw ErroJson("escape sem fim")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> throw ErroJson("escape inválido \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun objeto(): Map<String, Any?> {
            i++; val m = LinkedHashMap<String, Any?>()
            pularEspacos()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                pularEspacos()
                if (i >= s.length || s[i] != '"') throw ErroJson("esperava chave em $i")
                val k = string(); pularEspacos()
                if (i >= s.length || s[i] != ':') throw ErroJson("esperava ':' em $i")
                i++; pularEspacos()
                m[k] = valor(); pularEspacos()
                if (i >= s.length) throw ErroJson("objeto sem fim")
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> throw ErroJson("esperava ',' ou '}' em ${i - 1}") }
            }
        }
        fun lista(): List<Any?> {
            i++; val l = ArrayList<Any?>()
            pularEspacos()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                pularEspacos(); l.add(valor()); pularEspacos()
                if (i >= s.length) throw ErroJson("lista sem fim")
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> throw ErroJson("esperava ',' ou ']' em ${i - 1}") }
            }
        }
    }
}

fun Map<String, Any?>.str(k: String): String? = this[k]?.toString()
fun Map<String, Any?>.num(k: String): Double? = (this[k] as? Number)?.toDouble()
fun Map<String, Any?>.lng(k: String): Long? = (this[k] as? Number)?.toLong()
fun Map<String, Any?>.bool(k: String): Boolean? = this[k] as? Boolean
