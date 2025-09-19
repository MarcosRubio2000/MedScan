package com.medscan.medscan

import android.content.Context
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer

class MedicineDatabaseHelper(context: Context) {

    private var medicineList: List<String>

    init {
        // Cargar medicamentos desde el JSON en assets
        val inputStream = context.assets.open("medicamentos.json")
        val bufferedReader = BufferedReader(InputStreamReader(inputStream))
        val jsonString = bufferedReader.use { it.readText() }

        val jsonArray = JSONArray(jsonString)
        val tempList = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            tempList.add(jsonArray.getString(i))
        }
        medicineList = tempList.distinct() // evita duplicados
    }

    /**
     * Normaliza un texto: minúsculas, sin acentos ni caracteres especiales.
     */
    private fun normalizeText(text: String): String {
        val temp = Normalizer.normalize(text, Normalizer.Form.NFD)
        return temp
            .replace("\\p{Mn}+".toRegex(), "")   // quita acentos
            .lowercase()
            .replace("[^a-z0-9 ]".toRegex(), "") // elimina símbolos y signos
            .trim()
    }

    fun findClosestMedicine(detectedText: String): String? {
        val normalizedDetected = normalizeText(detectedText)

        var bestMatch: String? = null
        var bestScore = 0.0

        for (medicine in medicineList) {
            val normalizedMedicine = normalizeText(medicine)

            // Match directo
            if (normalizedDetected.contains(normalizedMedicine)) {
                return medicine
            }

            // Comparar similitud
            val score = similarity(normalizedDetected, normalizedMedicine)
            if (score > bestScore) {
                bestScore = score
                bestMatch = medicine
            }
        }

        return if (bestScore > 0.65) bestMatch else null // subí el umbral un poquito
    }

    private fun similarity(s1: String, s2: String): Double {
        val longer = if (s1.length > s2.length) s1 else s2
        val shorter = if (s1.length > s2.length) s2 else s1
        val longerLength = longer.length
        if (longerLength == 0) return 1.0
        return (longerLength - editDistance(longer, shorter)) / longerLength.toDouble()
    }

    private fun editDistance(s1: String, s2: String): Int {
        val costs = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var lastValue = i - 1
            costs[0] = i
            for (j in 1..s2.length) {
                val newValue = if (s1[i - 1] == s2[j - 1]) lastValue
                else 1 + minOf(minOf(costs[j], costs[j - 1]), lastValue)
                lastValue = costs[j]
                costs[j] = newValue
            }
        }
        return costs[s2.length]
    }
}
