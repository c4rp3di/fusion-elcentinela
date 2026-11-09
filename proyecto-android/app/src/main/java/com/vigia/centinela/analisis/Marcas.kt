package com.vigia.centinela.analisis

/**
 * Marcas que los estafadores suelen imitar. Un dominio es legítimo si su nombre principal es exactamente la marca
 * (paypal.com, amazon.es, bbva.com.mx…) o está en la lista de extras. Cualquier otro dominio que CONTENGA la marca
 * (paypal-seguro.xyz, amazon.com.pedido.top, paypa1.com…) se considera una imitación.
 */
object Marcas {

    class Marca(
        val clave: String,
        val extras: Set<String> = emptySet(),
        /** Para palabras cortas o comunes (apple, dhl…): solo cuenta si aparece como palabra suelta. */
        val soloPalabra: Boolean = false
    ) {
        fun esLegitimo(registrable: String): Boolean =
            registrable.substringBefore('.') == clave || registrable in extras
    }

    val LISTA: List<Marca> = listOf(
        Marca("paypal", setOf("paypalobjects.com")),
        Marca("amazon", setOf("amazonaws.com", "amazon-adsystem.com", "media-amazon.com", "ssl-images-amazon.com", "amazonpay.com")),
        Marca("netflix", setOf("nflxvideo.net", "nflximg.net", "netflix.net")),
        Marca("apple", soloPalabra = true),
        Marca("appleid"),
        Marca("icloud"),
        Marca("microsoft", setOf("microsoftonline.com", "microsoft365.com")),
        Marca("google", setOf("googleapis.com", "googleusercontent.com", "googlevideo.com", "googlesyndication.com", "googletagmanager.com", "google-analytics.com", "googleadservices.com")),
        Marca("facebook", setOf("facebook.net")),
        Marca("instagram", setOf("cdninstagram.com")),
        Marca("whatsapp"),
        Marca("linkedin"),
        Marca("spotify"),
        Marca("binance"),
        Marca("coinbase"),
        // Banca
        Marca("bbva"),
        Marca("santander", setOf("bancosantander.es")),
        Marca("caixabank"),
        Marca("sabadell", setOf("bancsabadell.com", "bancosabadell.com")),
        Marca("bankinter"),
        Marca("openbank"),
        Marca("unicaja", setOf("unicajabanco.es")),
        Marca("ibercaja"),
        Marca("abanca"),
        Marca("kutxabank"),
        Marca(
            "banco",
            setOf(
                "bancosantander.es", "bancolombia.com", "bancoestado.cl", "bancochile.cl", "bancodechile.cl",
                "bancoppel.com", "bancogalicia.com.ar", "bancopichincha.com", "bancobrasil.com.br",
                "bancodebogota.com", "bancodeoccidente.com.co", "bancopopular.es", "bancofalabella.cl",
                "bancoazteca.com.mx", "bancodeespana.es", "bancomext.com", "bancoguayaquil.com",
                "bancointernacional.com.ec", "bancoprovincia.com.ar", "bancocredicoop.coop"
            )
        ),
        // Envíos y administración
        Marca("correos", setOf("correosexpress.com")),
        Marca("dhl", soloPalabra = true),
        Marca("seur", soloPalabra = true),
        Marca("agenciatributaria")
    )
}
