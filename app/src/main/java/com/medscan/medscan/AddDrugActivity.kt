package com.medscan.medscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.medscan.medscan.data.MedicineRepository
import com.medscan.medscan.databinding.ActivityAddDrugBinding
import com.medscan.medscan.db.AppDatabase
import com.medscan.medscan.db.entities.Drug
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

@ExperimentalGetImage
class AddDrugActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityAddDrugBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null

    // OCR
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // TTS
    private lateinit var tts: TextToSpeech

    // STT Google (online)
    private var stt: SpeechRecognizer? = null
    private var lastPartialSaid: String? = null

    // STT Vosk (offline)
    private var vosk: VoskMenuRecognizer? = null
    private var voskReady = false
    private var waitingForVosk = false
    private var voskLastPartial: String? = null
    private var listenTimeout: Runnable? = null
    private var triggerHeard = false

    // Repo
    private lateinit var repo: MedicineRepository

    // Estado
    private var lastOcrText: String = ""
    private var sttStructuralFailCount = 0
    private val MAX_STRUCTURAL_FAILS = 3
    private var sttSemanticFailCount = 0
    private val MAX_SEMANTIC_FAILS = 3

    // TTS IDs
    private val UTT_INIT   = "UTT_INIT"
    private val UTT_PROMPT = "UTT_PROMPT"

    // Trigger
    private val TRIGGER_REGEX = Regex(
        """(?:^|\s)(?:el\s+)?(?:medicamento(?:s)?\s+)?se\s+(?:llama|yama|shama)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val TRIGGER_WORDS = listOf("se llama", "se yama", "se shama")

    // Ventanas Vosk
    private val PRE_TRIGGER_MAX_MS = 7000L
    private val POST_TRIGGER_TAIL_MS = 2500L

    // --- Selección/forzado de motor ---
    private enum class Engine { AUTO, GOOGLE, VOSK }
    private var forcedEngine: Engine = Engine.AUTO
    private var currentEngine: Engine? = null
    private var attemptId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddDrugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = MedicineRepository(this)
        tts = TextToSpeech(this, this)

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.detectionButton.setOnClickListener { v ->
            Haptics.detect(this, v)
            imageAnalysis?.setAnalyzer(cameraExecutor) { image -> processImage(image) }
        }

        // Long-press para forzar motor (Auto → Google → Vosk)
        binding.detectionButton.setOnLongClickListener {
            forcedEngine = when (forcedEngine) {
                Engine.AUTO -> Engine.GOOGLE
                Engine.GOOGLE -> Engine.VOSK
                Engine.VOSK -> Engine.AUTO
            }
            val msg = when (forcedEngine) {
                Engine.AUTO -> "Motor: Automático"
                Engine.GOOGLE -> "Motor forzado: Google"
                Engine.VOSK -> "Motor forzado: Vosk"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "⚙️ Modo motor = $forcedEngine")
            true
        }

        binding.flashButton.setOnClickListener { v ->
            val cam = camera ?: return@setOnClickListener
            if (!cam.cameraInfo.hasFlashUnit()) {
                Toast.makeText(this, "El dispositivo no tiene linterna", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val isOn = cam.cameraInfo.torchState.value == TorchState.ON
            cam.cameraControl.enableTorch(!isOn)
            if (!isOn) Haptics.flashOn(this, v) else Haptics.flashOff(this, v)
        }

        binding.exitButton.setOnClickListener { v ->
            Haptics.navBack(this, v)
            stopAudio()
            finish()
        }

        // Vosk
        vosk = VoskMenuRecognizer(this, object : VoskMenuRecognizer.Callbacks {
            override fun onReady() { voskReady = true }
            override fun onListening() { runOnUiThread { binding.textView.text = "Escuchando..." } }
            override fun onPartial(text: String) {
                voskLastPartial = text
                Log.d(TAG, "VOSK partial=$text [attempt#$attemptId]")
                handleVoskPartialForTrigger(text)
            }
            override fun onResult(text: String) {
                Log.d(TAG, "VOSK final=$text [attempt#$attemptId]")
                if (!waitingForVosk) return
                handleVoskRecognizedNow(text)
            }
            override fun onError(msg: String) {
                runOnUiThread { binding.textView.text = "Error: $msg" }
                Log.e(TAG, "VOSK error: $msg [attempt#$attemptId]")
            }
        })
        vosk?.prepare()
    }

    override fun onPause() {
        super.onPause()
        stopAudio()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
    }

    private fun stopAudio() {
        try { stt?.cancel() } catch (_: Exception) {}
        try { stt?.destroy() } catch (_: Exception) {}
        stt = null

        try { vosk?.stop() } catch (_: Throwable) {}
        try { vosk?.destroy() } catch (_: Throwable) {}
        waitingForVosk = false
        triggerHeard = false
        cancelListenTimeout()

        if (::tts.isInitialized) { try { tts.stop() } catch (_: Throwable) {} }
    }

    // ---- Cámara / OCR ----
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val selector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, imageAnalysis)
                camera?.cameraInfo?.torchState?.observe(this) { state ->
                    binding.flashButton.setImageResource(
                        if (state == TorchState.ON) R.drawable.ic_lantern_on else R.drawable.ic_lantern_off
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
                if (!hasMeaningfulOcr(raw)) {
                    binding.textView.text = "No se detectó texto legible. Intente nuevamente."
                    speak("No se detectó texto legible. Vuelva a enfocar y presione Detectar.", UTT_INIT)
                    lastOcrText = ""
                } else {
                    sttStructuralFailCount = 0
                    sttSemanticFailCount = 0
                    binding.textView.text = "Texto detectado. Diga a continuación: El medicamento se llama..., seguido del nombre."
                    speak("Texto detectado. Diga a continuación: El medicamento se llama, seguido del nombre.", UTT_PROMPT)
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

    // ---- TTS ----
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
                        binding.textView.post { startHybridListening() }
                    }
                }
                override fun onError(utteranceId: String?) {}
            })
            speak("Modo AÑADIR MEDICAMENTO. Coloque la caja frente a la cámara y presione Detectar.", UTT_INIT)
        } else Log.e(TAG, "Error al inicializar TTS")
    }

    private fun speak(text: String, id: String) {
        val params = Bundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    // ---- Híbrido con forzado y logging explícito ----
    private fun startHybridListening() {
        if (lastOcrText.isBlank()) return
        attemptId++

        val chosen = when (forcedEngine) {
            Engine.GOOGLE -> Engine.GOOGLE
            Engine.VOSK -> Engine.VOSK
            Engine.AUTO -> if (hasInternet()) Engine.GOOGLE else Engine.VOSK
        }
        currentEngine = chosen

        // Mostrar solo “Escuchando...”
        runOnUiThread {
            binding.textView.text = "Escuchando..."
        }

        when (chosen) {
            Engine.GOOGLE -> startGoogleListening()
            Engine.VOSK -> startVoskListening()
            else -> {} // por completitud (para cubrir el caso AUTO)
        }
    }

    private fun hasInternet(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val nw = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(nw) ?: return false
        return (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---- Google STT ----
    private fun startGoogleListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Google STT no disponible -> fallback Vosk [attempt#$attemptId]")
            startVoskListening(); return
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
                    override fun onReadyForSpeech(params: Bundle?) { Log.d(STT_TAG, "onReadyForSpeech [attempt#$attemptId]") }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        Log.w(TAG, "Google STT error=$error [attempt#$attemptId]")
                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            try { stt?.cancel(); stt?.destroy() } catch (_: Exception) {}
                            stt = null
                            binding.textView.postDelayed({ if (lastOcrText.isNotBlank()) startGoogleListening() }, 300)
                            return
                        }
                        if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            handleStructuralFailAndMaybeStop(); return
                        }
                        // otros errores → reintentar con Vosk (log explícito)
                        Log.w(TAG, "FALLBACK → VOSK por error Google($error) [attempt#$attemptId]")
                        startVoskListening()
                    }

                    override fun onResults(results: Bundle) {
                        val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        var said = matches?.firstOrNull()?.trim().orEmpty()
                        if (said.isEmpty() && !lastPartialSaid.isNullOrBlank()) {
                            val lp = lastPartialSaid!!.trim()
                            if (lp.contains("llama", true) || lp.contains("yama", true) || lp.contains("shama", true)) {
                                said = lp
                            }
                        }
                        Log.d(STT_TAG, "🎤 Google final='$said' [attempt#$attemptId]")

                        if (said.isEmpty()) { handleStructuralFailAndMaybeStop(); return }
                        val m = TRIGGER_REGEX.find(said) ?: run { handleStructuralFailAndMaybeStop(); return }
                        val nameRaw = m.groupValues[1].trim()
                        if (nameRaw.isEmpty()) { handleStructuralFailAndMaybeStop(); return }
                        handleSpokenName(nameRaw)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        lastPartialSaid = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                        lastPartialSaid?.let {
                            Log.d(STT_TAG, "Google partial='$it' [attempt#$attemptId]")
                        }
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

        // Doble beep para Google
        beep(count = 2)
        binding.textView.postDelayed({
            try {
                lastPartialSaid = null
                stt?.startListening(intent)
                binding.textView.text = "Escuchando... [Engine: Google]"
            } catch (e: Exception) {
                Log.e(TAG, "Error startListening Google [attempt#$attemptId]", e)
                startVoskListening()
            }
        }, 130)
    }

    // ---- Vosk STT ----
    private fun startVoskListening() {
        if (lastOcrText.isBlank()) return
        if (!voskReady) {
            binding.textView.text = "Preparando modelo offline..."
            Log.d(TAG, "Vosk aún no listo [attempt#$attemptId]")
            return
        }

        try { vosk?.stop() } catch (_: Throwable) {}
        vosk?.setGrammar(null)
        Log.d(TAG, "🔊 Vosk en modo libre (sin gramática). [attempt#$attemptId]")

        voskLastPartial = null
        triggerHeard = false
        waitingForVosk = true

        // Beep simple para Vosk
        beep(count = 1)
        binding.textView.postDelayed({
            vosk?.start()
            binding.textView.text = "Escuchando... [Engine: Vosk]"
            scheduleListenTimeout(PRE_TRIGGER_MAX_MS)
        }, 130)
    }

    private fun handleVoskPartialForTrigger(text: String) {
        if (!waitingForVosk) return
        val lower = text.lowercase(Locale("es", "AR"))
        val heard = TRIGGER_WORDS.any { lower.contains(it) }
        if (!triggerHeard && heard) {
            triggerHeard = true
            Log.d(TAG, "🔔 Detectado trigger en parcial. Ventana cola ${POST_TRIGGER_TAIL_MS}ms [attempt#$attemptId]")
            scheduleListenTimeout(POST_TRIGGER_TAIL_MS)
        } else if (triggerHeard) {
            scheduleListenTimeout(POST_TRIGGER_TAIL_MS)
        }
    }

    private fun handleVoskRecognizedNow(finalText: String) {
        val raw = finalText.trim().ifEmpty { voskLastPartial?.trim().orEmpty() }
        Log.d(TAG, "🎤 Texto reconocido (procesado ahora): '$raw' [attempt#$attemptId]")
        if (raw.isEmpty()) { handleStructuralFailAndMaybeStop(); return }

        TRIGGER_REGEX.find(raw)?.let { m ->
            val nameRaw = m.groupValues[1].trim()
            Log.d(TAG, "✅ Trigger+nombre: '$nameRaw' [attempt#$attemptId]")
            if (nameRaw.isNotEmpty()) { acceptNameFromVosk(nameRaw); return }
        }

        if (triggerHeard) {
            val tail = extractTailAfterTrigger(voskLastPartial.orEmpty())
            Log.d(TAG, "🔎 Cola post-trigger: '$tail' [attempt#$attemptId]")
            if (tail.isNotBlank()) { acceptNameFromVosk(tail); return }
        }

        handleStructuralFailAndMaybeStop()
    }

    private fun extractTailAfterTrigger(s: String): String {
        val lower = s.lowercase(Locale("es", "AR"))
        val idx = TRIGGER_WORDS
            .mapNotNull { w -> lower.indexOf(w).takeIf { it >= 0 }?.let { it to w } }
            .minByOrNull { it.first } ?: return ""
        val start = idx.first + idx.second.length
        return s.substring(start).trim()
    }

    private fun acceptNameFromVosk(nameRaw: String) {
        cancelListenTimeout()
        waitingForVosk = false
        try { vosk?.stop() } catch (_: Throwable) {}
        handleSpokenName(nameRaw)
    }

    // ---- Timeouts Vosk ----
    private fun scheduleListenTimeout(ms: Long) {
        cancelListenTimeout()
        listenTimeout = Runnable {
            Log.d(TAG, if (triggerHeard)
                "⏱ Timeout cola: procesamos último parcial [attempt#$attemptId]"
            else
                "⏱ Timeout trigger: no se detectó 'se llama' [attempt#$attemptId]"
            )
            handleVoskRecognizedNow(voskLastPartial.orEmpty())
        }
        binding.textView.postDelayed(listenTimeout!!, ms)
    }

    private fun cancelListenTimeout() {
        listenTimeout?.let { binding.textView.removeCallbacks(it) }
        listenTimeout = null
    }

    // ---- Beep/Haptic (1 = Vosk, 2 = Google) ----
    private fun beep(count: Int) {
        Haptics.click(this, null)
        val tg = try {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 75)
        } catch (_: Throwable) {
            try { android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 75) }
            catch (_: Throwable) { null }
        } ?: return

        val handler = Handler(Looper.getMainLooper())
        repeat(count) { i ->
            handler.postDelayed({
                try { tg.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 80) } catch (_: Throwable) {}
            }, (i * 120).toLong())
        }
    }

    // ---- Guardado ----
    private fun handleStructuralFailAndMaybeStop() {
        sttStructuralFailCount++
        try { stt?.cancel() } catch (_: Exception) {}
        try { vosk?.stop() } catch (_: Exception) {}
        if (sttStructuralFailCount >= MAX_STRUCTURAL_FAILS) {
            forceRetakePhoto(); return
        }
        binding.textView.text = "Por favor, diga: El medicamento se llama... y el nombre."
        speak("Por favor, diga: El medicamento se llama, seguido del nombre.", UTT_PROMPT)
    }

    private fun handleSemanticFailAndMaybeStop() {
        sttSemanticFailCount++
        if (sttSemanticFailCount >= MAX_SEMANTIC_FAILS) {
            forceRetakePhoto(); return
        }
        binding.textView.text = "Lo indicado no coincide con el texto detectado. Intente nuevamente."
        speak("Lo indicado no coincide con el texto detectado. Intente nuevamente.", UTT_PROMPT)
    }

    private fun forceRetakePhoto() {
        try { stt?.cancel() } catch (_: Exception) {}
        try { vosk?.stop() } catch (_: Exception) {}
        lastOcrText = ""
        sttStructuralFailCount = 0
        sttSemanticFailCount = 0
        waitingForVosk = false
        triggerHeard = false
        binding.textView.text = "Varios intentos fallidos. Presione Detectar para volver a tomar la foto."
        speak("Se detectaron varios intentos fallidos. Por favor, presione el botón Detectar para volver a tomar la foto.", UTT_INIT)
    }

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
            val fm = ocrMatchesSpokenSafe(spoken, lastOcrText)
            if (!fm.ok) { runOnUiThread { handleSemanticFailAndMaybeStop() }; return@launch }

            sttStructuralFailCount = 0; sttSemanticFailCount = 0
            val ocrToken = fm.ocrToken ?: spoken
            val ocrDbName = repo.findBestDrugName(ocrToken)
            val toInsert = ocrDbName ?: toTitleCaseEs(ocrToken)

            val dao = AppDatabase.get(this@AddDrugActivity).medicineDao()
            dao.insertDrugs(listOf(Drug(name = toInsert, normalized = normalizeLettersOnly(toInsert))))
            runOnUiThread {
                val msg = "Medicamento añadido: $toInsert"
                binding.textView.text = msg
                speak(msg, UTT_INIT)
            }
        }
    }

    // ---- Matching seguro (solo OCR tokens) ----
    private data class OcrFuzzy(val ok: Boolean, val ocrToken: String?, val score: Float)

    private fun ocrMatchesSpokenSafe(spoken: String, ocrText: String): OcrFuzzy {
        val candidates = extractMainWords(ocrText)
        if (candidates.isEmpty()) return OcrFuzzy(false, null, 0f)

        val phonBest = bestPhoneticCandidate(spoken, candidates)
        if (phonBest != null && phonBest.second >= 0.79f) {
            return OcrFuzzy(true, phonBest.first, phonBest.second)
        }

        val sNorm = normalizeLettersOnly(spoken)
        var bestTok: String? = null
        var bestScore = 0f
        for (t in candidates) {
            val sc = levenshteinSimilarity(normalizeLettersOnly(t), sNorm)
            if (sc > bestScore) { bestScore = sc; bestTok = t }
        }
        return if (bestTok != null && bestScore >= 0.78f) OcrFuzzy(true, bestTok, bestScore)
        else OcrFuzzy(false, null, bestScore)
    }

    // ---- Fonética ES ----
    private fun spanishPhoneticKey(s: String): String {
        var t = Normalizer.normalize(s.lowercase(Locale("es","AR")), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
        t = t.replace("ph", "f")
            .replace("qu", "k")
            .replace("ci", "si").replace("ce", "se").replace("cy", "si")
            .replace("z", "s")
            .replace("v", "b")
            .replace("ll", "y")
            .replace("gue", "ge").replace("gui", "gi")
            .replace("ge", "je").replace("gi", "ji")
            .replace("ch", "ch")
            .replace("h", "")
        t = t.replace("y$".toRegex(), "i")
        t = t.replace("[^a-z]".toRegex(), "")
        t = t.replace("(.)\\1+".toRegex(), "$1")
        return t
    }

    private fun bestPhoneticCandidate(spoken: String, candidates: List<String>): Pair<String, Float>? {
        val keySp = spanishPhoneticKey(spoken)
        var best: String? = null
        var bestSim = 0f
        for (cand in candidates) {
            val sim = levenshteinSimilarity(spanishPhoneticKey(cand), keySp)
            if (sim > bestSim) { bestSim = sim; best = cand }
        }
        return if (best != null) best!! to bestSim else null
    }

    // ---- Utils ----
    private fun hasMeaningfulOcr(raw: String): Boolean {
        val norm = normalizeLettersOnly(raw)
        if (norm.isBlank()) return false
        val toks = norm.split(" ").filter { it.any { ch -> ch.isLetterOrDigit() } }
        val chars = toks.sumOf { it.count { ch -> ch.isLetterOrDigit() } }
        return toks.size >= 2 && chars >= 6
    }

    private fun extractMainWords(ocr: String): List<String> {
        val norm = normalizeLettersOnly(ocr)
        return norm.split(" ")
            .filter { it.length >= 4 && it.any { ch -> ch.isLetter() } }
            .take(30)
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

    private fun toTitleCaseEs(s: String): String =
        s.lowercase(Locale("es", "AR")).split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale("es", "AR")) } }

    private fun normalizeLettersOnly(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("[^\\p{L}\\p{Nd}\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .uppercase()

    private fun allPermissionsGranted(): Boolean =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    companion object {
        private const val TAG = "AddDrugActivity"
        private const val STT_TAG = "STT_RESULT"
        private const val REQUEST_CODE_PERMISSIONS = 11
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}
