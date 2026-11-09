package com.vigia.centinela.datos

import android.content.Context
import android.content.SharedPreferences

/** Preferencias persistentes de la app (SharedPreferences). Nada de esto sale del teléfono. */
object Ajustes {
    private const val ARCHIVO = "centinela_ajustes"
    private const val K_ENLACE_GENERADOR = "enlace_generador"
    private const val K_LISTA_FECHA = "lista_fecha"
    private const val K_LISTA_TOTAL = "lista_total"

    private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(ARCHIVO, Context.MODE_PRIVATE)

    /** Dirección de la página del Generador de Listas del usuario ("" si no la ha escrito). */
    fun enlaceGenerador(c: Context): String = prefs(c).getString(K_ENLACE_GENERADOR, "") ?: ""

    fun guardarEnlaceGenerador(c: Context, valor: String) {
        prefs(c).edit().putString(K_ENLACE_GENERADOR, valor.trim()).apply()
    }

    /** Momento (ms) de la última importación; 0 si todavía no hay lista. */
    fun listaFecha(c: Context): Long = prefs(c).getLong(K_LISTA_FECHA, 0L)

    fun listaTotal(c: Context): Int = prefs(c).getInt(K_LISTA_TOTAL, 0)

    fun guardarLista(c: Context, fecha: Long, total: Int) {
        prefs(c).edit().putLong(K_LISTA_FECHA, fecha).putInt(K_LISTA_TOTAL, total).apply()
    }
}
