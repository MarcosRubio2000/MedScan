package com.medscan.medscan.data

import android.content.Context
import com.medscan.medscan.db.AppDatabase
import com.medscan.medscan.db.entities.Drug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.min

data class FoundPair(val drug: String, val dose: String?)

class MedicineRepository(context: Context) {
    private val dao = AppDatabase.get(context).medicineDao()

    /* =======================
       Normalización / OCR fix
       ======================= */

    // ⚠️ NUEVO: NO tocar letras → números. Solo normalizamos diacríticos, símbolos y espacios.
    private fun normalizeLettersOnly(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")             // quita acentos
            .replace("[^\\p{L}\\p{Nd}\\s]".toRegex(), " ")  // símbolos → espacio
            .replace("\\s+".toRegex(), " ")                 // colapsa espacios
            .trim()
            .uppercase()

    // (Opcional) Fixups SOLO en números, para leer dosis (p.ej. 0↔O en tokens numéricos)
    private fun fixDigitsInNumbers(text: String): String {
        // Reemplazos seguros solo dentro de grupos numéricos; dejamos letras intactas.
        // Estrategia simple: donde hay secuencias con mayoría de dígitos, corregimos O→0, l/I→1, B→8, S→5.
        val tokenRegex = Regex("""\S+""")
        return tokenRegex.replace(text) { m ->
            val t = m.value
            val digits = t.count { it.isDigit() }
            val letters = t.count { it.isLetter() }
            if (digits >= letters && t.length >= 2) {
                t.map { c ->
                    when (c) {
                        'O','o' -> '0'
                        'l','I' -> '1'
                        'B'     -> '8'
                        'S'     -> '5'
                        else    -> c
                    }
                }.joinToString("")
            } else t
        }
    }

    /* =======================
       Dosis (regex robusto)
       ======================= */

    private val DOSE_REGEX = Regex("""\b(\d+(?:[.,]\d+)?)\s*(mg|g|mcg|µg)\b""", RegexOption.IGNORE_CASE)

    private fun extractDoses(line: String): List<String> {
        // Aplicar fix de dígitos SOLO aquí (no en nombres de drogas)
        val safe = fixDigitsInNumbers(line)
        return DOSE_REGEX.findAll(safe).map { it.value.trim() }.toList()
    }

    fun extractDosesPublic(line: String): List<String> = extractDoses(line)

    /* =======================
       Fuzzy matching liviano
       ======================= */

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length; val n = b.length
        if (m == 0) return n; if (n == 0) return m
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..n) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = min(min(dp[j] + 1, dp[j - 1] + 1), prev + cost)
                prev = temp
            }
        }
        return dp[n]
    }

    private fun fuzzyBest(normText: String, drugs: List<Drug>): Drug? {
        var best: Pair<Drug, Int>? = null
        val words = normText.split(' ').filter { it.length >= 4 }
        for (d in drugs) {
            val dn = d.normalized
            if (normText.contains(dn)) return d
            val allowed = when {
                dn.length >= 12 -> 3
                dn.length >= 9  -> 2
                dn.length >= 6  -> 1
                else            -> 1
            }
            for (w in words) {
                val dist = levenshtein(w, dn)
                if (dist <= allowed) {
                    if (best == null || dist < best!!.second) best = d to dist
                }
            }
        }
        return best?.first
    }

    /* =======================
       API pública
       ======================= */

    suspend fun findBestDrugName(text: String): String? = withContext(Dispatchers.IO) {
        val norm = normalizeLettersOnly(text)

        // A) LIKE exacto/normalizado
        val like = dao.findDrugsLike(norm)
        if (like.isNotEmpty()) {
            return@withContext like.maxByOrNull { it.normalized.length }?.name
        }

        // B) Fuzzy como respaldo
        val fuzzy = fuzzyBest(norm, dao.getAllDrugs())
        return@withContext fuzzy?.name
    }

    // (Opcional) APIs previas
    suspend fun findInBlockOrLines(blockText: String, lines: List<String>): List<FoundPair> =
        withContext(Dispatchers.IO) {
            val primary = findInText(blockText)
            if (primary.isNotEmpty()) return@withContext primary
            for (line in lines) {
                val res = findInText(line)
                if (res.isNotEmpty()) return@withContext res
            }
            emptyList()
        }

    private suspend fun findInText(text: String): List<FoundPair> {
        val norm = normalizeLettersOnly(text)

        val like = dao.findDrugsLike(norm)
        if (like.isNotEmpty()) {
            val doses = extractDoses(text)
            return if (doses.isNotEmpty())
                like.map { FoundPair(it.name, doses.first()) }
            else like.map { FoundPair(it.name, null) }
        }

        val fuzzy = fuzzyBest(norm, dao.getAllDrugs())
        if (fuzzy != null) {
            val doses = extractDoses(text)
            return if (doses.isNotEmpty())
                listOf(FoundPair(fuzzy.name, doses.first()))
            else listOf(FoundPair(fuzzy.name, null))
        }

        return emptyList()
    }
}
