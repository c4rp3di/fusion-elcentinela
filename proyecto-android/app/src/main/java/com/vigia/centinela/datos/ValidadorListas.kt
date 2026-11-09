package com.vigia.centinela.datos

import java.io.File
import java.util.Locale

/**
 * Validador de listas. Una lista es un .txt con un dominio por línea. Antes de tocar la base de datos se comprueba
 * el tamaño y se cuenta cuántas líneas son válidas y cuántas están corruptas.
 */
object ValidadorListas {
    const val TAM_MAX_BYTES: Long = 20L * 1024L * 1024L
    const val MIN_VALIDOS: Int = 50

    private const val MAX_DOMINIO = 253
    private const val MAX_ETIQUETA = 63

    enum class Tipo { VACIA, COMENTARIO, VALIDA, CORRUPTA }

    class Linea(val tipo: Tipo, val dominio: String? = null)

    /** lineas = todas las leídas; validas = dominios correctos; corruptas = ni vacías, ni comentario, ni dominio. */
    class Informe(val lineas: Int, val validas: Int, val corruptas: Int) {
        val ignoradas: Int get() = lineas - validas
    }

    private val ETIQUETA = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?\$")
    private val ESPACIOS = Regex("\\s+")

    /** Prefijos de IP que aparecen en las listas con formato «hosts» (0.0.0.0 dominio.com). */
    private val IP_HOSTS = setOf("0.0.0.0", "127.0.0.1", "::1", "::", "255.255.255.255")

    /** Limpia una línea: quita espacios, pasa a minúsculas y comprueba que parece un dominio. */
    fun clasificar(cruda: String): Linea {
        var l = cruda.removePrefix("\uFEFF").trim()
        if (l.isEmpty()) return Linea(Tipo.VACIA)
        if (l.startsWith("#")) return Linea(Tipo.COMENTARIO)

        val almohadilla = l.indexOf('#')
        if (almohadilla > 0) l = l.substring(0, almohadilla).trim()

        val partes = if (l.none { it.isWhitespace() }) listOf(l) else l.split(ESPACIOS)
        l = when {
            partes.size == 1 -> partes[0]
            partes.size == 2 && partes[0] in IP_HOSTS -> partes[1]
            else -> return Linea(Tipo.CORRUPTA)
        }

        l = l.lowercase(Locale.ROOT).removePrefix("*.").trimEnd('.')
        return if (esDominioValido(l)) Linea(Tipo.VALIDA, l) else Linea(Tipo.CORRUPTA)
    }

    /** Al menos un punto y solo letras, números y guiones dentro de cada etiqueta (sin guion al principio ni al final). */
    private fun esDominioValido(d: String): Boolean {
        if (d.length < 3 || d.length > MAX_DOMINIO || !d.contains('.')) return false
        for (etiqueta in d.split('.')) {
            if (etiqueta.isEmpty() || etiqueta.length > MAX_ETIQUETA || !ETIQUETA.matches(etiqueta)) return false
        }
        return true
    }

    /** Lee el archivo línea a línea (sin cargarlo entero en memoria) y cuenta. No toca la base de datos. */
    fun analizar(archivo: File): Informe {
        var lineas = 0
        var validas = 0
        var corruptas = 0
        archivo.bufferedReader(Charsets.UTF_8).use { lector ->
            while (true) {
                val texto = lector.readLine() ?: break
                lineas++
                when (clasificar(texto).tipo) {
                    Tipo.VALIDA -> validas++
                    Tipo.CORRUPTA -> corruptas++
                    else -> {}
                }
            }
        }
        return Informe(lineas, validas, corruptas)
    }
}
