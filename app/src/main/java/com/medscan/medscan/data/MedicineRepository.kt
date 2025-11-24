package com.medscan.medscan.data

import android.content.Context
import com.medscan.medscan.db.AppDatabase
import com.medscan.medscan.db.entities.Drug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.min

/**
 * Par (nombre de fármaco, dosis) que se devuelve al resolver coincidencias.
 */
data class FoundPair(val drug: String, val dose: String?)

/**
 * Repositorio de medicamentos:
 * - Acceso a Room (DAO) para búsquedas normalizadas (LIKE).
 * - Heurísticas de OCR: normalización, fix numérico y extracción de dosis.
 * - Fuzzy matching liviano (Levenshtein) como respaldo cuando no hay LIKE.
 */

class MedicineRepository(context: Context) {

    // ----------------------------------------------------------------------
    // Configuración / DAO
    // ----------------------------------------------------------------------

    /**
     * Palabras frecuentes en cajas/packaging que pueden confundir el fuzzy.
     * No deben disparar coincidencias de marca/fármaco.
     */
    private val PACKAGING_STOPWORDS = setOf(
        "ARGENTINA", "INDUSTRIA", "COMPRIMIDOS", "COMPRIMIDO",
        "TABLETAS", "TABLETA", "CAPSULAS", "CAPSULA",
        "VENTA", "RECETA", "LOTE", "VENCIMIENTO", "VTO",
        "LABORATORIO", "LAB", "CONTENIDO", "NETO", "MG", "G", "MCG", "µG"
    )

    /** DAO de acceso a la tabla `drugs`. */
    private val dao = AppDatabase.get(context).medicineDao()

    // ----------------------------------------------------------------------
    // Normalización / Fix de OCR (NO altera letras↔números en nombres)
    // ----------------------------------------------------------------------

    /**
     * Normaliza manteniendo letras y dígitos; elimina diacríticos y símbolos.
     * No convierte letras a números en nombres (evita falsos positivos).
     */
    private fun normalizeLettersOnly(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")             // quita acentos
            .replace("[^\\p{L}\\p{Nd}\\s]".toRegex(), " ")  // símbolos → espacio
            .replace("\\s+".toRegex(), " ")                 // colapsa espacios
            .trim()
            .uppercase()

    /**
     * Fix numérico selectivo para detección de dosis:
     * - Aplica reemplazos típicos de OCR (O→0, l/I→1, B→8, S→5) **solo** en tokens
     *   con mayoría de dígitos, dejando intactas las palabras (nombres).
     */
    private fun fixDigitsInNumbers(text: String): String {
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

    // ----------------------------------------------------------------------
    // Extracción de dosis (regex robusto)
    // ----------------------------------------------------------------------

    /** Regex de dosis: número (con opcional decimal) + unidad (mg, g, mcg, µg). */
    private val DOSE_REGEX = Regex("""\b(\d+(?:[.,]\d+)?)\s*(mg|g|mcg|µg)\b""", RegexOption.IGNORE_CASE)

    /**
     * Extrae todas las dosis presentes en una línea de texto.
     * Aplica el fix numérico SOLO aquí, no en los nombres.
     */
    private fun extractDoses(line: String): List<String> {
        val safe = fixDigitsInNumbers(line)
        return DOSE_REGEX.findAll(safe).map { it.value.trim() }.toList()
    }

    /** Exposición pública del extractor de dosis (misma implementación). */
    fun extractDosesPublic(line: String): List<String> = extractDoses(line)

    // ----------------------------------------------------------------------
    // Fuzzy matching liviano (Levenshtein)
    // ----------------------------------------------------------------------

    /**
     * Distancia de edición Levenshtein O(m·n) con memoria O(n).
     */
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

    /**
     * Dada una cadena normalizada del OCR, encuentra el `Drug` más cercano:
     * - Primero descarta stopwords y tokens cortos.
     * - Coincidencia exacta por substring corta camino (ya cubierta por LIKE).
     * - Umbral dinámico por longitud y verificación de "borde" (primera/última letra).
     * - Requiere similitud relativa ≥ 0.80.
     */
    private fun fuzzyBest(normText: String, drugs: List<Drug>): Drug? {
        var best: Pair<Drug, Int>? = null

        val words = normText
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 4 && it !in PACKAGING_STOPWORDS }

        for (d in drugs) {
            val dn = d.normalized

            // Atajo: si el texto contiene el nombre normalizado, retornar.
            if (normText.contains(dn)) return d

            // Umbral por longitud (más estricto para nombres largos).
            val allowed = when {
                dn.length >= 12 -> 2
                dn.length >= 9  -> 1
                dn.length >= 6  -> 1
                else            -> 1
            }

            for (w in words) {
                // Evitar comparar longitudes muy dispares.
                val lenDiff = kotlin.math.abs(w.length - dn.length)
                if (lenDiff > 2) continue

                // Exigir coincidencia de borde para bajar falsos positivos.
                val sameEdge = (w.firstOrNull() == dn.firstOrNull()) || (w.lastOrNull() == dn.lastOrNull())
                if (!sameEdge) continue

                val dist = levenshtein(w, dn)

                // Similitud relativa mínima.
                val maxLen = maxOf(w.length, dn.length).toFloat()
                val rel = 1f - (dist / maxLen)  // 1.0 => idéntico
                if (dist <= allowed && rel >= 0.80f) {
                    if (best == null || dist < best!!.second) best = d to dist
                }
            }
        }
        return best?.first
    }

    // ----------------------------------------------------------------------
    // API pública
    // ----------------------------------------------------------------------

    /**
     * Devuelve el **mejor nombre** encontrado en un texto:
     *  A) `LIKE` (SQL) contra nombres normalizados.
     *  B) Si no hay match, fuzzy Levenshtein contra todo el catálogo.
     */
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

    /**
     * (Compat) Busca primero en el bloque completo, si no, por líneas.
     */
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

    /**
     * (Privado) Lógica de búsqueda por texto:
     *  - LIKE (si hay, adjunta primera dosis si existe en el texto).
     *  - Fuzzy (si no hay LIKE).
     */
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
