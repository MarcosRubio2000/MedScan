package com.medscan.medscan

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import org.json.JSONArray
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
    private var isReadyFlag = false
    fun isReady(): Boolean = isReadyFlag

    private val sampleRate = 16000

    // Gramática de menú
    private val grammar = arrayOf(
        "uno","dos","tres",
        "opcion uno","opcion dos","opcion tres",
        "opción uno","opción dos","opción tres",
        "modo uno","modo dos","modo tres",
        "repetir","de nuevo","otra vez",
        "salir","volver","atras","atrás"
    )
    // Vosk espera una cadena JSON (e.g. ["uno","dos"])
    private val grammarJson: String = JSONArray(grammar).toString()

    fun prepare() {
        if (isReadyFlag) return
        val TAG = "VOSK_PREP"

        // 🔍 Paso de depuración: listar los assets visibles
        try {
            val list = ctx.assets.list("vosk-model-small-es-0.42") ?: emptyArray()
            android.util.Log.i(TAG, "assets/vosk-model-small-es-0.42 contiene: ${list.joinToString()}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error leyendo assets: ${e.message}")
        }

        android.util.Log.i(TAG, "prepare(): iniciando unpack...")

        StorageService.unpack(
            ctx.applicationContext,
            "vosk-model-small-es-0.42",  // carpeta (no .zip)
            "vosk-es",
            { unpackedModel ->
                try {
                    model = unpackedModel
                    recognizer = Recognizer(model, sampleRate.toFloat(), grammarJson)
                    isReadyFlag = true
                    android.util.Log.i(TAG, "modelo listo ✔")
                    callbacks.onReady()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "error creando Recognizer", e)
                    callbacks.onError("Error creando Recognizer: ${e.message}")
                }
            },
            { ex ->
                android.util.Log.e(TAG, "unpack ERROR", ex)
                callbacks.onError("No se pudo preparar el modelo: ${ex.localizedMessage}")
            }
        )
    }

    @SuppressLint("MissingPermission") // Verificá RECORD_AUDIO antes de llamar a start()
    fun start() {
        if (!isReadyFlag) { callbacks.onError("Modelo no listo"); return }
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
        } catch (_: SecurityException) {
            callbacks.onError("Permiso de micrófono requerido")
            return
        }

        callbacks.onListening()

        job = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ShortArray(2048)
            while (isActive) {
                val n = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (n <= 0) continue
                val rec = recognizer ?: break

                val accepted = rec.acceptWaveForm(buffer, n)
                if (accepted) {
                    // result es propiedad, no función
                    val txt = JSONObject(rec.result).optString("text", "")
                    withContext(Dispatchers.Main) { callbacks.onResult(txt) }
                } else {
                    // partialResult es propiedad, no función
                    val p = JSONObject(rec.partialResult).optString("partial", "")
                    if (p.isNotBlank()) {
                        withContext(Dispatchers.Main) { callbacks.onPartial(p) }
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try { audioRecord?.stop() } catch (_: Throwable) {}
        audioRecord?.release()
        audioRecord = null
        recognizer?.reset()
    }

    fun destroy() {
        stop()
        try { recognizer?.close() } catch (_: Throwable) {}
        recognizer = null
        try { model?.close() } catch (_: Throwable) {}
        model = null
        isReadyFlag = false
    }
}
