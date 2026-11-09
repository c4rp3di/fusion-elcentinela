package com.vigia.centinela.alerta

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.vigia.centinela.R
import com.vigia.centinela.analisis.AnalizadorEnlaces.Nivel
import com.vigia.centinela.analisis.AnalizadorEnlaces.Resultado
import com.vigia.centinela.core.Portapapeles
import com.vigia.centinela.core.Tema
import com.vigia.centinela.core.Ui

/**
 * Ventana flotante de alerta (tema oscuro, 🚨, dominio y dos botones). Todos los métodos se llaman desde el hilo principal.
 *
 * Usa SYSTEM_ALERT_WINDOW si el usuario la ha concedido; si no, cae en la ventana propia de los servicios de accesibilidad,
 * que no necesita ese permiso, para que la alerta se vea igualmente.
 */
class AlertaFlotante(private val servicio: AccessibilityService) {

    private val gestor = servicio.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val principal = Handler(Looper.getMainLooper())
    private val temporizador = Runnable { ocultar() }

    private var vista: View? = null
    private var nivelActual: Nivel = Nivel.SEGURO

    fun mostrar(resultado: Resultado, alDescartar: (Resultado) -> Unit) {
        if (vista != null) {
            if (nivelActual.ordinal >= resultado.nivel.ordinal) return // Ya hay un aviso igual o más grave en pantalla.
            ocultar()
        }
        val nueva = construir(resultado, alDescartar)
        try {
            gestor.addView(nueva, parametros())
        } catch (e: Exception) {
            return // Android no ha permitido la ventana: mejor no hacer nada que fallar.
        }
        vista = nueva
        nivelActual = resultado.nivel
        vibrar(resultado.nivel == Nivel.CRITICO)
        principal.postDelayed(temporizador, DURACION_MS)
    }

    fun ocultar() {
        principal.removeCallbacks(temporizador)
        val actual = vista ?: return
        vista = null
        nivelActual = Nivel.SEGURO
        try {
            gestor.removeView(actual)
        } catch (e: Exception) {
            // Ya no estaba.
        }
    }

    private fun construir(r: Resultado, alDescartar: (Resultado) -> Unit): View {
        val critico = r.nivel == Nivel.CRITICO
        val acento = if (critico) Tema.rojo(servicio) else Tema.ambar(servicio)
        val acentoClaro = if (critico) 0xFFFF8A80.toInt() else 0xFFFFD54F.toInt()

        val tarjeta = LinearLayout(servicio)
        tarjeta.orientation = LinearLayout.VERTICAL
        tarjeta.setPadding(dp(16), dp(14), dp(16), dp(14))
        val fondo = GradientDrawable()
        fondo.setColor(FONDO)
        fondo.cornerRadius = dp(16).toFloat()
        fondo.setStroke(dp(2), acento)
        tarjeta.background = fondo
        tarjeta.elevation = dp(8).toFloat()
        tarjeta.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE

        // Cabecera: 🚨 + título
        val cabecera = LinearLayout(servicio)
        cabecera.orientation = LinearLayout.HORIZONTAL
        cabecera.gravity = Gravity.CENTER_VERTICAL
        val icono = texto("🚨", 30f, Color.WHITE)
        cabecera.addView(icono)
        val titulos = LinearLayout(servicio)
        titulos.orientation = LinearLayout.VERTICAL
        titulos.setPadding(dp(10), 0, 0, 0)
        titulos.addView(texto(servicio.getString(if (critico) R.string.alerta_titulo_critico else R.string.alerta_titulo_sospechoso), 17f, Color.WHITE, true))
        titulos.addView(texto(servicio.getString(R.string.alerta_subtitulo), 12f, GRIS_CLARO))
        cabecera.addView(titulos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tarjeta.addView(cabecera)

        // Dominio sospechoso
        val dominio = texto(r.dominio, 16f, acentoClaro, true)
        dominio.typeface = Typeface.MONOSPACE
        dominio.maxLines = 2
        dominio.ellipsize = TextUtils.TruncateAt.MIDDLE
        dominio.setPadding(0, dp(10), 0, dp(4))
        tarjeta.addView(dominio)

        // Motivos (como mucho tres)
        val motivos = r.motivos.take(3).joinToString("\n") { "• " + servicio.getString(it.recurso, it.argumento) }
        if (motivos.isNotEmpty()) tarjeta.addView(texto(motivos, 13f, GRIS_CLARO))

        // Botones
        val fila = LinearLayout(servicio)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.setPadding(0, dp(14), 0, 0)
        val descartar = boton(servicio.getString(R.string.alerta_descartar), null) {
            alDescartar(r)
            ocultar()
        }
        val copiar = boton(servicio.getString(R.string.alerta_copiar), acento) {
            Portapapeles.copiar(servicio, r.enlace)
            Toast.makeText(servicio, R.string.enlace_copiado, Toast.LENGTH_SHORT).show()
            alDescartar(r)
            ocultar()
        }
        val separacion = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        separacion.rightMargin = dp(8)
        fila.addView(descartar, separacion)
        fila.addView(copiar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tarjeta.addView(fila)

        return tarjeta
    }

    private fun texto(valor: CharSequence, tam: Float, color: Int, negrita: Boolean = false): TextView {
        val t = TextView(servicio)
        t.text = valor
        t.textSize = tam
        t.setTextColor(color)
        if (negrita) t.setTypeface(t.typeface, Typeface.BOLD)
        return t
    }

    /** Botón con aspecto propio (relleno de color, o solo borde si relleno es null) para no depender del tema del servicio. */
    private fun boton(etiqueta: String, relleno: Int?, alPulsar: () -> Unit): Button {
        val b = Button(servicio)
        b.text = etiqueta
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        val fondo = GradientDrawable()
        fondo.cornerRadius = dp(10).toFloat()
        if (relleno != null) {
            fondo.setColor(relleno)
        } else {
            fondo.setColor(Color.TRANSPARENT)
            fondo.setStroke(dp(1), GRIS_CLARO)
        }
        b.background = fondo
        b.setOnClickListener { alPulsar() }
        return b
    }

    private fun parametros(): WindowManager.LayoutParams {
        val ancho = minOf(servicio.resources.displayMetrics.widthPixels - dp(24), dp(520))
        val lp = WindowManager.LayoutParams(
            ancho,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            tipoVentana(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = dp(48)
        return lp
    }

    @Suppress("DEPRECATION")
    private fun tipoVentana(): Int {
        val puedeDibujar = Settings.canDrawOverlays(servicio)
        return when {
            puedeDibujar && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            puedeDibujar -> WindowManager.LayoutParams.TYPE_PHONE
            else -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrar(critico: Boolean) {
        val patron = if (critico) longArrayOf(0, 300, 120, 300, 120, 500) else longArrayOf(0, 250, 120, 250)
        try {
            val vibrador: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (servicio.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                servicio.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrador.vibrate(VibrationEffect.createWaveform(patron, -1))
            } else {
                vibrador.vibrate(patron, -1)
            }
        } catch (e: Exception) {
            // Sin vibrador o sin permiso: la alerta se ve igual.
        }
    }

    private fun dp(v: Int): Int = Ui.dp(servicio, v)

    private companion object {
        val FONDO: Int = 0xFF1E1E24.toInt()
        val GRIS_CLARO: Int = 0xFFB8B8C2.toInt()
        const val DURACION_MS = 30_000L
    }
}
