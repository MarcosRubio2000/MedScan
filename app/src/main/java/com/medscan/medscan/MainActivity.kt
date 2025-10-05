package com.medscan.medscan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Bundle as AndroidBundle
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
import kotlinx.coroutines.launch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = MedicineRepository(this)
        tts = TextToSpeech(this, this)

        // Ir a AddDrug
        binding.addDrugButton.setOnClickListener { v ->
            Haptics.navForward(this, v)
            if (::tts.isInitialized) {
                tts.stop()
                binding.ttsIcon.setImageResource(R.drawable.ic_tts)
            }
            startActivity(Intent(this, AddDrugActivity::class.java))
        }

        // Permisos
        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        cameraExecutor = Executors.newSingleThreadExecutor()

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
                    data class LineInfo(val text: String, val rect: android.graphics.Rect)
                    val lines = mutableListOf<LineInfo>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            line.boundingBox?.let { lines.add(LineInfo(line.text, it)) }
                        }
                    }

                    val results = LinkedHashMap<String, String?>()
                    val usedDoseRects = mutableSetOf<android.graphics.Rect>()

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
                    fun findNearbyDoseFor(base: LineInfo): Pair<String, android.graphics.Rect>? {
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
                            val doses = repo.extractDosesPublic(other.text)
                            if (doses.isEmpty()) continue
                            if (usedDoseRects.any { it == other.rect }) continue
                            val candidate = doses.first()
                            if (dy < bestScore) { best = candidate to other.rect; bestScore = dy }
                        }
                        return best
                    }

                    lines.sortBy { it.rect.top }
                    for (li in lines) {
                        val drugName = repo.findBestDrugName(li.text) ?: continue
                        if (results.keys.any { it.equals(drugName, ignoreCase = true) }) continue
                        val sameLineDoses = repo.extractDosesPublic(li.text)
                        if (sameLineDoses.isNotEmpty()) {
                            results[drugName] = sameLineDoses.first()
                            usedDoseRects.add(li.rect)
                            continue
                        }
                        val near = findNearbyDoseFor(li)
                        results[drugName] = near?.first
                        near?.second?.let { usedDoseRects.add(it) }
                    }

                    if (results.isNotEmpty()) {
                        val spoken = results.entries.joinToString("\n") { (drug, dose) ->
                            if (dose != null) "$drug $dose" else drug
                        }
                        binding.textView.text = spoken
                        binding.ttsIcon.setImageResource(R.drawable.ic_tts_verde)
                        speakOut(spoken)
                    } else {
                        binding.textView.text = "No se encontró coincidencia"
                        binding.ttsIcon.setImageResource(R.drawable.ic_tts_rojo)
                        speakOut("Intente nuevamente")
                    }
                }
            }
            .addOnFailureListener {
                binding.textView.text = "Error al detectar texto"
                binding.ttsIcon.setImageResource(R.drawable.ic_tts_rojo)
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
                override fun onDone(utteranceId: String?) { runOnUiThread { binding.ttsIcon.setImageResource(R.drawable.ic_tts) } }
                override fun onError(utteranceId: String?) { runOnUiThread { binding.ttsIcon.setImageResource(R.drawable.ic_tts) } }
            })
        } else Log.e(TAG, "Error al inicializar TTS")
    }

    override fun onResume() {
        super.onResume()
        startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::tts.isInitialized) { tts.stop(); tts.shutdown() }
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.toTypedArray()
    }
}
