package com.medscan.medscan

import android.Manifest
import android.content.pm.PackageManager
import android.os.*
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
import kotlin.math.abs
import kotlin.math.max

@ExperimentalGetImage
class AddDrugActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityAddDrugBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null

    // ML Kit OCR
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    // TTS
    private lateinit var tts: TextToSpeech

    // Repo
    private lateinit var repo: MedicineRepository

    // OCR & STT estado
    private var lastOcrText: String = ""

    // Contadores (igual que original)
    private var sttStructuralFailCount = 0
    private val MAX_STRUCTURAL_FAILS = 3
    private var sttSemanticFailCount = 0
    private val MAX_SEMANTIC_FAILS = 3

    private val UTT_INIT   = "UTT_INIT"
    private val UTT_PROMPT = "UTT_PROMPT"

    // Trigger: “(el) (medicamento[s]) se (llama|yama|shama) …”
    private val TRIGGER_REGEX = Regex(
        """(?:^|\s)(?:el\s+)?(?:medicamento(?:s)?\s+)?se\s+(?:llama|yama|shama)\s+(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val TRIGGER_WORDS = listOf("se llama", "se yama", "se shama")

    // ---- Vosk (modo libre) ----
    private var vosk: VoskMenuRecognizer? = null
    private var voskReady = false
    private var waitingForSpeech = false
    private var lastPartial: String? = null
    private var listenTimeout: Runnable? = null
    private var triggerHeard = false

    // Ventanas de escucha
    private val PRE_TRIGGER_MAX_MS = 7000L   // tiempo máx. para oír “se llama…”
    private val POST_TRIGGER_TAIL_MS = 2500L // tiempo para capturar el nombre tras el trigger

    companion object {
        private const val TAG = "AddDrugActivity"
        private const val REQUEST_CODE_PERMISSIONS = 11
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    // ---------------- Ciclo ----------------
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

        // Prepara Vosk una sola vez
        vosk = VoskMenuRecognizer(this, object : VoskMenuRecognizer.Callbacks {
            override fun onReady() { voskReady = true }
            override fun onListening() { runOnUiThread { binding.textView.text = "Escuchando..." } }
            override fun onPartial(text: String) {
                lastPartial = text
                Log.d(TAG, "VOSK partial=$text")
                handlePartialForTrigger(text)
            }
            override fun onResult(text: String) {
                Log.d(TAG, "VOSK final=$text")
                if (!waitingForSpeech) return
                handleRecognizedNow(text)
            }
            override fun onError(msg: String) {
                runOnUiThread { binding.textView.text = "Error: $msg" }
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
        listenTimeout?.let { binding.textView.removeCallbacks(it) }
        stopAudio()
    }

    // ---------- Helpers de normalización ----------
    private fun normalizeLettersOnly(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("[^\\p{L}\\p{Nd}\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .uppercase()

    private fun compact(s: String): String =
        normalizeLettersOnly(s)
            .replace(" ", "")
            .replace(Regex("(.)\\1+"), "$1") // colapsa letras repetidas

    private fun stripNoiseWords(s: String): String {
        val stops = setOf(
            "EL","LA","LOS","LAS","DE","DEL","AL","A","Y",
            "UN","UNA","UNOS","UNAS","PARA","POR","CON",
            "ELLA","ELLO","ESO","ESTE","ESTA","HAY","HOY"
        )
        val parts = normalizeLettersOnly(s).split(" ")
        val filtered = parts.filter { it.isNotBlank() && it !in stops }
        return filtered.joinToString(" ")
    }

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
        t = t.replace("(.)\\1+".toRegex(), "$1") // colapsa dobles
        return t
    }

    private fun stopAudio() {
        try { vosk?.stop() } catch (_: Throwable) {}
        if (::tts.isInitialized) { try { tts.stop() } catch (_: Throwable) {} }
        waitingForSpeech = false
        triggerHeard = false
        cancelListenTimeout()
    }

    // ---------- Cámara ----------
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

    // ---------- OCR ----------
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
                    waitingForSpeech = false
                } else {
                    sttStructuralFailCount = 0
                    sttSemanticFailCount = 0
                    binding.textView.text = "Texto detectado. Diga: El medicamento se llama..."
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
                waitingForSpeech = false
            }
            .addOnCompleteListener {
                imageProxy.close()
                imageAnalysis?.clearAnalyzer()
            }
    }

    // ---------- TTS ----------
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
                    if (utteranceId == UTT_PROMPT) binding.textView.post { startFreeListening() }
                }
                override fun onError(utteranceId: String?) {}
            })
            speak("Enfoque la caja y presione Detectar. Luego diga: El medicamento se llama...", UTT_INIT)
        } else Log.e(TAG, "Error al inicializar TTS")
    }

    private fun speak(text: String, id: String) {
        val params = Bundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    // ---------- Vosk: modo libre, sin reinicios en medio de la frase ----------
    private fun startFreeListening() {
        if (lastOcrText.isBlank()) return
        if (!voskReady) { binding.textView.text = "Preparando modelo offline..."; return }

        vosk?.stop()
        vosk?.setGrammar(null) // libre
        Log.d(TAG, "🔊 Vosk en modo libre (sin gramática).")

        lastPartial = null
        triggerHeard = false
        waitingForSpeech = true
        beepAndHaptic()
        vosk?.start()
        binding.textView.text = "Escuchando..."

        scheduleListenTimeout(PRE_TRIGGER_MAX_MS)
    }

    private fun handlePartialForTrigger(text: String) {
        if (!waitingForSpeech) return
        val t = text.lowercase(Locale("es", "AR"))
        val heardTrigger = TRIGGER_WORDS.any { t.contains(it) }
        if (!triggerHeard && heardTrigger) {
            triggerHeard = true
            Log.d(TAG, "🔔 Detectado trigger en parcial. Abriendo ventana de cola ${POST_TRIGGER_TAIL_MS}ms")
            scheduleListenTimeout(POST_TRIGGER_TAIL_MS)
        } else if (triggerHeard) {
            // mientras siguen llegando parciales tras el trigger, movemos la ventana
            scheduleListenTimeout(POST_TRIGGER_TAIL_MS)
        }
    }

    private fun handleRecognizedNow(finalText: String) {
        val raw = finalText.trim().ifEmpty { lastPartial?.trim().orEmpty() }
        Log.d(TAG, "🎤 Texto reconocido (procesado ahora): '$raw'")
        if (raw.isEmpty()) { handleStructuralFailAndMaybeStop(); return }

        // 1) ¿“se llama <nombre>” en la misma frase?
        TRIGGER_REGEX.find(raw)?.let { m ->
            val nameRaw = m.groupValues[1].trim()
            Log.d(TAG, "✅ Trigger+nombre en una sola frase: '$nameRaw'")
            if (nameRaw.isNotEmpty()) { acceptName(nameRaw); return }
        }

        // 2) Si ya hubo trigger, probamos extraer “cola” del último parcial
        if (triggerHeard) {
            val from = lastPartial.orEmpty()
            val tail = extractTailAfterTrigger(from)
            Log.d(TAG, "🔎 Cola después del trigger (parcial): '$tail'")
            if (tail.isNotBlank()) { acceptName(tail); return }
        }

        // 3) Fallback: ¿mencionó solo el nombre (algún token del OCR) sin trigger?
        val norm = normalizeLettersOnly(raw)
        val tokens = extractMainWords(lastOcrText)
        val hits = tokens.filter { norm.contains(normalizeLettersOnly(it)) }
        Log.d(TAG, "📄 OCR tokens = $tokens")
        Log.d(TAG, "📊 Hits coincidentes = $hits")
        when {
            hits.size == 1 -> { acceptName(hits.first()); return }
            hits.size > 1  -> { handleStructuralFailAndMaybeStop(); return }
            else           -> { handleStructuralFailAndMaybeStop(); return }
        }
    }

    private fun extractTailAfterTrigger(s: String): String {
        val lower = s.lowercase(Locale("es", "AR"))
        val idx = TRIGGER_WORDS
            .mapNotNull { w -> lower.indexOf(w).takeIf { it >= 0 }?.let { it to w } }
            .minByOrNull { it.first } ?: return ""
        val start = idx.first + idx.second.length
        return s.substring(start).trim()
    }

    private fun acceptName(nameRaw: String) {
        cancelListenTimeout()
        waitingForSpeech = false
        try { vosk?.stop() } catch (_: Throwable) {}

        Log.d(TAG, "🟢 Nombre a validar: '$nameRaw'")
        handleSpokenName(nameRaw)
    }

    // ---------- Timeouts ----------
    private fun scheduleListenTimeout(ms: Long) {
        cancelListenTimeout()
        listenTimeout = Runnable {
            Log.d(TAG, if (triggerHeard)
                "⏱ Timeout de cola: procesamos con último parcial"
            else
                "⏱ Timeout TRIGGER: no se detectó 'se llama'"
            )
            handleRecognizedNow(lastPartial.orEmpty())
        }
        binding.textView.postDelayed(listenTimeout!!, ms)
    }

    private fun cancelListenTimeout() {
        listenTimeout?.let { binding.textView.removeCallbacks(it) }
        listenTimeout = null
    }

    // ---------- Beep/haptic ----------
    private fun beepAndHaptic() {
        Haptics.click(this, null)
        try {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70)
                .startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 90)
        } catch (_: Throwable) {}
    }

    // ---------- Flujo de guardado ----------
    private fun handleStructuralFailAndMaybeStop() {
        sttStructuralFailCount++
        waitingForSpeech = false
        try { vosk?.stop() } catch (_: Exception) {}
        if (sttStructuralFailCount >= MAX_STRUCTURAL_FAILS) {
            forceRetakePhoto(); return
        }
        binding.textView.text = "Por favor, diga: El medicamento se llama... y el nombre."
        speak("Por favor, diga: El medicamento se llama, seguido del nombre.", UTT_PROMPT)
    }

    private fun handleSemanticFailAndMaybeStop() {
        sttSemanticFailCount++
        waitingForSpeech = false
        try { vosk?.stop() } catch (_: Exception) {}
        if (sttSemanticFailCount >= MAX_SEMANTIC_FAILS) {
            forceRetakePhoto(); return
        }
        binding.textView.text = "Lo indicado no coincide con el texto detectado. Intente nuevamente."
        speak("Lo indicado no coincide con el texto detectado. Intente nuevamente.", UTT_PROMPT)
    }

    private fun forceRetakePhoto() {
        try { vosk?.stop() } catch (_: Exception) {}
        lastOcrText = ""
        sttStructuralFailCount = 0
        sttSemanticFailCount = 0
        waitingForSpeech = false
        triggerHeard = false
        binding.textView.text =
            "Varios intentos fallidos. Presione Detectar para volver a tomar la foto."
        speak(
            "Se detectaron varios intentos fallidos. Por favor, presione el botón Detectar para volver a tomar la foto.",
            UTT_INIT
        )
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
            // Limpiamos muletillas y quedamos con lo sustantivo
            val spokenClean = stripNoiseWords(nameRaw).trim().ifEmpty { nameRaw.trim() }

            // Candidatos solo del OCR (seguro)
            val candidates = extractMainWords(lastOcrText)
            Log.d(TAG, "🔐 Candidatos OCR: $candidates")

            // 1) Fuzzy seguro contra candidatos del OCR (con margen)
            val fuzzy = bestSafeFuzzy(spokenClean, candidates)
            if (fuzzy != null) {
                Log.d(TAG, "✅ Fuzzy => ${fuzzy.first} (edit=${"%.2f".format(fuzzy.second)}, phon=${"%.2f".format(fuzzy.third)})")
                saveDrugAndAnnounce(fuzzy.first)
                return@launch
            } else {
                Log.d(TAG, "❌ Fuzzy no alcanzó umbral. Probamos OCR-wide.")
            }

            // 2) Fallback: fuzzy contra TODO el texto OCR tokenizado
            val fm = ocrMatchesSpoken(spokenClean, lastOcrText)
            if (!fm.ok) { runOnUiThread { handleSemanticFailAndMaybeStop() }; return@launch }
            val ocrToken = fm.ocrToken ?: spokenClean
            saveDrugAndAnnounce(ocrToken)
        }
    }

    private fun saveDrugAndAnnounce(token: String) {
        val cleaned = token.trim()
        val ctx = applicationContext
        val dao = AppDatabase.get(ctx).medicineDao()

        lifecycleScope.launch(Dispatchers.IO) {
            val ocrDbName = repo.findBestDrugName(cleaned)
            val toInsert = ocrDbName ?: toTitleCaseEs(cleaned)

            dao.insertDrugs(
                listOf(Drug(name = toInsert, normalized = normalizeLettersOnly(toInsert)))
            )

            runOnUiThread {
                val msg = "Medicamento añadido: $toInsert"
                binding.textView.text = msg
                speak(msg, UTT_INIT)
            }
        }
    }

    // ---------- Fuzzy seguro (sin whitelist, solo OCR) ----------
    private fun bestSafeFuzzy(spoken: String, candidates: List<String>): Triple<String, Float, Float>? {
        val spEdit = compact(spoken)
        val spPhon = spanishPhoneticKey(spoken)

        data class Sc(val cand: String, val edit: Float, val phon: Float, val score: Float)

        val scored = candidates.map { c ->
            val ce = compact(c)
            val cp = spanishPhoneticKey(c)
            val edit = levenshteinSimilarity(spEdit, ce)
            val phon = levenshteinSimilarity(spPhon, cp)
            Sc(c, edit, phon, max(edit, phon))
        }.sortedByDescending { it.score }

        // Log top-K
        val topK = scored.take(5).joinToString { "${it.cand}(e=${"%.2f".format(it.edit)},p=${"%.2f".format(it.phon)})" }
        Log.d(TAG, "🔎 FuzzyTop: $topK")

        val top = scored.getOrNull(0) ?: return null
        val second = scored.getOrNull(1)

        // Umbral duro
        val PHONETIC_ACCEPT = 0.72f
        val EDIT_ACCEPT = 0.72f
        if (top.edit >= EDIT_ACCEPT || top.phon >= PHONETIC_ACCEPT) {
            Log.d(TAG, "✅ Fuzzy OK (umbral duro) => ${top.cand}")
            return Triple(top.cand, top.edit, top.phon)
        }

        // Aceptación por margen (top claramente mejor que el segundo)
        val MIN_TOP = 0.56f
        val MIN_GAP = 0.22f
        val gap = if (second != null) top.score - second.score else top.score
        if (top.score >= MIN_TOP && gap >= MIN_GAP) {
            Log.d(TAG, "✅ Fuzzy OK (margen): top=${"%.2f".format(top.score)} gap=${"%.2f".format(gap)} => ${top.cand}")
            return Triple(top.cand, top.edit, top.phon)
        }

        Log.d(TAG, "❌ Fuzzy no alcanzó umbral ni margen (top=${"%.2f".format(top.score)}, gap=${"%.2f".format(gap)})")
        return null
    }

    // ---------- Utils ----------
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
            .distinct()
            .take(30)
    }

    private data class OcrFuzzy(val ok: Boolean, val ocrToken: String?, val score: Float)

    private fun ocrMatchesSpoken(spoken: String, ocrText: String, threshold: Float = 0.74f): OcrFuzzy {
        val sNorm = compact(spoken)
        val oNorm = compact(ocrText)
        if (sNorm.isBlank() || oNorm.isBlank()) return OcrFuzzy(false, null, 0f)

        data class Tok(val orig: String, val norm: String)
        val tokens = ocrText.split(Regex("\\s+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
            .map { Tok(it, compact(it)) }

        if (oNorm.contains(sNorm)) return OcrFuzzy(true, sNorm, 1f)

        var bestSim = 0f; var best: Tok? = null
        for (t in tokens) {
            if (t.norm.length < 3) continue
            val sim = levenshteinSimilarity(t.norm, sNorm)
            if (sim > bestSim) { bestSim = sim; best = t }
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

    private fun toTitleCaseEs(s: String): String =
        s.lowercase(Locale("es", "AR")).split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale("es", "AR")) } }

    private fun allPermissionsGranted(): Boolean =
        REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
}
