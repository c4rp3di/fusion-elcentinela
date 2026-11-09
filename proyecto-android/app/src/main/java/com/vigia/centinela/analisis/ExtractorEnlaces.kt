package com.vigia.centinela.analisis

import java.util.Locale

/** Busca enlaces dentro de un texto y los descompone para poder analizarlos. No hace ninguna conexión de red. */
object ExtractorEnlaces {

    /** El enlace separado en piezas: solo importa el dominio (host) y si trae un «usuario@» delante. */
    class Enlace(val original: String, val host: String, val conArroba: Boolean)

    // http://, https:// o www. (sin letra o número delante, para no pillar «awww.»). Se corta en espacios y comillas.
    private val PATRON = Regex("""(?i)(?<![a-z0-9])(?:https?://|www\.)[^\s<>"'`]+""")

    private const val MAX_LONGITUD = 2048
    private const val PUNTUACION_FINAL = ".,;:!?)]}>»”’"

    /** Filtro barato para no ejecutar la expresión regular sobre textos que no pueden contener enlaces. */
    fun puedeContenerEnlace(texto: String): Boolean =
        texto.contains("://") || texto.contains("www.", ignoreCase = true)

    /** Enlaces distintos encontrados en los textos, en orden de aparición y hasta un máximo. */
    fun buscar(textos: List<String>, max: Int = 30): List<String> {
        val vistos = LinkedHashSet<String>()
        for (texto in textos) {
            if (!puedeContenerEnlace(texto)) continue
            for (m in PATRON.findAll(texto)) {
                val enlace = m.value.trimEnd { it in PUNTUACION_FINAL }
                if (enlace.length in 8..MAX_LONGITUD) vistos.add(enlace)
                if (vistos.size >= max) return vistos.toList()
            }
        }
        return vistos.toList()
    }

    /** Saca el dominio de un enlace. Devuelve null si no tiene pinta de dirección web (por ejemplo «http://localhost»). */
    fun descomponer(enlace: String): Enlace? {
        var resto = enlace.trim()
        val esquema = resto.indexOf("://")
        if (esquema in 0..10) resto = resto.substring(esquema + 3)

        val fin = resto.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }
        var autoridad = if (fin >= 0) resto.substring(0, fin) else resto

        // «https://paypal.com@sitio-malo.xyz»: el destino real es lo que va detrás de la arroba.
        val arroba = autoridad.lastIndexOf('@')
        val conArroba = arroba >= 0
        if (conArroba) autoridad = autoridad.substring(arroba + 1)

        val bruto: String
        if (autoridad.startsWith("[")) {
            val cierre = autoridad.indexOf(']')
            if (cierre < 0) return null
            bruto = autoridad.substring(0, cierre + 1)
        } else {
            bruto = autoridad.substringBefore(':')
        }
        val host = bruto.lowercase(Locale.ROOT).trimEnd('.')

        if (host.isEmpty() || host.length > 253) return null
        // Sin punto no es una dirección web, salvo las IP disfrazadas de número (http://3232235777/).
        if (!host.startsWith("[") && !host.contains('.') && !Heuristicas.esIpDirecta(host)) return null
        return Enlace(enlace, host, conArroba)
    }
}
