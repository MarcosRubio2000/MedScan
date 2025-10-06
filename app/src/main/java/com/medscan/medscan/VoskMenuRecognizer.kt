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

class VoskMenuRecognizer(
    private val ctx: Context,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun onReady()
        fun onListening()
        fun onPartial(text: String)
        fun onResult(text: String)
        fun onError(msg: String)
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private var ready = false

    private var currentGrammarJson: String? = null

    private val sampleRate = 16000
    private val TAG = "VOSK_FLEX"

    /** Carga el modelo desde assets (carpeta o zip). NO crea el Recognizer. */
    fun prepare() {
        if (ready) { callbacks.onReady(); return }
        Log.i(TAG, "prepare(): unpack del modelo…")

        // IMPORTANTE: el segundo parámetro es *el nombre en assets/*.
        // Usá el que tengas: carpeta "vosk-model-small-es-0.42" o zip "vosk-model-small-es-0.42.zip".
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

    /** True cuando el modelo está listo. */
    fun isReady(): Boolean = ready

    /** Cambia la gramática en caliente. Pasa null para modo libre. */
    fun setGrammar(grammarJson: String?) {
        if (!ready) {
            callbacks.onError("Modelo no listo")
            return
        }
        // Si hay audio corriendo, lo detenemos antes de cambiar grammar
        stopInternal()

        try {
            recognizer?.close()
        } catch (_: Throwable) {}
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

        job = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ShortArray(2048)
            while (isActive) {
                val n = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (n <= 0) continue
                val r = recognizer ?: break

                val accepted = r.acceptWaveForm(buffer, n)
                if (accepted) {
                    try {
                        val txt = JSONObject(r.result).optString("text", "")
                        withContext(Dispatchers.Main) { callbacks.onResult(txt) }
                    } catch (_: Throwable) {
                        withContext(Dispatchers.Main) { callbacks.onResult("") }
                    }
                } else {
                    try {
                        val p = JSONObject(r.partialResult).optString("partial", "")
                        if (p.isNotBlank()) {
                            withContext(Dispatchers.Main) { callbacks.onPartial(p) }
                        }
                    } catch (_: Throwable) {
                        // ignorar JSON corrupto de parciales
                    }
                }
            }
        }
    }

    fun stop() {
        stopInternal()
        // después de stop(), el recognizer queda listo para reusar (no lo cerramos)
        try { recognizer?.reset() } catch (_: Throwable) {}
    }

    private fun stopInternal() {
        job?.cancel(); job = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioRecord?.release() } catch (_: Throwable) {}
        audioRecord = null
    }

    fun destroy() {
        stopInternal()
        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null
        try { model?.close() } catch (_: Throwable) {}
        model = null
        ready = false
    }
}
