package com.medscan.medscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Bundle as AndroidBundle
import android.os.SystemClock
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
import com.medscan.medscan.databinding.ActivityMainBinding
import com.medscan.medscan.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

@ExperimentalGetImage
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private lateinit var tts: TextToSpeech
    private lateinit var repo: MedicineRepository

    // ---- Bienvenida / retorno desde AddDrug ----
    private var hasWelcomed = false
    private val STATE_WELCOMED = "state_welcomed"

    private val PREFS_NAME = "medscan_prefs"
    private val KEY_PENDING_POST_ADD = "pending_post_add"

    private val WELCOME_FULL =
        "Bienvenido a MedScan. Coloque la caja del medicamento frente a la cámara y presione Detectar"
    private val WELCOME_SHORT =
        "Modo DETECCIÓN. Coloque la caja del medicamento frente a la cámara y presione Detectar"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restaurar si ya se dio la bienvenida en esta instancia (evita repetir por rotación)
        hasWelcomed = savedInstanceState?.getBoolean(STATE_WELCOMED) ?: false

        repo = MedicineRepository(this)
        tts = TextToSpeech(this, this)

        // ---- PREWARM OCR: carga modelo on-device para evitar cold start ----
        prewarmOcr()

        // ---- Espera de DB: deshabilitar "Detectar" hasta que se inserten fármacos ----
        binding.detectionButton.isEnabled = false
        binding.detectionButton.alpha = 0.5f
        lifecycleScope.launch {
            waitDbReady()
            binding.detectionButton.isEnabled = true
            binding.detectionButton.alpha = 1f
        }

        // Ir a AddDrug
        binding.addDrugButton.setOnClickListener { v ->
            Haptics.navForward(this, v)
            if (::tts.isInitialized) tts.stop()
            // marcar que, al volver desde AddDrug, debemos reproducir el mensaje corto
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING_POST_ADD, true)
                .apply()
            startActivity(Intent(this, AddDrugActivity::class.java))
        }

        // Permisos
        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Botón de ayuda
        binding.helpButton.setOnClickListener { v ->
            Haptics.navForward(this, v)
            startActivity(Intent(this, HelpActivity::class.java))
        }

        // Detectar
        binding.detectionButton.setOnClickListener { v ->
            Haptics.detect(this, v)
            imageAnalysis?.setAnalyzer(cameraExecutor) { img -> processImage(img) }
        }

        // Linterna
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
    }

    override fun onPause() {
        super.onPause()
        if (::tts.isInitialized) {
            tts.stop()
        }
    }

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
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                lifecycleScope.launch {
                    // ===== Paso 0: recolectar líneas OCR =====
                    data class LineInfo(val text: String, val rect: android.graphics.Rect)
                    val rawLines = mutableListOf<LineInfo>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            line.boundingBox?.let { rawLines.add(LineInfo(line.text, it)) }
                        }
                    }
                    rawLines.sortBy { it.rect.top }

                    // ===== Helpers geométricos =====
                    fun horizontalOverlap(a: android.graphics.Rect, b: android.graphics.Rect): Int {
                        val left = maxOf(a.left, b.left)
                        val right = minOf(a.right, b.right)
                        return (right - left).coerceAtLeast(0)
                    }
                    fun verticalCenterDistance(a: android.graphics.Rect, b: android.graphics.Rect): Int {
                        val ay = (a.top + a.bottom) / 2
                        val by = (b.top + b.bottom) / 2
                        return kotlin.math.abs(ay - by)
                    }

                    // ===== Buscar dosis vecina (con control de reutilización) =====
                    fun findNearbyDoseFor(
                        base: LineInfo,
                        lines: List<LineInfo>,
                        usedDoseRects: MutableSet<android.graphics.Rect>
                    ): Pair<String, android.graphics.Rect>? {
                        val baseHeight = base.rect.height()
                        val maxDy = (baseHeight * 0.9f).toInt()
                        var best: Pair<String, android.graphics.Rect>? = null
                        var bestScore = Int.MAX_VALUE
                        for (other in lines) {
                            if (other === base) continue
                            val dy = verticalCenterDistance(base.rect, other.rect)
                            if (dy > maxDy) continue
                            val otherIsBelow = other.rect.centerY() >= base.rect.centerY() || dy < (baseHeight * 0.25f)
                            if (!otherIsBelow) continue
                            val overlap = horizontalOverlap(base.rect, other.rect)
                            val minOverlap = (minOf(base.rect.width(), other.rect.width()) * 0.5f).toInt()
                            if (overlap < minOverlap) continue
                            if (usedDoseRects.any { it == other.rect }) continue
                            val doses = repo.extractDosesPublic(other.text)
                            if (doses.isEmpty()) continue
                            val candidate = doses.first()
                            if (dy < bestScore) { best = candidate to other.rect; bestScore = dy }
                        }
                        return best
                    }

                    // ===== Paso 1: anotar cada línea con (nombre, dosis) =====
                    data class Annot(val line: LineInfo, val drugName: String?, val doses: List<String>)
                    val annotated: List<Annot> = rawLines.map { li ->
                        val dn = repo.findBestDrugName(li.text)
                        val ds = repo.extractDosesPublic(li.text)
                        Annot(li, dn, ds)
                    }

                    val results = LinkedHashMap<String, String?>()
                    val usedDoseRects = mutableSetOf<android.graphics.Rect>()

                    fun hasKeyCI(map: Map<String, *>, key: String) =
                        map.keys.any { it.equals(key, ignoreCase = true) }

                    // Cantidad REAL de líneas que tienen dosis (cota para no inventar más)
                    val totalDoseRects = annotated.count { it.doses.isNotEmpty() }

                    // ===== Paso 2 (PRIORIDAD): nombre + dosis EN LA MISMA LÍNEA =====
                    for (a in annotated) {
                        val dn = a.drugName ?: continue
                        if (hasKeyCI(results, dn)) continue
                        if (a.doses.isNotEmpty()) {
                            results[dn] = a.doses.first()
                            usedDoseRects.add(a.line.rect) // esta línea ya aportó su dosis
                        }
                    }

                    // ===== Paso 3: nombres SIN dosis propia → buscar dosis CERCANA disponible =====
                    for (a in annotated) {
                        val dn = a.drugName ?: continue
                        if (hasKeyCI(results, dn)) continue

                        // Si ya usé todas las líneas con dosis, no asigno nada (evita “inventar”)
                        if (usedDoseRects.size >= totalDoseRects) {
                            results[dn] = null
                            continue
                        }

                        val near = findNearbyDoseFor(a.line, rawLines, usedDoseRects)
                        results[dn] = near?.first
                        near?.second?.let { usedDoseRects.add(it) }
                    }

                    // ===== Salida =====
                    if (results.isNotEmpty()) {
                        val spoken = results.entries.joinToString("\n") { (drug, dose) ->
                            if (dose != null) "$drug $dose" else drug
                        }
                        binding.textView.text = spoken
                        speakOut(spoken)
                    } else {
                        binding.textView.text = "No se encontró coincidencia"
                        speakOut("Intente nuevamente")
                    }
                }
            }
            .addOnFailureListener {
                binding.textView.text = "Error al detectar texto"
                speakOut("Error al detectar texto")
            }
            .addOnCompleteListener {
                imageProxy.close()
                imageAnalysis?.clearAnalyzer()
            }
    }

    private fun speakOut(text: String) {
        val params = AndroidBundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "UTTERANCE_ID")
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera()
            else { Toast.makeText(this, "Permisos no concedidos.", Toast.LENGTH_SHORT).show(); finish() }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("es", "AR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Idioma no soportado")
            }
            tts.setSpeechRate(1.10f)
            tts.setPitch(1.0f)

            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {}
            })

            maybeSpeakWelcome()

        } else {
            Log.e(TAG, "Error al inicializar TTS")
        }
    }

    override fun onResume() {
        super.onResume()
        startCamera()
        maybeSpeakReturnedFromAddDrug()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_WELCOMED, hasWelcomed)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
    }

    // ---- Bienvenida / retorno helpers ----
    private fun maybeSpeakWelcome() {
        if (!hasWelcomed && ::tts.isInitialized) {
            speakOut(WELCOME_FULL)
            hasWelcomed = true
        }
    }

    private fun maybeSpeakReturnedFromAddDrug() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val pending = prefs.getBoolean(KEY_PENDING_POST_ADD, false)
        if (pending && ::tts.isInitialized) {
            speakOut(WELCOME_SHORT)
            prefs.edit().putBoolean(KEY_PENDING_POST_ADD, false).apply()
        }
    }

    // ---- Helpers nuevos: PREWARM OCR + esperar DB ----
    private fun prewarmOcr() {
        // Crea un bitmap dummy y ejecuta una pasada del OCR para cargar el modelo en memoria.
        val bmp = android.graphics.Bitmap.createBitmap(320, 320, android.graphics.Bitmap.Config.ARGB_8888)
        val img = InputImage.fromBitmap(bmp, 0)
        recognizer.process(img).addOnCompleteListener {
            // No usamos el resultado; solo evitamos el "cold start" en la primera detección real.
        }
    }

    private suspend fun waitDbReady(timeoutMs: Long = 6000) {
        val db = AppDatabase.get(applicationContext)
        val t0 = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
            val ready = withContext(Dispatchers.IO) {
                db.medicineDao().getAllDrugs().isNotEmpty()
            }
            if (ready) return
            delay(150)
        }
        Log.w(TAG, "Tiempo de espera de DB agotado; habilitando detección de todos modos.")
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
}
