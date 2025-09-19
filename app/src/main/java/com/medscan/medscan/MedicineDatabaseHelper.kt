package com.medscan.medscan

import android.content.Context
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer

class MedicineDatabaseHelper(context: Context) {

    private var medicineList: List<String>
    private var doseList: List<String>

    init {
        // Cargar medicamentos
        val medInput = context.assets.open("medicamentos.json")
        val medReader = BufferedReader(InputStreamReader(medInput))
        val medJson = medReader.use { it.readText() }
        val medArray = JSONArray(medJson)
        val tempMed = mutableListOf<String>()
        for (i in 0 until medArray.length()) {
            tempMed.add(medArray.getString(i))
        }
        medicineList = tempMed.distinct()

        // Cargar dosis
        val doseInput = context.assets.open("dosis.json")
        val doseReader = BufferedReader(InputStreamReader(doseInput))
        val doseJson = doseReader.use { it.readText() }
        val doseArray = JSONArray(doseJson)
        val tempDose = mutableListOf<String>()
        for (i in 0 until doseArray.length()) {
            tempDose.add(doseArray.getString(i))
        }
        doseList = tempDose.distinct()
    }

    private fun normalizeText(text: String): String {
        val temp = Normalizer.normalize(text, Normalizer.Form.NFD)
        return temp
            .replace("\\p{Mn}+".toRegex(), "")   // quita acentos
            .lowercase()
            .replace("[^a-z0-9 ]".toRegex(), "") // elimina símbolos
            .trim()
    }

    /**
     * Busca coincidencia de medicamento + dosis en una línea.
     */
    fun findMedicineWithDose(lineText: String): String? {
        val normalizedLine = normalizeText(lineText)

        var foundMedicine: String? = null
        var foundDose: String? = null

        // Buscar medicamento en la línea
        for (medicine in medicineList) {
            val normalizedMedicine = normalizeText(medicine)
            if (normalizedLine.contains(normalizedMedicine)) {
                foundMedicine = medicine
                break
            }
        }

        // Buscar dosis en la línea
        for (dose in doseList) {
            val normalizedDose = normalizeText(dose)
            if (normalizedLine.contains(normalizedDose)) {
                foundDose = dose
                break
            }
        }

        // Si encontramos ambos en la misma línea → asociamos
        if (foundMedicine != null) {
            return if (foundDose != null) {
                "$foundMedicine $foundDose"
            } else {
                foundMedicine
            }
        }

        return null
    }
}
