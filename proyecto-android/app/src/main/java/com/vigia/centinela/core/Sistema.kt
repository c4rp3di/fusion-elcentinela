package com.vigia.centinela.core

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Atajos a pantallas de ajustes de Android; si el fabricante no las tiene, devuelven false y no pasa nada. */
object Sistema {
    fun abrir(a: Activity, accion: String): Boolean {
        return try {
            a.startActivity(Intent(accion))
            true
        } catch (e: Exception) {
            false
        }
    }

    fun abrirAjustesApp(a: Activity, paquete: String): Boolean {
        return try {
            a.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + paquete)))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Pantalla «Mostrar sobre otras apps» de esta app (permiso SYSTEM_ALERT_WINDOW). */
    fun abrirPermisoVentanas(a: Activity): Boolean {
        return try {
            a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.packageName)))
            true
        } catch (e: Exception) {
            false
        }
    }
}
