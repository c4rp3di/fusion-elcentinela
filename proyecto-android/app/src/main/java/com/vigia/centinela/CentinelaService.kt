package com.vigia.centinela

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.vigia.centinela.alerta.AlertaFlotante
import com.vigia.centinela.analisis.AnalizadorEnlaces
import com.vigia.centinela.analisis.AnalizadorEnlaces.Nivel
import com.vigia.centinela.analisis.ExtractorEnlaces
import com.vigia.centinela.datos.GestorListas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object CentinelaEstado {
    /** ¿Está el servicio conectado ahora mismo en este proceso? */
    @Volatile
    var conectado: Boolean = false

    /** ¿Lo tiene el usuario activado en Ajustes → Accesibilidad? (vale aunque el proceso acabe de arrancar). */
    fun habilitado(c: Context): Boolean {
        val yo = ComponentName(c, CentinelaService::class.java)
        val lista = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return lista.split(':').any { ComponentName.unflattenFromString(it) == yo }
    }
}

/**
 * Servicio de accesibilidad de El Centinela. Cuando cambia el contenido de la pantalla o se escribe texto:
 *  1) lee los textos visibles (ignorando los campos de contraseña),
 *  2) busca enlaces con una expresión regular,
 *  3) los pasa al AnalizadorEnlaces,
 *  4) si alguno es sospechoso, muestra la alerta flotante.
 * Todo el trabajo pesado va en una corrutina fuera del hilo principal. Nada se guarda ni se envía.
 */
class CentinelaService : AccessibilityService() {

    /** Aviso de «ha pasado algo»; lo único que lleva es el texto que se estaba escribiendo, si procede. */
    private class Senal(val textoEscrito: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val senales = Channel<Senal>(Channel.CONFLATED)
    private var trabajo: Job? = null

    private lateinit var analizador: AnalizadorEnlaces
    private lateinit var alerta: AlertaFlotante

    // Solo los toca la corrutina de trabajo (una sola a la vez), salvo «descartados», que también se escribe desde la alerta.
    private val cache = LinkedHashMap<String, AnalizadorEnlaces.Resultado>()
    private var generacionVista = -1
    private val ultimaAlerta = ConcurrentHashMap<String, Long>()
    private val descartados = ConcurrentHashMap<String, Long>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        analizador = AnalizadorEnlaces(applicationContext)
        alerta = AlertaFlotante(this)
        CentinelaEstado.conectado = true

        trabajo?.cancel()
        trabajo = scope.launch {
            for (senal in senales) {
                delay(RETARDO_MS) // Agrupa ráfagas de eventos: se mira la pantalla una vez, cuando se calma.
                try {
                    procesar(senal)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Un fallo al leer una pantalla nunca debe tumbar el servicio.
                }
                delay(PAUSA_MS)
            }
        }
    }

    override fun onAccessibilityEvent(evento: AccessibilityEvent?) {
        if (evento == null) return
        val tipo = evento.eventType
        if (tipo != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && tipo != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) return
        if (evento.packageName?.toString() == packageName) return // Nuestra propia alerta y pantallas.
        if (evento.isPassword) return

        val escrito = if (tipo == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            evento.text.joinToString(" ").take(MAX_TEXTO_NODO)
        } else {
            ""
        }
        senales.trySend(Senal(escrito)) // Hilo principal: solo se encola, no se analiza nada aquí.
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        CentinelaEstado.conectado = false
        senales.close()
        scope.cancel()
        if (::alerta.isInitialized) alerta.ocultar()
        super.onDestroy()
    }

