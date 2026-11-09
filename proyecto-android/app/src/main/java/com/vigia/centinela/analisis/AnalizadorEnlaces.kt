package com.vigia.centinela.analisis

import android.content.Context
import androidx.annotation.StringRes
import com.vigia.centinela.R
import com.vigia.centinela.datos.BaseDatos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decide si un enlace es peligroso. Todo es local:
 *  1) ¿Está su dominio (o uno de sus dominios padre) en la lista de amenazas? → CRÍTICO.
 *  2) Si no, heurísticas: IP directa, terminación sospechosa, enlace acortado, marca imitada.
 */
class AnalizadorEnlaces(contexto: Context) {

    enum class Nivel { SEGURO, SOSPECHOSO, CRITICO }

    /** Una razón por la que se avisa. El texto sale de los recursos para poder traducirlo. */
    class Motivo(@StringRes val recurso: Int, val argumento: String = "")

    class Resultado(val enlace: String, val dominio: String, val nivel: Nivel, val motivos: List<Motivo>)

    private val dao = BaseDatos.obtener(contexto).dominios()

    suspend fun analizar(enlace: String): Resultado {
        val d = ExtractorEnlaces.descomponer(enlace)
            ?: return Resultado(enlace, enlace, Nivel.SEGURO, emptyList())

        val motivos = ArrayList<Motivo>()

        val coincidencia: String? = withContext(Dispatchers.IO) {
            try {
                dao.buscar(Heuristicas.candidatosLista(d.host))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // Si la base de datos falla, se sigue con las heurísticas.
            }
        }
        if (coincidencia != null) motivos.add(Motivo(R.string.motivo_lista, coincidencia))

        motivos.addAll(Heuristicas.evaluar(d.host, d.conArroba))

        val nivel = when {
            coincidencia != null -> Nivel.CRITICO
            motivos.isNotEmpty() -> Nivel.SOSPECHOSO
            else -> Nivel.SEGURO
        }
        return Resultado(enlace, d.host, nivel, motivos)
    }
}

/** Comprobaciones puras sobre un dominio. No usan Context ni base de datos. */
object Heuristicas {

    private val TLD_SOSPECHOSOS = setOf(
        "xyz", "top", "click", "buzz", "icu", "vip", "cyou", "rest", "monster", "sbs", "cfd",
        "quest", "tk", "ml", "ga", "cf", "gq", "loan", "bid", "zip", "mov"
    )

    private val ACORTADORES = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "ow.ly", "is.gd", "buff.ly", "cutt.ly",
        "rebrand.ly", "shorturl.at", "tiny.cc", "rb.gy", "t.ly", "s.id", "lnkd.in"
    )

    /** Terminaciones de dos partes en las que el nombre registrable son tres etiquetas (miweb.co.uk, miweb.com.es). */
    private val SEGUNDO_NIVEL = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "com.es", "org.es", "gob.es", "edu.es", "nom.es",
        "com.mx", "com.ar", "com.br", "com.co", "com.pe", "com.ve", "com.ec", "com.au", "net.au",
        "co.jp", "co.in", "co.nz", "com.tr", "com.cn"
    )

    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\$")

    fun evaluar(host: String, conArroba: Boolean): List<AnalizadorEnlaces.Motivo> {
        val motivos = ArrayList<AnalizadorEnlaces.Motivo>()

        // a) IP directa
        if (esIpDirecta(host)) {
            motivos.add(AnalizadorEnlaces.Motivo(R.string.motivo_ip))
        } else {
            // b) Terminación sospechosa
            val tld = host.substringAfterLast('.')
            if (tld in TLD_SOSPECHOSOS) motivos.add(AnalizadorEnlaces.Motivo(R.string.motivo_tld, tld))

            // c) Enlace acortado
            if (dominioRegistrable(host) in ACORTADORES) motivos.add(AnalizadorEnlaces.Motivo(R.string.motivo_acortado))

            // d) Marca imitada (se citan como mucho dos)
            for (marca in marcasImitadas(host).take(2)) {
                motivos.add(AnalizadorEnlaces.Motivo(R.string.motivo_marca, marca.clave))
            }
        }

        // Extra: «usuario@» delante del dominio, truco clásico para disfrazar el destino.
        if (conArroba) motivos.add(AnalizadorEnlaces.Motivo(R.string.motivo_arroba))
        return motivos
    }

    /**
     * ¿El dominio es una dirección IP? Cuenta IPv4, IPv6, y las formas disfrazadas (número entero o hexadecimal).
     * Las direcciones de la red local (192.168.x.x, 10.x.x.x…) no se consideran: no son phishing, son tu router.
     */
    fun esIpDirecta(host: String): Boolean {
        if (host.startsWith("[")) return host != "[::1]"
        val m = IPV4.matchEntire(host)
        if (m != null) {
            val o = m.groupValues.drop(1).map { it.toInt() }
            if (o.any { it > 255 }) return false
            return !esRedLocal(o)
        }
        if (host.length in 5..10 && host.all { it.isDigit() }) return true
        if (host.startsWith("0x") && host.length > 2 && host.drop(2).all { it.isDigit() || it in 'a'..'f' }) return true
        return false
    }

    private fun esRedLocal(o: List<Int>): Boolean =
        o[0] == 10 || o[0] == 127 || (o[0] == 192 && o[1] == 168) || (o[0] == 172 && o[1] in 16..31) || (o[0] == 169 && o[1] == 254)

    /** «a.b.paypal.co.uk» → «paypal.co.uk». Aproximación sin lista pública de sufijos completa. */
    fun dominioRegistrable(host: String): String {
        val p = host.split('.')
        if (p.size <= 2) return host
        val ultimas2 = p.takeLast(2).joinToString(".")
        return if (ultimas2 in SEGUNDO_NIVEL) p.takeLast(3).joinToString(".") else ultimas2
    }

    /** «a.b.mal.com» → [a.b.mal.com, b.mal.com, mal.com]: así un subdominio de un dominio listado también salta. */
    fun candidatosLista(host: String): List<String> {
        if (host.startsWith("[") || !host.contains('.') || IPV4.matches(host)) return listOf(host)
        val p = host.split('.')
        val salida = ArrayList<String>()
        for (i in 0 until p.size - 1) salida.add(p.subList(i, p.size).joinToString("."))
        return salida
    }

    private fun marcasImitadas(host: String): List<Marcas.Marca> {
        val registrable = dominioRegistrable(host)
        val variantes = variantesDe(host)
        val palabras = host.split('.', '-')
        return Marcas.LISTA.filter { m ->
            val aparece = if (m.soloPalabra) palabras.any { it == m.clave } else variantes.any { it.contains(m.clave) }
            aparece && !m.esLegitimo(registrable)
        }
    }

    /** El dominio tal cual, sin guiones («pay-pal») y con cifras por letras («paypa1», «amaz0n»). */
    private fun variantesDe(host: String): Set<String> {
        val sinGuion = host.replace("-", "")
        return setOf(host, sinGuion, cifrasPorLetras(sinGuion, 'l'), cifrasPorLetras(sinGuion, 'i'))
    }

    private fun cifrasPorLetras(s: String, uno: Char): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    '0' -> 'o'
                    '1' -> uno
                    '3' -> 'e'
                    '4' -> 'a'
                    '5' -> 's'
                    '7' -> 't'
                    else -> c
                }
            )
        }
        return sb.toString()
    }
}
