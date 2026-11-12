package it.eremite.autosalute.health

/** Spiegazione in italiano dei codici errore più comuni (motore diesel K9K). */
object Dtc {
    private val map = mapOf(
        "P0087" to "pressione gasolio troppo bassa (filtro gasolio, pompa)",
        "P0088" to "pressione gasolio troppo alta",
        "P0089" to "regolatore pressione gasolio",
        "P0093" to "perdita nel circuito gasolio",
        "P0100" to "debimetro (sensore aria)",
        "P0101" to "debimetro: valori non plausibili",
        "P0102" to "debimetro: segnale basso",
        "P0103" to "debimetro: segnale alto",
        "P0105" to "sensore pressione collettore",
        "P0110" to "sensore temperatura aria",
        "P0115" to "sensore temperatura motore",
        "P0116" to "sensore temperatura motore: valori non plausibili",
        "P0117" to "sensore temperatura motore: segnale basso",
        "P0118" to "sensore temperatura motore: segnale alto",
        "P0128" to "motore non arriva in temperatura (termostato)",
        "P0190" to "sensore pressione gasolio",
        "P0201" to "iniettore cilindro 1", "P0202" to "iniettore cilindro 2",
        "P0203" to "iniettore cilindro 3", "P0204" to "iniettore cilindro 4",
        "P0234" to "turbo: pressione troppo alta",
        "P0235" to "sensore pressione turbo",
        "P0299" to "turbo: pressione troppo bassa",
        "P0335" to "sensore giri motore",
        "P0340" to "sensore fase (albero a camme)",
        "P0380" to "circuito candelette di preriscaldo: una candeletta guasta o la centralina che le comanda (avviamento a freddo più faticoso)",
        "P0381" to "spia candelette",
        "P0400" to "valvola EGR",
        "P0401" to "EGR: flusso insufficiente",
        "P0402" to "EGR: flusso eccessivo",
        "P0403" to "EGR: circuito elettrico",
        "P0404" to "EGR: posizione non plausibile",
        "P0420" to "catalizzatore",
        "P0470" to "sensore pressione gas di scarico",
        "P0480" to "ventola di raffreddamento",
        "P0500" to "sensore velocità",
        "P0560" to "tensione batteria",
        "P0562" to "tensione batteria bassa",
        "P0563" to "tensione batteria alta",
        "P0606" to "centralina motore",
        "P0670" to "centralina candelette",
        "P2002" to "filtro antiparticolato (FAP): efficienza bassa",
        "P2452" to "sensore pressione differenziale FAP",
        "P2453" to "sensore pressione differenziale FAP: valori non plausibili",
        "P2458" to "rigenerazione FAP durata troppo",
        "P2463" to "filtro antiparticolato (FAP) intasato",
        "P242F" to "filtro antiparticolato: cenere accumulata",
        "P2563" to "sensore posizione turbo",
    )

    fun describe(code: String): String = map[code] ?: when (code.firstOrNull()) {
        'P' -> when (code.getOrNull(2)) {
            '0', '1' -> "aria/alimentazione"
            '2' -> "iniezione"
            '3' -> "accensione / mancate combustioni"
            '4' -> "controllo emissioni (EGR, FAP, catalizzatore)"
            '5' -> "velocità, minimo, ingressi"
            '6' -> "centralina e uscite"
            '7', '8', '9' -> "cambio"
            else -> "motore"
        }
        'C' -> "telaio (freni, ABS, sterzo)"
        'B' -> "carrozzeria (airbag, clima, luci)"
        'U' -> "comunicazione tra centraline"
        else -> "codice sconosciuto"
    }
}
