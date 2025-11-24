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

// Reconocedor OCR on-device (ML Kit). Se instancia 1 sola vez a nivel de archivo.
val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

/**
 * MainActivity:
 * - Orquesta la UI principal y flujo de detección.
 * - Pipeline: CameraX -> ML Kit OCR -> Heurística nombre/dosis -> TTS.
 * - Incluye:
 *   * Pre-warm del OCR (evita cold start).
 *   * Espera de DB para evitar que la primera detección falle por tabla vacía.
 *   * Emparejado en 2 pasadas (misma línea primero; luego dosis cercana sin reutilizar rect).
 */

@ExperimentalGetImage
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // --- View binding / cámara / OCR / TTS / repositorio ---
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private lateinit var tts: TextToSpeech
    private lateinit var repo: MedicineRepository

    // --- Estado de bienvenida y retorno desde AddDrug ---
    private var hasWelcomed = false
    private val STATE_WELCOMED = "state_welcomed"

    private val PREFS_NAME = "medscan_prefs"
    private val KEY_PENDING_POST_ADD = "pending_post_add"

    // Mensajes de bienvenida/guía.
    private val WELCOME_FULL =
        "Bienvenido a MedScan. Coloque la caja del medicamento frente a la cámara y presione Detectar"
    private val WELCOME_SHORT =
        "Modo DETECCIÓN. Coloque la caja del medicamento frente a la cámara y presione Detectar"

    // -------------------------------------------------------
    // Ciclo de vida
    // -------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restaurar si ya se dio la bienvenida en esta instancia (evita repetir por rotación).
        hasWelcomed = savedInstanceState?.getBoolean(STATE_WELCOMED) ?: false

        // Dependencias principales (repository y TTS).
        repo = MedicineRepository(this)
        tts = TextToSpeech(this, this)

        // PREWARM OCR: dispara una pasada dummy para cargar el modelo en memoria y evitar el "primer frame" lento.
        prewarmOcr()

        // Espera de DB: deshabilitar "Detectar" hasta que el pre-poblado (assets -> Room) haya insertado filas.
        // Esto evita que la primera detección falle por tabla vacía.
        binding.detectionButton.isEnabled = false
        binding.detectionButton.alpha = 0.5f
        lifecycleScope.launch {
            waitDbReady()
            binding.detectionButton.isEnabled = true
            binding.detectionButton.alpha = 1f
        }

        // --- Navegación a AddDrug ---
        binding.addDrugButton.setOnClickListener { v ->
            Haptics.navForward(this, v)
            if (::tts.isInitialized) tts.stop()
            // Marcar que, al volver desde AddDrug, se lea la guía corta.
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_PENDING_POST_ADD, true)
                .apply()
            startActivity(Intent(this, AddDrugActivity::class.java))
        }

        // --- Permisos de cámara ---
        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        // --- Executor de análisis de imagen (un solo hilo) ---
        cameraExecutor = Executors.newSingleThreadExecutor()

        // --- Ayuda ---
        binding.helpButton.setOnClickListener { v ->
            Haptics.navForward(this, v)
            startActivity(Intent(this, HelpActivity::class.java))
        }

        // --- Detección (single-shot): setea analyzer para un frame; processImage cierra y limpia analyzer al finalizar. ---
        binding.detectionButton.setOnClickListener { v ->
            Haptics.detect(this, v)
            imageAnalysis?.setAnalyzer(cameraExecutor) { img -> processImage(img) }
        }

        // --- Linterna (toggle torch) ---
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

    override fun onResume() {
        super.onResume()
        // Reasegura preview/cámara tras regresar al foreground.
        startCamera()
        // Si venimos de AddDrugActivity, reproducir guía corta y limpiar la flag.
        maybeSpeakReturnedFromAddDrug()
    }

    override fun onPause() {
        super.onPause()
        if (::tts.isInitialized) {
            // Detiene cualquier lectura en curso al pasar a background.
            tts.stop()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Conserva si ya se dio la bienvenida en esta instancia para evitar repetir tras rotación.
        outState.putBoolean(STATE_WELCOMED, hasWelcomed)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Libera recursos de análisis y TTS.
        cameraExecutor.shutdown()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
    }

    // -------------------------------------------------------
    // Cámara (CameraX)
    // -------------------------------------------------------
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()

            // Preview hacia el SurfaceProvider del viewFinder.
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            // ImageAnalysis: resolución objetivo 1280x720; KEEP_ONLY_LATEST para no acumular backpressure.
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val selector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selector, preview, imageAnalysis)

                // Observa estado del flash para actualizar el ícono.
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

    // -------------------------------------------------------
    // OCR + Emparejado nombre/dosis + Ensamblado de salida
    // -------------------------------------------------------
    private fun processImage(imageProxy: ImageProxy) {
        // Obtiene imagen del frame y compone InputImage con la rotación correcta.
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        // Ejecuta OCR (ML Kit). Éxito -> heurística de emparejado; Error -> feedback a usuario.
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                lifecycleScope.launch {
                    // ===== Paso 0: recolectar líneas OCR (texto + bounding box) =====
                    data class LineInfo(val text: String, val rect: android.graphics.Rect)
                    val rawLines = mutableListOf<LineInfo>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            line.boundingBox?.let { rawLines.add(LineInfo(line.text, it)) }
                        }
                    }
                    rawLines.sortBy { it.rect.top } // orden top-down

                    // ===== Helpers geométricos para buscar "dosis cercana" =====
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

                    // ===== Búsqueda de dosis "cercana" (no reutiliza líneas de dosis ya usadas) =====
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

                    // ===== Paso 1: anotación por línea (nombre normalizado + lista de dosis detectadas) =====
                    data class Annot(val line: LineInfo, val drugName: String?, val doses: List<String>)
                    val annotated: List<Annot> = rawLines.map { li ->
                        val dn = repo.findBestDrugName(li.text)    // búsqueda exacta + fuzzy (Levenshtein)
                        val ds = repo.extractDosesPublic(li.text)   // regex robusto de dosis
                        Annot(li, dn, ds)
                    }

                    // Resultados finales y control de "dosis ya asignadas" (para no reutilizar).
                    val results = LinkedHashMap<String, String?>()
                    val usedDoseRects = mutableSetOf<android.graphics.Rect>()

                    fun hasKeyCI(map: Map<String, *>, key: String) =
                        map.keys.any { it.equals(key, ignoreCase = true) }

                    // Cota superior: número real de líneas que contienen una dosis.
                    val totalDoseRects = annotated.count { it.doses.isNotEmpty() }

                    // ===== Paso 2 (PRIORIDAD): nombre + dosis EN LA MISMA LÍNEA =====
                    // Se reserva primero la dosis de cada principio activo que la trae en su propia línea.
                    for (a in annotated) {
                        val dn = a.drugName ?: continue
                        if (hasKeyCI(results, dn)) continue
                        if (a.doses.isNotEmpty()) {
                            results[dn] = a.doses.first()
                            usedDoseRects.add(a.line.rect) // esta línea ya aportó su dosis
                        }
                    }

                    // ===== Paso 3: nombres SIN dosis propia -> buscar dosis CERCANA disponible =====
                    for (a in annotated) {
                        val dn = a.drugName ?: continue
                        if (hasKeyCI(results, dn)) continue

                        // Si ya usé todas las líneas con dosis, no "invento" una extra.
                        if (usedDoseRects.size >= totalDoseRects) {
                            results[dn] = null
                            continue
                        }

                        val near = findNearbyDoseFor(a.line, rawLines, usedDoseRects)
                        results[dn] = near?.first
                        near?.second?.let { usedDoseRects.add(it) }
                    }

                    // ===== Salida (UI + TTS) =====
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
                // Cierra el frame y vuelve a modo idle (single-shot).
                imageProxy.close()
                imageAnalysis?.clearAnalyzer()
            }
    }

    // -------------------------------------------------------
    // TTS
    // -------------------------------------------------------
    private fun speakOut(text: String) {
        // Usa Bundle alias AndroidBundle (como en tu versión) para mantener compatibilidad.
        val params = AndroidBundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "UTTERANCE_ID")
    }

    // -------------------------------------------------------
    // Permisos
    // -------------------------------------------------------
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

    // -------------------------------------------------------
    // TTS init + bienvenida
    // -------------------------------------------------------
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("es", "AR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Idioma no soportado")
            }
            tts.setSpeechRate(1.10f)
            tts.setPitch(1.0f)

            // Listener opcional (estado del utterance).
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {}
            })

            // Bienvenida solo una vez por lanzamiento (según flag de instancia).
            maybeSpeakWelcome()

        } else {
            Log.e(TAG, "Error al inicializar TTS")
        }
    }

    // -------------------------------------------------------
    // Bienvenida / retorno desde AddDrug
    // -------------------------------------------------------
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

    // -------------------------------------------------------
    // Helpers: PREWARM OCR + Espera DB
    // -------------------------------------------------------
    private fun prewarmOcr() {
        // Crea un bitmap dummy y ejecuta una pasada del OCR
        // para cargar el modelo en memoria y evitar el "cold start".
        val bmp = android.graphics.Bitmap.createBitmap(320, 320, android.graphics.Bitmap.Config.ARGB_8888)
        val img = InputImage.fromBitmap(bmp, 0)
        recognizer.process(img).addOnCompleteListener {
            // No usamos el resultado; solo "entra en calor" el modelo.
        }
    }

    private suspend fun waitDbReady(timeoutMs: Long = 6000) {
        // Espera activa (con delay corto) hasta que la tabla tenga al menos 1 fila
        // o se cumpla el tiempo máximo. Evita que la primera detección falle por DB vacía.
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

    // -------------------------------------------------------
    // Constantes / permisos
    // -------------------------------------------------------
    companion object {
        private const val TAG = "CameraXApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
}
