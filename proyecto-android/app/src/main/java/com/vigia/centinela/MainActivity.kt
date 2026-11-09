package com.vigia.centinela

import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.vigia.centinela.core.ActividadBase
import com.vigia.centinela.core.Ayuda
import com.vigia.centinela.core.Formato
import com.vigia.centinela.core.Portapapeles
import com.vigia.centinela.core.Sistema
import com.vigia.centinela.core.Tema
import com.vigia.centinela.core.Ui
import com.vigia.centinela.datos.Ajustes
import com.vigia.centinela.datos.ArchivoGrandeException
import com.vigia.centinela.datos.GestorListas
import com.vigia.centinela.datos.ValidadorListas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class MainActivity : ActividadBase() {

    private lateinit var estadoServicio: TextView
    private lateinit var ayudaServicio: TextView
    private lateinit var btnActivarServicio: MaterialButton
    private lateinit var estadoVentanas: TextView
    private lateinit var btnPermisoVentanas: MaterialButton
    private lateinit var estadoLista: TextView
    private lateinit var avisoLista: TextView
    private lateinit var entradaEnlace: TextInputLayout
    private lateinit var campoEnlace: TextInputEditText

    /** Copia temporal del archivo que se está importando; se borra siempre al terminar, cancelar o cerrar la pantalla. */
    private var archivoPendiente: File? = null
    private var progreso: AlertDialog? = null

    private val selector = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) alElegirArchivo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        estadoServicio = findViewById(R.id.estadoServicio)
        ayudaServicio = findViewById(R.id.ayudaServicio)
        btnActivarServicio = findViewById(R.id.btnActivarServicio)
        estadoVentanas = findViewById(R.id.estadoVentanas)
        btnPermisoVentanas = findViewById(R.id.btnPermisoVentanas)
        estadoLista = findViewById(R.id.estadoLista)
        avisoLista = findViewById(R.id.avisoLista)
        entradaEnlace = findViewById(R.id.entradaEnlace)
        campoEnlace = findViewById(R.id.campoEnlace)

        findViewById<LinearLayout>(R.id.filaCabecera).addView(
            Ayuda.enlace(this, getString(R.string.ayuda_enlace)) {
                Ayuda.mostrar(this, R.string.ayuda_principal_titulo, R.string.ayuda_principal)
            }
        )

        // Dirección del Generador de Listas: se guarda en SharedPreferences a cada cambio.
        campoEnlace.setText(Ajustes.enlaceGenerador(this))
        revisarFormatoEnlace(campoEnlace.text?.toString() ?: "")
        campoEnlace.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val valor = s?.toString() ?: ""
                Ajustes.guardarEnlaceGenerador(this@MainActivity, valor)
                revisarFormatoEnlace(valor)
            }
        })

        btnActivarServicio.setOnClickListener {
            if (!Sistema.abrir(this, Settings.ACTION_ACCESSIBILITY_SETTINGS)) aviso(R.string.no_se_pudo_abrir)
        }
        btnPermisoVentanas.setOnClickListener {
            if (!Sistema.abrirPermisoVentanas(this)) aviso(R.string.no_se_pudo_abrir)
        }
        findViewById<MaterialButton>(R.id.btnCopiarEnlace).setOnClickListener { copiarEnlaceDescarga() }
        findViewById<MaterialButton>(R.id.btnImportar).setOnClickListener {
            selector.launch(arrayOf("text/*", "application/octet-stream"))
        }

        GestorListas.limpiarTemporales(this)
    }

    override fun onResume() {
        super.onResume()
        pintarProteccion()
        pintarLista()
    }

    override fun onDestroy() {
        ocultarProgreso()
        descartarTemporal()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Estado en pantalla

    private fun pintarProteccion() {
        val activo = CentinelaEstado.habilitado(this)
        estadoServicio.setText(if (activo) R.string.servicio_activo else R.string.servicio_inactivo)
        estadoServicio.setTextColor(if (activo) Tema.verde(this) else Tema.rojo(this))
        ayudaServicio.visibility = if (activo) View.GONE else View.VISIBLE
        btnActivarServicio.setText(if (activo) R.string.btn_ajustes_accesibilidad else R.string.btn_activar_accesibilidad)

        val ventanas = Settings.canDrawOverlays(this)
        estadoVentanas.setText(if (ventanas) R.string.overlay_ok else R.string.overlay_no)
        estadoVentanas.setTextColor(if (ventanas) Tema.verde(this) else Tema.ambar(this))
        btnPermisoVentanas.visibility = if (ventanas) View.GONE else View.VISIBLE
    }

    private fun pintarLista() {
        val fecha = Ajustes.listaFecha(this)
        if (fecha == 0L) {
            estadoLista.setText(R.string.lista_vacia)
            avisoLista.visibility = View.GONE
            return
        }
        estadoLista.text = getString(R.string.lista_estado, Formato.fechaHora(fecha), Formato.numero(Ajustes.listaTotal(this)))

        val edadMs = System.currentTimeMillis() - fecha
        if (edadMs > LIMITE_ANTIGUEDAD_MS) {
            val dias = (edadMs / DIA_MS).toInt()
            avisoLista.text = getString(R.string.lista_antigua, dias)
            avisoLista.setTextColor(Tema.rojo(this))
            avisoLista.visibility = View.VISIBLE
        } else {
            avisoLista.visibility = View.GONE
        }
    }

    private fun revisarFormatoEnlace(valor: String) {
        val v = valor.trim()
        val correcto = v.isEmpty() || v.startsWith("http://", ignoreCase = true) || v.startsWith("https://", ignoreCase = true)
        entradaEnlace.error = if (correcto) null else getString(R.string.enlace_formato)
    }

    // ---------------------------------------------------------------- Enlace de descarga

    private fun copiarEnlaceDescarga() {
        val enlace = Ajustes.enlaceGenerador(this)
        if (enlace.isBlank()) {
            aviso(R.string.enlace_vacio)
            return
        }
        Portapapeles.copiar(this, enlace)
        aviso(R.string.copiado)
    }

    // ---------------------------------------------------------------- Importación

    private fun alElegirArchivo(uri: Uri) {
        lifecycleScope.launch {
            val meta = GestorListas.metadatos(this@MainActivity, uri)

            val nombre = meta.nombre
            if (nombre != null && !nombre.lowercase(Locale.ROOT).endsWith(".txt")) {
                aviso(R.string.import_no_txt)
                return@launch
            }
            val bytes = meta.bytes
            if (bytes != null && bytes > ValidadorListas.TAM_MAX_BYTES) {
                aviso(getString(R.string.import_muy_grande, Formato.bytes(bytes)))
                return@launch
            }

            mostrarProgreso(R.string.import_leyendo)
            try {
                val copia = GestorListas.copiarATemporal(this@MainActivity, uri)
                archivoPendiente = copia
                val informe = withContext(Dispatchers.IO) { ValidadorListas.analizar(copia) }
                ocultarProgreso()
                confirmar(copia, informe)
            } catch (e: ArchivoGrandeException) {
                ocultarProgreso()
                descartarTemporal()
                aviso(getString(R.string.import_muy_grande, Formato.bytes(e.bytes)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ocultarProgreso()
                descartarTemporal()
                aviso(R.string.import_error_leer)
            }
        }
    }

    /** Muestra el resumen del validador y deja decidir al usuario. Sin su confirmación no se guarda nada. */
    private fun confirmar(archivo: File, informe: ValidadorListas.Informe) {
        if (informe.validas == 0) {
            AlertDialog.Builder(this)
                .setTitle(R.string.import_titulo)
                .setMessage(R.string.import_sin_validos)
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ -> descartarTemporal() }
                .show()
            return
        }

        val incompleto = informe.validas < ValidadorListas.MIN_VALIDOS
        val principal = if (incompleto) {
            getString(R.string.import_incompleto, Formato.numero(informe.validas))
        } else {
            getString(
                R.string.import_resumen,
                Formato.numero(informe.lineas),
                Formato.numero(informe.validas),
                Formato.numero(informe.ignoradas)
            )
        }
        val mensaje = if (informe.corruptas > 0) {
            principal + "\n\n" + getString(R.string.import_corruptas, Formato.numero(informe.corruptas))
        } else {
            principal
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.import_titulo)
            .setMessage(mensaje)
            .setCancelable(false)
            .setPositiveButton(if (incompleto) R.string.import_btn_igualmente else R.string.import_btn_importar) { _, _ ->
                ejecutarImportacion(archivo)
            }
            .setNegativeButton(R.string.import_btn_cancelar) { _, _ -> descartarTemporal() }
            .show()
    }

    private fun ejecutarImportacion(archivo: File) {
        mostrarProgreso(R.string.import_guardando)
        lifecycleScope.launch {
            try {
                val total = GestorListas.importar(this@MainActivity, archivo)
                ocultarProgreso()
                aviso(getString(R.string.import_ok, Formato.numero(total)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ocultarProgreso()
                aviso(R.string.import_fallo)
            } finally {
                descartarTemporal()
                pintarLista()
            }
        }
    }

    private fun descartarTemporal() {
        archivoPendiente?.delete()
        archivoPendiente = null
    }

    private fun mostrarProgreso(texto: Int) {
        ocultarProgreso()
        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.gravity = Gravity.CENTER_VERTICAL
        val m = Ui.dp(this, 24)
        fila.setPadding(m, m, m, m)
        fila.addView(ProgressBar(this))
        val t = Ui.texto(this, getString(texto), 14f)
        t.setPadding(Ui.dp(this, 16), 0, 0, 0)
        fila.addView(t)
        val dialogo = AlertDialog.Builder(this).setView(fila).setCancelable(false).create()
        dialogo.show()
        progreso = dialogo
    }

    private fun ocultarProgreso() {
        progreso?.dismiss()
        progreso = null
    }

    private companion object {
        const val DIA_MS = 24L * 60L * 60L * 1000L
        const val LIMITE_ANTIGUEDAD_MS = 14L * DIA_MS
    }
}
