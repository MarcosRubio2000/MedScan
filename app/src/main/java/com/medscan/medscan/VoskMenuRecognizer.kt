package com.medscan.medscan

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * VoskMenuRecognizer
 * ------------------
 * Wrapper liviano para reconocimiento de voz offline con Vosk.
 *
 * - Carga el modelo desde assets con [prepare] (asíncrono).
 * - Permite establecer una gramática JSON (palabras/frases esperadas) con [setGrammar],
 *   o pasar `null` para modo libre (dictado general).
 * - Inicia captura de micrófono y decodificación con [start], emitiendo callbacks de
 *   "escuchando", parciales y resultado final.
 * - Se detiene con [stop] y se libera con [destroy].
 *
 */
class VoskMenuRecognizer(
    private val ctx: Context,
    private val callbacks: Callbacks
) {

    // =========================================================
    // Callbacks externos
    // =========================================================
    interface Callbacks {
        fun onReady()                    // Modelo listo para usarse
        fun onListening()                // AudioRecord activo / escuchando
        fun onPartial(text: String)      // Parcial reconocido (si lo hay)
        fun onResult(text: String)       // Resultado final (puede ser cadena vacía)
        fun onError(msg: String)         // Errores operativos
    }

    // =========================================================
    // Estado interno
    // =========================================================
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private var ready = false

    private var currentGrammarJson: String? = null

    // Parámetros de audio / logging
    private val sampleRate = 16000
    private val TAG = "VOSK_FLEX"

    // =========================================================
    // Setup / Modelo
    // =========================================================

    /**
     * Carga el modelo desde assets (carpeta o zip). NO crea el Recognizer.
     * Llama a [Callbacks.onReady] al terminar OK.
     */
    fun prepare() {
        if (ready) { callbacks.onReady(); return }
        Log.i(TAG, "prepare(): unpack del modelo…")

        // IMPORTANTE:
        //  - El segundo parámetro es *el nombre en assets/*.
        //  - Usar el que exista: carpeta "vosk-model-small-es-0.42" o zip "vosk-model-small-es-0.42.zip".
        StorageService.unpack(
            ctx.applicationContext,
            /* sourcePath */ "vosk-model-small-es-0.42",  // <-- si usás .zip, cambialo por "vosk-model-small-es-0.42.zip"
            /* targetPath */ "vosk-es",
            { unpackedModel ->
                try {
                    model = unpackedModel
                    ready = true
                    Log.i(TAG, "Modelo listo ✔")
                    callbacks.onReady()
                } catch (e: Exception) {
                    Log.e(TAG, "Error guardando modelo", e)
                    callbacks.onError("Error preparando modelo: ${e.message}")
                }
            },
            { ex ->
                Log.e(TAG, "unpack ERROR", ex)
                callbacks.onError("No se pudo preparar el modelo: ${ex.localizedMessage}")
            }
        )
    }

    /** True cuando el modelo está listo para crear Recognizers y escuchar. */
    fun isReady(): Boolean = ready

    /**
     * Cambia la gramática en caliente.
     * - Pasa `grammarJson` con un JSON de frases/palabras esperadas (JSONArray como String).
     * - Pasa `null` para modo libre (sin gramática).
     *
     * Reinicia internamente el recognizer para aplicar la nueva gramática.
     */
    fun setGrammar(grammarJson: String?) {
        if (!ready) {
            callbacks.onError("Modelo no listo")
            return
        }
        // Si hay audio corriendo, detener antes de cambiar gramática
        stopInternal()

        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null

        try {
            val mdl = model ?: run {
                callbacks.onError("Modelo no disponible")
                return
            }
            currentGrammarJson = grammarJson
            recognizer = if (grammarJson.isNullOrBlank()) {
                Log.d(TAG, "Aplicando gramática: LIBRE (null)")
                Recognizer(mdl, sampleRate.toFloat())
            } else {
                Log.d(TAG, "Aplicando gramática JSON con ${grammarJson.length} chars")
                Recognizer(mdl, sampleRate.toFloat(), grammarJson)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creando Recognizer con la gramática", e)
            callbacks.onError("Error creando Recognizer: ${e.message}")
            recognizer = null
        }
    }

    // =========================================================
    // Captura y reconocimiento
    // =========================================================

    /**
     * Inicia captura de micrófono (AudioRecord) y decodificación Vosk.
     * Requiere haber llamado antes a [setGrammar] (modo libre o con gramática).
     */
    @SuppressLint("MissingPermission")
    fun start() {
        val rec = recognizer
        if (!ready || rec == null) {
            callbacks.onError("Recognizer no configurado; llamá a setGrammar() primero")
            return
        }
        if (job != null) return

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf
        )

        try {
            audioRecord?.startRecording()
        } catch (se: SecurityException) {
            callbacks.onError("Permiso de micrófono requerido")
            return
        }

        callbacks.onListening()

        // Bucle de lectura/decodificación en IO
        job = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ShortArray(2048)
            while (isActive) {
                val n = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (n <= 0) continue
                val r = recognizer ?: break

                val accepted = r.acceptWaveForm(buffer, n)
                if (accepted) {
                    // Resultado final
                    try {
                        val txt = JSONObject(r.result).optString("text", "")
                        withContext(Dispatchers.Main) { callbacks.onResult(txt) }
                    } catch (_: Throwable) {
                        withContext(Dispatchers.Main) { callbacks.onResult("") }
                    }
                } else {
                    // Parciales
                    try {
                        val p = JSONObject(r.partialResult).optString("partial", "")
                        if (p.isNotBlank()) {
                            withContext(Dispatchers.Main) { callbacks.onPartial(p) }
                        }
                    } catch (_: Throwable) {
                        // Ignorar JSON corrupto de parciales
                    }
                }
            }
        }
    }

    /**
     * Detiene la captura/decodificación actual (si la hay).
     * El recognizer queda listo para reusar (se hace reset).
     */
    fun stop() {
        stopInternal()
        try { recognizer?.reset() } catch (_: Throwable) {}
    }

    /** Cierra audio y job internos sin tocar el recognizer. */
    private fun stopInternal() {
        job?.cancel(); job = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    /**
     * Libera todos los recursos (audio, recognizer y modelo).
     * Luego de esto, debe llamarse nuevamente a [prepare] para volver a usar.
     */
    fun destroy() {
        stopInternal()
        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null
        try { model?.close() } catch (_: Throwable) {}
        model = null
        ready = false
    }
}
