package com.medscan.medscan

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.content.Intent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

class HelpActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var isTtsReady = false
    private var sr: SpeechRecognizer? = null

    private lateinit var statusText: TextView
    private lateinit var backButton: ImageButton
    private lateinit var option1Row: ConstraintLayout
    private lateinit var option2Row: ConstraintLayout
    private lateinit var option3Row: ConstraintLayout

    private var failCount = 0
    private var lastPrompt: String = ""

    // Timeout manual mientras esperamos voz
    private val STT_TIMEOUT_MS = 3500L
    private var sttTimeoutRunnable: Runnable? = null

    // Estado STT
    private var lastPartial: String? = null
    private var quickRetryPending = false

    // Frase estándar de re-prompt
    private val REPROMPT = "Diga Uno, Dos, Tres, Repetir o Salir."

    companion object {
        private const val REQ_RECORD_AUDIO = 9101
    }

    // ---------------- Ciclo ----------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_help)

        statusText = findViewById(R.id.statusText)
        backButton = findViewById(R.id.backButton)
        option1Row = findViewById(R.id.option1Row)
        option2Row = findViewById(R.id.option2Row)
        option3Row = findViewById(R.id.option3Row)

        tts = TextToSpeech(this, this)

        backButton.setOnClickListener { v ->
            Haptics.navBack(this, v)
            stopAudio(); finish()
        }
        option1Row.setOnClickListener { v ->
            Haptics.click(this, v)
            forceCloseSttCycle()
            speakDetection()
        }
        option2Row.setOnClickListener { v ->
            Haptics.click(this, v)
            forceCloseSttCycle()
            speakAddMode()
        }
        option3Row.setOnClickListener { v ->
            Haptics.click(this, v)
            forceCloseSttCycle()
            speakAbout()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("es", "AR")
            tts.setSpeechRate(1.1f)
            tts.setPitch(1.0f)
            isTtsReady = true

            val intro = """
                Bienvenido a las instrucciones.
                Para detección de medicamentos, diga Uno.
                Para guardar nuevos medicamentos, diga Dos.
                Para conocer más información acerca de MedScan, diga Tres.
            """.trimIndent()

            // Intro + reprompt, luego abrimos mic
            speak("$intro $REPROMPT") { askRecordPermissionThen { startListeningWithBeep() } }
        } else {
            statusText.text = "Error al iniciar voz."
        }
    }

    override fun onPause() {
        super.onPause()
        stopAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
    }

    // ---------------- Helpers de audio / TTS ----------------
    private fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isTtsReady) return
        // asegurar que no hay SR vivo mientras hablamos
        forceCloseSttCycle()

        lastPrompt = text
        val id = System.currentTimeMillis().toString()
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { runOnUiThread { statusText.text = "Hablando…" } }
            override fun onDone(utteranceId: String?) { runOnUiThread { onDone?.invoke() } }
            override fun onError(utteranceId: String?) { runOnUiThread { statusText.text = "Error de voz." } }
        })
        val params = Bundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    private fun speakThenReprompt(main: String) {
        // Cerrar SR, hablar y reabrir mic al final
        speak("$main $REPROMPT") { startListeningWithBeep() }
    }

    private fun beepAndHaptic(anchor: View? = null) {
        Haptics.click(this, anchor)
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 120) } catch (_: Throwable) {}
    }

    private fun stopAudio() {
        try { sr?.cancel() } catch (_: Throwable) {}
        try { sr?.destroy() } catch (_: Throwable) {}
        sr = null
        if (isTtsReady) tts.stop()
        cancelSttTimeout()
    }

    // Corta SIEMPRE el ciclo de STT actual (para usar antes de cualquier TTS o navegación)
    private fun forceCloseSttCycle() {
        cancelSttTimeout()
        quickRetryPending = false
        lastPartial = null
        try { sr?.cancel() } catch (_: Throwable) {}
        try { sr?.destroy() } catch (_: Throwable) {}
        sr = null
    }

    // ---------------- Reconocedor: lifecycle robusto ----------------
    private fun restartRecognizer() {
        forceCloseSttCycle() // incluye destroy/cancel
        ensureRecognizer()
    }

    private fun ensureRecognizer() {
        if (sr != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = "Reconocimiento de voz no disponible."
            return
        }
        sr = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    statusText.text = "Escuchando…"
                    android.util.Log.d("STT_DEBUG", "onReadyForSpeech")
                    lastPartial = null
                    scheduleSttTimeout()
                }
                override fun onBeginningOfSpeech() {
                    // si hay voz, damos margen para terminar la palabra/frase
                    scheduleSttTimeout(2500L)
                }
                override fun onRmsChanged(rmsdB: Float) {
                    android.util.Log.d("STT_DEBUG_RMS", "rms=$rmsdB")
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    cancelSttTimeout()
                    android.util.Log.d("STT_DEBUG", "onError: $error")
                    if (!quickRetryPending &&
                        (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) {
                        quickRetryPending = true
                        statusText.postDelayed({ listen() }, 250L)
                        return
                    }
                    quickRetryPending = false
                    handleUnrecognized()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    lastPartial = partial
                    android.util.Log.d("STT_DEBUG", "Parcial: $partial")

                    if (!partial.isNullOrBlank()) scheduleSttTimeout(1800L)

                    // Si ya detectamos opción, cortar SR antes de hablar
                    matchOption(partial ?: "")?.let {
                        forceCloseSttCycle()
                        dispatchOption(it) // esto habla y luego reabre el mic con reprompt
                    }
                }

                override fun onResults(results: Bundle) {
                    cancelSttTimeout()
                    quickRetryPending = false
                    val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                    android.util.Log.d("STT_DEBUG", "Resultados: ${list.joinToString()}")

                    val opt = list.firstNotNullOfOrNull { matchOption(it) }
                    if (opt != null) {
                        forceCloseSttCycle()
                        dispatchOption(opt)
                        return
                    }

                    var said = list.firstOrNull().orEmpty().trim()
                    if (said.isEmpty() && !lastPartial.isNullOrBlank()) {
                        android.util.Log.d("STT_DEBUG", "Usando parcial como resultado: $lastPartial")
                        said = lastPartial!!.trim()
                    }
                    android.util.Log.d("STT_DEBUG", "Reconocido(final): $said")
                    handleCommand(said)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    // ---------------- Timeout manual ----------------
    private fun scheduleSttTimeout(delayMs: Long = STT_TIMEOUT_MS) {
        cancelSttTimeout()
        sttTimeoutRunnable = Runnable {
            android.util.Log.d("STT_DEBUG", "Timeout manual alcanzado ($delayMs ms).")
            try { sr?.cancel() } catch (_: Throwable) {}
            quickRetryPending = false
            handleUnrecognized()
        }
        statusText.postDelayed(sttTimeoutRunnable!!, delayMs)
    }

    private fun cancelSttTimeout() {
        sttTimeoutRunnable?.let { statusText.removeCallbacks(it) }
        sttTimeoutRunnable = null
    }

    // ---------------- Abrir el mic ----------------
    private fun listen() {
        cancelSttTimeout()
        ensureRecognizer()
        try { sr?.cancel() } catch (_: Throwable) {}
        lastPartial = null
        quickRetryPending = false

        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-AR")

            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // Tiempos moderados (afinables)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 600L)
        }
        android.util.Log.d("STT_DEBUG", "listen(): start")
        sr?.startListening(i)
    }

    private fun startListeningWithBeep() {
        beepAndHaptic()
        restartRecognizer()
        // pequeño delay para no pisar el audio-focus del beep
        statusText.postDelayed({ listen() }, 200L)
    }

    private fun askRecordPermissionThen(onGranted: () -> Unit) {
        val ok = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (ok) onGranted() else ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startListeningWithBeep()
        }
    }

    // ---------------- Comandos y respuestas ----------------
    private fun handleCommand(raw: String) {
        val cmd = normalize(raw)
        when {
            cmd.isEmpty() -> { handleUnrecognized(); return }

            containsAny(cmd, "salir", "volver", "atras", "atrás") -> {
                // Cerrar SR y salir
                speak("Regresando a la pantalla de inicio.") { stopAudio(); finish() }
            }
            containsAny(cmd, "repetir", "de nuevo", "otra vez") -> {
                failCount = 0
                // Repetimos el último bloque que habló (intro u opción), y reprompt
                speak(lastPrompt) { speak(REPROMPT) { startListeningWithBeep() } }
            }
            isOpt(cmd, 1) -> speakDetection()
            isOpt(cmd, 2) -> speakAddMode()
            isOpt(cmd, 3) -> speakAbout()
            else -> handleUnrecognized()
        }
    }

    private fun speakDetection() {
        failCount = 0
        val text = """
            Modo detección.
            Paso 1: Coloque la caja frente a la cámara, con correcta iluminación y el texto centrado.
            Paso 2: Toque Detectar.
            Paso 3: Espere el resultado. Se leerá el nombre y la dosis si aparecen.
            Si no se reconoce, acerque un poco la cámara o encienda la linterna, y vuelva a tocar Detectar.
        """.trimIndent()
        speakThenReprompt(text)
    }

    private fun speakAddMode() {
        failCount = 0
        val text = """
            Modo añadir nuevos medicamentos.
            Paso 1: para ingresar a este modo, pulse el botón que se encuentra en la esquina superior derecha de la pantalla de inicio.
            Paso 2: apunte la cámara a la caja y toque Detectar.
            Paso 3: una vez la aplicación confirme que hay texto, diga: El medicamento se llama... seguido del nombre. Por ejemplo: El medicamento se llama Ibuprofeno.
            La aplicación comparará lo que dijo con el texto de la foto.
            Si coinciden, guardará el nombre en su lista.
            Si no coinciden, se solicitará que lo repita.
        """.trimIndent()
        speakThenReprompt(text)
    }

    private fun speakAbout() {
        failCount = 0
        val text = """
            MedScan es una aplicación móvil impulsada por Inteligencia Artificial que ayuda a personas con discapacidad visual a identificar sus medicamentos de forma rápida y segura.
            Desarrollada por Marcos Rubio, Daniela Araujo y Catalina Crespo, estudiantes de la Universidad Nacional de Villa Mercedes, esta app fue creada en el marco del proyecto de la materia Ingeniería de Rehabilitación.
        """.trimIndent()
        speakThenReprompt(text)
    }

    private fun handleUnrecognized() {
        failCount++
        if (failCount >= 3) {
            failCount = 0
            speak("Se detectaron varios intentos fallidos. Ha sido regresado al modo detección.") {
                stopAudio(); finish()
            }
        } else {
            speak("No fue posible reconocer su respuesta.") {
                speak(REPROMPT) { startListeningWithBeep() }
            }
        }
    }

    // ---------------- Utils ----------------
    private fun normalize(s: String): String {
        val lowered = s.lowercase(Locale("es", "AR"))
        val n = Normalizer.normalize(lowered, Normalizer.Form.NFD)
        return n.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("[^a-z0-9\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun containsAny(text: String, vararg options: String) = options.any { text.contains(it) }

    private fun isOpt(cmd: String, n: Int): Boolean {
        val s = n.toString()
        return containsAny(
            cmd,
            s, "opcion $s", "opción $s", "modo $s",
            when (n) { 1 -> "uno"; 2 -> "dos"; 3 -> "tres"; else -> "" }
        )
    }

    // ------ Matching robusto Uno/Dos/Tres ------
    private fun normTokens(s: String): List<String> =
        normalize(s).split(" ").filter { it.isNotBlank() }

    private fun near(word: String, target: String): Boolean {
        if (word == target) return true
        if (abs(word.length - target.length) > 1) return false
        var i = 0; var j = 0; var edits = 0
        while (i < word.length && j < target.length) {
            if (word[i] == target[j]) { i++; j++; continue }
            edits++
            if (edits > 1) return false
            if (i + 1 < word.length && j + 1 < target.length && word[i + 1] == target[j + 1]) { i++; j++; continue }
            if (i + 1 < word.length && word[i + 1] == target[j]) { i++; continue }
            if (j + 1 < target.length && word[i] == target[j + 1]) { j++; continue }
            return false
        }
        edits += (word.length - i) + (target.length - j)
        return edits <= 1
    }

    private fun matchOption(raw: String): Int? {
        val cmd = normalize(raw)
        if (cmd.isEmpty()) return null

        if (Regex("""\b1\b""").containsMatchIn(cmd)) return 1
        if (Regex("""\b2\b""").containsMatchIn(cmd)) return 2
        if (Regex("""\b3\b""").containsMatchIn(cmd)) return 3

        val toks = normTokens(cmd)
        val one = arrayOf("uno", "un", "bueno")
        val two = arrayOf("dos", "do", "voz")
        val thr = arrayOf("tres", "tre", "trés", "stress", "estres")

        fun hasAnyNear(cands: Array<String>) =
            toks.any { w -> cands.any { near(w, it) } } || cands.any { it in cmd }

        if (hasAnyNear(one)) return 1
        if (hasAnyNear(two)) return 2
        if (hasAnyNear(thr)) return 3

        if (Regex("""\b(opcion|opción|modo)\s*1\b""").containsMatchIn(cmd)) return 1
        if (Regex("""\b(opcion|opción|modo)\s*2\b""").containsMatchIn(cmd)) return 2
        if (Regex("""\b(opcion|opción|modo)\s*3\b""").containsMatchIn(cmd)) return 3

        if (Regex("""\b(opcion|opción|modo)\s*uno\b""").containsMatchIn(cmd)) return 1
        if (Regex("""\b(opcion|opción|modo)\s*dos\b""").containsMatchIn(cmd)) return 2
        if (Regex("""\b(opcion|opción|modo)\s*tres\b""").containsMatchIn(cmd)) return 3

        return null
    }

    private fun dispatchOption(opt: Int) {
        when (opt) {
            1 -> speakDetection()
            2 -> speakAddMode()
            3 -> speakAbout()
        }
    }
}
