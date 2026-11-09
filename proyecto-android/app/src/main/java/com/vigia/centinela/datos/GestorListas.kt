package com.vigia.centinela.datos

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ArchivoGrandeException(val bytes: Long) : IOException("El archivo supera el tamaño máximo")

/** Importación de la lista de amenazas: copia temporal → validación → Room → borrado del temporal. */
object GestorListas {
    private const val CARPETA_TEMPORAL = "listas"
    private const val TAM_LOTE = 5000

    private val version = AtomicInteger(0)

    /** Cambia cada vez que se importa una lista; el servicio la usa para olvidar análisis antiguos. */
    val generacion: Int get() = version.get()

    class Metadatos(val nombre: String?, val bytes: Long?)

    suspend fun metadatos(c: Context, uri: Uri): Metadatos = withContext(Dispatchers.IO) {
        var nombre: String? = null
        var bytes: Long? = null
        try {
            val columnas = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            c.contentResolver.query(uri, columnas, null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && !cur.isNull(i)) nombre = cur.getString(i)
                    val j = cur.getColumnIndex(OpenableColumns.SIZE)
                    if (j >= 0 && !cur.isNull(j)) bytes = cur.getLong(j)
                }
            }
        } catch (e: Exception) {
            // Sin metadatos: se seguirá comprobando el tamaño real mientras se copia.
        }
        Metadatos(nombre, bytes)
    }

    /**
     * Copia el archivo elegido a la caché privada de la app. El límite de 20 MB se vuelve a comprobar mientras se
     * copia, porque el tamaño que declara el proveedor de archivos no siempre es fiable.
     */
    suspend fun copiarATemporal(c: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val carpeta = File(c.cacheDir, CARPETA_TEMPORAL)
        carpeta.mkdirs()
        val destino = File.createTempFile("lista_", ".txt", carpeta)
        try {
            val entrada = c.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
            entrada.use { ent ->
                destino.outputStream().use { sal ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ent.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > ValidadorListas.TAM_MAX_BYTES) throw ArchivoGrandeException(total)
                        sal.write(buffer, 0, n)
                    }
                }
            }
            destino
        } catch (e: Throwable) {
            destino.delete()
            throw e
        }
    }

    /**
     * Sustituye la lista guardada por la del archivo. Todo ocurre en una sola transacción: si algo falla, la lista
     * anterior queda intacta. Devuelve cuántos dominios distintos hay ahora cargados.
     */
    suspend fun importar(c: Context, archivo: File): Int = withContext(Dispatchers.IO) {
        val bd = BaseDatos.obtener(c)
        val dao = bd.dominios()
        bd.withTransaction {
            dao.borrarTodo()
            val lote = ArrayList<DominioEntity>(TAM_LOTE)
            archivo.bufferedReader(Charsets.UTF_8).use { lector ->
                while (true) {
                    val texto = lector.readLine() ?: break
                    val linea = ValidadorListas.clasificar(texto)
                    val dominio = linea.dominio
                    if (linea.tipo == ValidadorListas.Tipo.VALIDA && dominio != null) {
                        lote.add(DominioEntity(dominio))
                        if (lote.size >= TAM_LOTE) {
                            dao.insertar(lote)
                            lote.clear()
                        }
                    }
                }
            }
            if (lote.isNotEmpty()) dao.insertar(lote)
        }
        val total = dao.contar()
        Ajustes.guardarLista(c, System.currentTimeMillis(), total)
        version.incrementAndGet()
        total
    }

    /** Borra cualquier archivo temporal que se hubiera quedado (por ejemplo, si la app se cerró a mitad). */
    fun limpiarTemporales(c: Context) {
        File(c.cacheDir, CARPETA_TEMPORAL).listFiles()?.forEach { it.delete() }
    }
}
