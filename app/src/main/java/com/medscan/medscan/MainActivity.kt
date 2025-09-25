package com.medscan.medscan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibrationEffect
import android.speech.tts.TextToSpeech
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.medscan.medscan.databinding.ActivityMainBinding
import com.medscan.medscan.data.MedicineRepository
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.content.Intent


val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

@ExperimentalGetImage
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private var camera: androidx.camera.core.Camera? = null
    private lateinit var tts: TextToSpeech
    private lateinit var repo: MedicineRepository

    @Suppress("DEPRECATION")
    private fun vibrateFlash(isOn: Boolean) {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = if (isOn) {
                // Encendido → vibración más larga
                android.os.VibrationEffect.createOneShot(
                    250,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                )
            } else {
                // Apagado → vibración doble cortita
                android.os.VibrationEffect.createWaveform(
                    longArrayOf(0, 100, 80, 100), // pausa 0ms, vibra 100, pausa 80, vibra 100
                    -1 // no repetir
                )
            }
            vibrator.vibrate(effect)
        } else {
            // Compatibilidad con APIs viejas (<26)
            if (isOn) {
                vibrator.vibrate(250)
            } else {
                vibrator.vibrate(longArrayOf(0, 100, 80, 100), -1)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun vibratePhone() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    150,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            vibrator.vibrate(150)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("OCR_RESULT", "MainActivity onCreate()")
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        repo = MedicineRepository(this)
        tts = TextToSpeech(this, this)

        // Botón Add Drug -> abrir nueva pantalla
        viewBinding.addDrugButton.setOnClickListener {
            vibratePhone()
            startActivity(Intent(this, AddDrugActivity::class.java))
        }

        // Pedir permisos
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        cameraExecutor = Executors.newSingleThreadExecutor()


        // Botón Detectar
        viewBinding.detectionButton.setOnClickListener {
            vibratePhone()
            imageAnalysis?.setAnalyzer(cameraExecutor) { imageProxy ->
                processImage(imageProxy)
            }
        }

        // Botón Linterna
        viewBinding.flashButton.setOnClickListener {
            vibratePhone()
            camera?.let { cam ->
                if (cam.cameraInfo.hasFlashUnit()) {
                    val torchState = cam.cameraInfo.torchState.value
                    val newState = torchState != TorchState.ON
                    cam.cameraControl.enableTorch(newState)

                    // ✅ Llamada a la nueva vibración
                    vibrateFlash(newState)
                } else {
                    Toast.makeText(this, "El dispositivo no tiene linterna", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Observador del estado de la linterna para mantener el icono correcto
        // (se actualiza solo al cambiar TorchState)
        // Lo registramos cuando arranca la cámara
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
            }

            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )

                // Observador de la linterna
                camera?.cameraInfo?.torchState?.observe(this) { state ->
                    if (state == TorchState.ON) {
                        viewBinding.flashButton.setImageResource(R.drawable.ic_lantern_on)
                    } else {
                        viewBinding.flashButton.setImageResource(R.drawable.ic_lantern_off)
                    }
                }

            } catch (exc: Exception) {
                Log.e(TAG, "Error al iniciar la cámara", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(imageProxy: ImageProxy) {
        Log.d("OCR_RESULT", "processImage called")
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                Log.d("OCR_RESULT", "OCR completo:\n${visionText.text}")
                lifecycleScope.launch {
                    // Cada línea con bounding box
                    data class LineInfo(val text: String, val rect: android.graphics.Rect)
                    val lines = mutableListOf<LineInfo>()
                    for (block in visionText.textBlocks) {
                        Log.d("OCR_RESULT", "Bloque: ${block.text}")
                        for (line in block.lines) {
                            Log.d("OCR_RESULT", "  Línea: ${line.text}")
                            val r = line.boundingBox
                            if (r != null) lines.add(LineInfo(line.text, r))
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
                            if (dy < bestScore) {
                                best = candidate to other.rect
                                bestScore = dy
                            }
                        }
                        return best
                    }

                    lines.sortBy { it.rect.top }

                    for (li in lines) {
                        val drugName = repo.findBestDrugName(li.text)
                        if (drugName == null) continue

                        if (results.keys.any { it.equals(drugName, ignoreCase = true) }) continue

                        val sameLineDoses = repo.extractDosesPublic(li.text)
                        if (sameLineDoses.isNotEmpty()) {
                            results[drugName] = sameLineDoses.first()
                            usedDoseRects.add(li.rect)
                            continue
                        }

                        val near = findNearbyDoseFor(li)
                        if (near != null) {
                            results[drugName] = near.first
                            usedDoseRects.add(near.second)
                        } else {
                            results[drugName] = null
                        }
                    }

                    if (results.isNotEmpty()) {
                        val spoken = results.entries.joinToString("\n") { (drug, dose) ->
                            if (dose != null) "$drug $dose" else drug
                        }
                        viewBinding.textView.text = spoken
                        viewBinding.run { ttsIcon.setImageResource(R.drawable.ic_tts_verde) }
                        speakOut(spoken)
                    } else {
                        viewBinding.textView.text = "No se encontró coincidencia"
                        viewBinding.ttsIcon.setImageResource(R.drawable.ic_tts_rojo)
                        speakOut("Intente nuevamente")
                    }
                }
            }
            .addOnFailureListener {
                viewBinding.textView.text = "Error al detectar texto"
                viewBinding.ttsIcon.setImageResource(R.drawable.ic_tts_rojo)
                speakOut("Error al detectar texto")
            }
            .addOnCompleteListener {
                imageProxy.close()
                imageAnalysis?.clearAnalyzer()
            }
    }

    private fun speakOut(text: String) {
        val params = Bundle()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "UTTERANCE_ID")
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permisos no concedidos.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("es", "AR"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Idioma no soportado")
            }

            tts.setSpeechRate(1.10f) // un poco más lento
            tts.setPitch(1.0f)       // tono neutro

            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread { viewBinding.ttsIcon.setImageResource(R.drawable.ic_tts) }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread { viewBinding.ttsIcon.setImageResource(R.drawable.ic_tts) }
                }
            })
        } else {
            Log.e(TAG, "Error al inicializar TTS")
        }
    }

    override fun onResume() {
        super.onResume()
        // Reinicia el preview al volver desde AddDrugActivity
        startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
