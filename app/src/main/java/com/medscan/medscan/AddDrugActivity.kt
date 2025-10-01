package com.medscan.medscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.medscan.medscan.data.MedicineRepository
import com.medscan.medscan.databinding.ActivityAddDrugBinding
import com.medscan.medscan.db.AppDatabase
import com.medscan.medscan.db.entities.Drug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

@ExperimentalGetImage
class AddDrugActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityAddDrugBinding

    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null

    private lateinit var tts: TextToSpeech
    private var stt: SpeechRecognizer? = null
    private lateinit var repo: MedicineRepository

    private var lastOcrText: String = ""
    private var lastPartialSaid: String? = null
    private var sttAutoRetryPending = false

    // ➕ Contador de fallas estructurales
    private var sttStructuralFailCount = 0
    private val MAX_STRUCTURAL_FAILS = 3

    // IDs TTS
    private val UTT_INIT   = "UTT_INIT"
    private val UTT_PROMPT = "UTT_PROMPT"

    // Regex gatillo flexible
    private val TRIGGER_REGEX = Regex(
        pattern = """^\s*(?:El\s+)?(?:medicamento\s+)?se\s+llama\s+(.*)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    private data class OcrFuzzy(val ok: Boolean, val ocrToken: String?, val score: Float)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddDrugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = MedicineRepository(this)
        tts = TextToSpeech(this, this)

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.detectionButton.setOnClickListener {
            vibrateShort()
            imageAnalysis?.setAnalyzer(cameraExecutor) { image -> processImage(image) }
        }

        binding.flashButton.setOnClickListener {
            vibrateShort()
            camera?.let { cam ->
                if (cam.cameraInfo.hasFlashUnit()) {
                    val isOn = cam.cameraInfo.torchState.value == TorchState.ON
                    cam.cameraControl.enableTorch(!isOn)
                    vibrateFlash(!isOn)
                } else {
                    Toast.makeText(this, "El dispositivo no tiene linterna", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.exitButton.setOnClickListener {
            vibrateShort()
            finish()
        }
    }

    /* ------------------- Cámara / OCR ------------------- */

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, selector, preview, imageAnalysis)

                camera?.cameraInfo?.torchState?.observe(this) { state ->
                    binding.flashButton.setImageResource(
                        if (state == TorchState.ON) R.drawable.ic_lantern_on
                        else R.drawable.ic_lantern_off
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al iniciar la cámara", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)

        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                val raw = visionText.text ?: ""
                lastOcrText = raw
                Log.d("ADD_DRUG_OCR", "OCR:\n$lastOcrText")

                if (!hasMeaningfulOcr(raw)) {
                    binding.textView.text = "No se detectó texto legible. Intente nuevamente."
                    speak("No se detectó texto legible. Vuelva a enfocar y presione Detectar.", UTT_INIT)
                    lastOcrText = ""
                } else {
                    // ✅ Nuevo OCR válido → reseteo de fallas
                    sttStructuralFailCount = 0

                    binding.textView.text =
                        "Texto detectado. Diga a continuación: El medicamento se llama... para continuar."
                    speak(
                        "Texto detectado. Diga a continuación: El medicamento se llama, para continuar.",
                        UTT_PROMPT
                    )
                }
            }
            .addOnFailureListener {
                binding.textView.text = "Error al detectar texto"
                speak("Error al detectar texto", UTT_INIT)
                lastOcrText = ""
            }
            .addOnCompleteListener {
                imageProxy.close()
                imageAnalysis?.clearAnalyzer()
            }
    }

    /* ------------------- TTS ------------------- */

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts.setLanguage(Locale("es", "AR"))
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Idioma no soportado")
            }
            tts.setSpeechRate(1.10f)
            tts.setPitch(1.0f)

            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == UTT_PROMPT) {
                        binding.textView.postDelayed({
                            if (lastOcrText.isNotBlank()) startListening()
                        }, 900)
                    }
                }
                override fun onError(utteranceId: String?) {}
            })

            speak(
                "Enfoque la caja y presione Detectar. Luego diga: El medicamento se llama, seguido del nombre.",
                UTT_INIT
            )
        } else {
            Log.e(TAG, "Error al inicializar TTS")
        }
    }

    private fun speak(text: String, id: String) {
        val params = Bundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    /* ------------------- STT ------------------- */

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Reconocimiento de voz no disponible", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
            return
        }

        if (stt == null) {
            stt = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { Log.d(STT_TAG, "onReadyForSpeech") }
                    override fun onBeginningOfSpeech() { Log.d(STT_TAG, "onBeginningOfSpeech") }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { Log.d(STT_TAG, "onEndOfSpeech") }

                    override fun onError(error: Int) {
                        Log.e(STT_TAG, "onError: ${sttErrorToString(error)} ($error)")

                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            try { stt?.cancel(); stt?.destroy() } catch (_: Exception) {}
                            stt = null
                            binding.textView.postDelayed({ if (lastOcrText.isNotBlank()) startListening() }, 300)
                            return
                        }

                        // Silencio / no match → cuenta como falla estructural (caso 2)
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            handleStructuralFailAndMaybeStop()
                            return
                        }

                        // Otros errores → mensaje formal
                        binding.textView.text =
                            "No fue posible reconocer su respuesta. Por favor, diga: El medicamento se llama..."
                        speak(
                            "No fue posible reconocer su respuesta. Por favor, diga: El medicamento se llama, seguido del nombre.",
                            UTT_PROMPT
                        )
                    }

                    override fun onResults(results: Bundle) {
                        val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        var said = matches?.firstOrNull()?.trim().orEmpty()

                        // Fallback: usar parcial solo si incluye "llama"
                        if (said.isEmpty() && !lastPartialSaid.isNullOrBlank()) {
                            val lp = lastPartialSaid!!.trim()
                            if (lp.contains("llama", ignoreCase = true)) {
                                said = lp
                                Log.d(STT_TAG, "Usando último parcial como resultado: '$said'")
                            }
                        }

                        val saidNorm = normalizeLettersOnly(said)
                        Log.d(STT_TAG, "matches=$matches")
                        Log.d(STT_TAG, "said='$said'")
                        Log.d(STT_TAG, "saidNorm='$saidNorm'")

                        if (said.isEmpty()) {
                            // No se dijo nada útil → falla estructural (caso 2)
                            handleStructuralFailAndMaybeStop()
                            return
                        }

                        if (saidNorm == "SALIR") { finish(); return }

                        // Extraer nombre con trigger flexible
                        val m = TRIGGER_REGEX.find(said)
                        if (m == null) {
                            // No respeta el gatillo → falla estructural (caso 1)
                            handleStructuralFailAndMaybeStop()
                            return
                        }

                        val nameRaw = m.groupValues[1].trim()
                        Log.d(STT_TAG, "nameRaw extraído='$nameRaw'")

                        if (nameRaw.isEmpty()) {
                            // Gatillo correcto pero sin nombre → falla estructural (caso 2)
                            handleStructuralFailAndMaybeStop()
                            return
                        }

                        // Hasta acá estructura OK → no cuenta como falla
                        handleSpokenName(nameRaw)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        lastPartialSaid = partial
                        Log.d(STT_TAG, "partialResults=$partial")
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1800)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2200)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga: El medicamento se llama... y el nombre")
        }

        lastPartialSaid = null
        Log.d(STT_TAG, "startListening con idioma es-AR")
        stt?.startListening(intent)
    }

    /**
     * Cuenta una falla estructural y decide si detener o reintentar.
     */
    private fun handleStructuralFailAndMaybeStop() {
        sttStructuralFailCount++
        Log.d(STT_TAG, "Falla estructural #$sttStructuralFailCount / $MAX_STRUCTURAL_FAILS")

        if (sttStructuralFailCount >= MAX_STRUCTURAL_FAILS) {
            // Detener la escucha y pedir volver a Detectar
            binding.textView.text =
                "Se detectaron varios intentos fallidos. Por favor, presione Detectar para volver a intentar."
            speak(
                "Se detectaron varios intentos fallidos. Por favor, presione Detectar para volver a intentar.",
                UTT_INIT
            )
            // No reiniciamos el STT aquí
            return
        }

        // Reintento: repetir instrucción y volver a escuchar
        binding.textView.text =
            "Por favor, diga: El medicamento se llama... y el nombre."
        speak(
            "Por favor, diga: El medicamento se llama, seguido del nombre.",
            UTT_PROMPT
        )
        // startListening() se volverá a invocar al terminar el TTS (UTT_PROMPT)
    }

    /* ------------------- Match & Guardado ------------------- */

    private fun handleSpokenName(nameRaw: String) {
        if (lastOcrText.isBlank()) {
            runOnUiThread {
                binding.textView.text = "No hay texto de foto para comparar. Vuelva a detectar."
                speak("No hay texto de foto para comparar. Vuelva a detectar.", UTT_INIT)
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val spoken = nameRaw.trim()
            val ocr = lastOcrText

            val fm = ocrMatchesSpoken(spoken, ocr)
            Log.d(STT_TAG, "Fuzzy OCR↔voz ok=${fm.ok} score=${fm.score} tokenOCR='${fm.ocrToken}'")
            if (!fm.ok) {
                // ⚠️ No cuenta como falla estructural → solo reintenta
                runOnUiThread {
                    binding.textView.text =
                        "Lo indicado no coincide con el texto detectado. Intente nuevamente."
                    speak(
                        "Lo indicado no coincide con el texto detectado. Intente nuevamente.",
                        UTT_PROMPT
                    )
                }
                return@launch
            }

            // Éxito semántico → resetear fallas
            sttStructuralFailCount = 0

            val ocrToken = fm.ocrToken ?: spoken
            val ocrDbName = repo.findBestDrugName(ocrToken)
            val toInsert = when {
                !ocrDbName.isNullOrBlank() -> ocrDbName
                else -> toTitleCaseEs(ocrToken)
            }

            val dao = AppDatabase.get(this@AddDrugActivity).medicineDao()
            val newDrug = Drug(name = toInsert, normalized = normalizeLettersOnly(toInsert))
            dao.insertDrugs(listOf(newDrug))

            runOnUiThread {
                val msg = "Medicamento añadido: $toInsert"
                binding.textView.text = msg
                speak(msg, UTT_INIT)
            }
        }
    }

    /* ------------------- OCR utils / Fuzzy ------------------- */

    private fun hasMeaningfulOcr(raw: String): Boolean {
        val norm = normalizeLettersOnly(raw)
        if (norm.isBlank()) return false
        val tokens = norm.split(" ").filter { it.any { ch -> ch.isLetterOrDigit() } }
        val chars = tokens.sumOf { it.count { ch -> ch.isLetterOrDigit()} }
        return tokens.size >= 2 && chars >= 6
    }

    private fun ocrMatchesSpoken(spoken: String, ocrText: String, threshold: Float = 0.78f): OcrFuzzy {
        val sNorm = normalizeLettersOnly(spoken)
        val raw = ocrText
        val oNorm = normalizeLettersOnly(raw)
        if (sNorm.isBlank() || oNorm.isBlank()) return OcrFuzzy(false, null, 0f)

        data class Tok(val orig: String, val norm: String)
        val tokens = raw.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Tok(it, normalizeLettersOnly(it)) }

        if (oNorm.contains(sNorm)) return OcrFuzzy(true, sNorm, 1f)

        var bestSim = 0f
        var best: Tok? = null
        for (t in tokens) {
            if (t.norm.length < 3) continue
            if (abs(t.norm.length - sNorm.length) > 3) continue
            val sameEdge = (t.norm.firstOrNull() == sNorm.firstOrNull()) ||
                    (t.norm.lastOrNull()  == sNorm.lastOrNull())
            val sim = levenshteinSimilarity(t.norm, sNorm)
            if (sim > bestSim && (sameEdge || sim >= 0.86f)) {
                bestSim = sim; best = t
            }
        }
        return if (best != null && bestSim >= threshold) OcrFuzzy(true, best.orig, bestSim)
        else OcrFuzzy(false, null, bestSim)
    }

    private fun levenshteinSimilarity(a: String, b: String): Float {
        if (a == b) return 1f
        val dist = levenshtein(a, b)
        val denom = max(a.length, b.length).toFloat()
        return if (denom == 0f) 0f else 1f - (dist / denom)
    }

    private fun levenshtein(a: String, b: String): Int {
        val m = a.length; val n = b.length
        if (m == 0) return n; if (n == 0) return m
        val dp = IntArray(n + 1) { it }
        for (i in 1..m) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..n) {
                val temp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(minOf(dp[j] + 1, dp[j - 1] + 1), prev + cost)
                prev = temp
            }
        }
        return dp[n]
    }

    /* ------------------- Utilidades ------------------- */

    private fun toTitleCaseEs(s: String): String =
        s.lowercase(Locale("es", "AR"))
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { c -> c.titlecase(Locale("es", "AR")) }
            }

    private fun normalizeLettersOnly(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("[^\\p{L}\\p{Nd}\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .uppercase()

    private fun vibrateShort() {
        val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } else vib.vibrate(120)
    }

    private fun vibrateFlash(isOn: Boolean) {
        val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = if (isOn) {
                VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE)
            } else {
                VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 100), -1)
            }
            vib.vibrate(effect)
        } else {
            if (isOn) vib.vibrate(250) else vib.vibrate(longArrayOf(0, 100, 80, 100), -1)
        }
    }

    private fun allPermissionsGranted(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun sttErrorToString(code: Int) = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
        SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
        SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
        SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
        SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
        else -> "ERROR_$code"
    }

    override fun onDestroy() {
        super.onDestroy()
        imageAnalysis?.clearAnalyzer()
        try { camera?.cameraControl?.enableTorch(false) } catch (_: Exception) {}
        cameraExecutor.shutdown()
        stt?.destroy()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    companion object {
        private const val TAG = "AddDrugActivity"
        private const val STT_TAG = "STT_RESULT"
        private const val REQUEST_CODE_PERMISSIONS = 11

        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }
}
