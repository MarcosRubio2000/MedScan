package com.medscan.medscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
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

    companion object {
        private const val REQ_RECORD_AUDIO = 9101
    }

    // ---------- Ciclo ----------
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
            speakDetection()
        }
        option2Row.setOnClickListener { v ->
            Haptics.click(this, v)
            speakAddMode()
        }
        option3Row.setOnClickListener { v ->
            Haptics.click(this, v)
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
                Para volver a la pantalla anterior, diga Salir.
                Para repetir estas instrucciones, diga Repetir.
            """.trimIndent()

            speak(intro) { askRecordPermissionThen { startListeningWithBeep() } }
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

    // ---------- Audio helpers ----------
    private fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isTtsReady) return
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

    private fun beepAndHaptic(anchor: View? = null) {
        Haptics.click(this, anchor) // mismo “feel” que AddDrug
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 120) } catch (_: Throwable) {}
    }

    private fun stopAudio() {
        try { sr?.cancel() } catch (_: Throwable) {}
        if (isTtsReady) tts.stop()
    }

    // ---------- STT ----------
    private fun ensureRecognizer() {
        if (sr != null) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = "Reconocimiento de voz no disponible."
            return
        }
        sr = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { statusText.text = "Escuchando…" }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { handleUnrecognized() }
                override fun onResults(results: Bundle) {
                    val list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: arrayListOf()
                    val said = list.firstOrNull().orEmpty()
                    handleCommand(said)
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun listen() {
        ensureRecognizer()
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L) // 3 s de silencio
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }
        sr?.startListening(i)
    }

    private fun startListeningWithBeep() {
        beepAndHaptic()
        listen()
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

    // ---------- Comandos y respuestas ----------
    private fun handleCommand(raw: String) {
        val cmd = normalize(raw)
        when {
            cmd.isEmpty() -> { handleUnrecognized(); return }

            // salir / volver
            containsAny(cmd, "salir", "volver", "atras", "atrás") -> {
                speak("Volviendo a la pantalla de inicio.") { stopAudio(); finish() }
            }

            // repetir
            containsAny(cmd, "repetir", "de nuevo", "otra vez") -> {
                failCount = 0
                speak(lastPrompt) { startListeningWithBeep() }
            }

            // uno / opción 1 / modo 1
            isOpt(cmd, 1) -> speakDetection()

            // dos / opción 2 / modo 2
            isOpt(cmd, 2) -> speakAddMode()

            // tres / opción 3 / modo 3
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
            Paso 3: Espere el resultado. Le leeré el nombre y la dosis si aparecen.
            Si no se reconoce, acerque un poco la cámara o encienda la linterna, y vuelva a tocar Detectar.
            Para repetir estas instrucciones, diga Repetir.
            Para salir, diga Salir.
        """.trimIndent()
        speak(text) { startListeningWithBeep() }
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
            Si no coinciden, le pediré que lo repita.
            Para repetir estas instrucciones, diga Repetir.
            Para salir, diga Salir.
        """.trimIndent()
        speak(text) { startListeningWithBeep() }
    }

    private fun speakAbout() {
        failCount = 0
        val text = """
            MedScan es una aplicación móvil impulsada por Inteligencia Artificial que ayuda a personas con discapacidad visual a identificar sus medicamentos de forma rápida y segura.
            Desarrollada por Marcos Rubio, Daniela Araujo y Catalina Crespo, estudiantes de la Universidad Nacional de Villa Mercedes, esta app fue creada en el marco del proyecto de la materia Ingeniería de Rehabilitación.
        """.trimIndent()
        speak(text) { startListeningWithBeep() }
    }

    private fun handleUnrecognized() {
        failCount++
        if (failCount >= 3) {
            failCount = 0
            speak("Se detectaron varios intentos fallidos. Ha sido regresado al modo detección.") {
                stopAudio(); finish()
            }
        } else {
            speak("No fue posible reconocer su respuesta. Diga Uno, Dos, Tres o Salir.") {
                startListeningWithBeep()
            }
        }
    }

    // ---------- Utils ----------
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
}