    private suspend fun procesar(senal: Senal) {
        val textos = ArrayList<String>()
        if (senal.textoEscrito.isNotEmpty()) textos.add(senal.textoEscrito)

        val raiz = rootInActiveWindow
        if (raiz != null) {
            try {
                if (raiz.packageName?.toString() != packageName) recolectar(raiz, textos)
            } finally {
                liberar(raiz)
            }
        }

        val enlaces = ExtractorEnlaces.buscar(textos)
        if (enlaces.isEmpty()) return

        // Si se ha importado una lista nueva, lo analizado antes ya no vale.
        if (generacionVista != GestorListas.generacion) {
            cache.clear()
            generacionVista = GestorListas.generacion
        }

        var peor: AnalizadorEnlaces.Resultado? = null
        for (enlace in enlaces) {
            val r = cache[enlace] ?: analizador.analizar(enlace).also {
                if (cache.size >= MAX_CACHE) cache.remove(cache.keys.first())
                cache[enlace] = it
            }
            if (r.nivel == Nivel.SEGURO) continue
            if (!debeAvisar(r.dominio)) continue
            val actual = peor
            if (actual == null || r.nivel.ordinal > actual.nivel.ordinal) peor = r
        }

        val aviso = peor ?: return
        ultimaAlerta[aviso.dominio] = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            alerta.mostrar(aviso) { descartado ->
                descartados[descartado.dominio] = System.currentTimeMillis()
            }
        }
    }

    /** No repite el aviso del mismo dominio en un rato, ni si el usuario ya lo ha descartado. */
    private fun debeAvisar(dominio: String): Boolean {
        val ahora = System.currentTimeMillis()
        val descartadoEn = descartados[dominio]
        if (descartadoEn != null && ahora - descartadoEn < DESCARTE_MS) return false
        val avisadoEn = ultimaAlerta[dominio]
        if (avisadoEn != null && ahora - avisadoEn < REPETIR_MS) return false
        return true
    }

    /**
     * Recorre el árbol de nodos de la ventana activa y recoge los textos que podrían contener un enlace.
     * Es iterativo y acotado (nodos y caracteres máximos) para no colgarse con pantallas enormes.
     */
    private fun recolectar(raiz: AccessibilityNodeInfo, destino: MutableList<String>) {
        val pila = ArrayDeque<AccessibilityNodeInfo>()
        pila.addLast(raiz)
        var visitados = 0
        var caracteres = 0

        while (pila.isNotEmpty() && visitados < MAX_NODOS && caracteres < MAX_CARACTERES) {
            val nodo = pila.removeLast()
            visitados++
            try {
                if (nodo.isPassword) continue // Nunca se lee un campo de contraseña.

                caracteres += anotar(nodo.text, destino)
                caracteres += anotar(nodo.contentDescription, destino)

                var i = 0
                while (i < nodo.childCount && visitados + pila.size < MAX_NODOS) {
                    val hijo = nodo.getChild(i)
                    if (hijo != null) pila.addLast(hijo)
                    i++
                }
            } finally {
                if (nodo !== raiz) liberar(nodo)
            }
        }
        while (pila.isNotEmpty()) liberar(pila.removeLast())
    }

    /** Guarda el texto solo si puede contener un enlace; devuelve cuántos caracteres se han leído. */
    private fun anotar(texto: CharSequence?, destino: MutableList<String>): Int {
        if (texto.isNullOrEmpty()) return 0
        val s = if (texto.length > MAX_TEXTO_NODO) texto.subSequence(0, MAX_TEXTO_NODO).toString() else texto.toString()
        if (ExtractorEnlaces.puedeContenerEnlace(s)) destino.add(s)
        return s.length
    }

    @Suppress("DEPRECATION")
    private fun liberar(nodo: AccessibilityNodeInfo) {
        try {
            nodo.recycle() // Obligatorio hasta Android 12; en 13+ no hace nada.
        } catch (e: Exception) {
            // Ya estaba liberado.
        }
    }

    private companion object {
        const val RETARDO_MS = 400L
        const val PAUSA_MS = 600L
        const val MAX_NODOS = 1500
        const val MAX_CARACTERES = 200_000
        const val MAX_TEXTO_NODO = 20_000
        const val MAX_CACHE = 300
        const val REPETIR_MS = 2L * 60L * 1000L
        const val DESCARTE_MS = 30L * 60L * 1000L
    }
}
