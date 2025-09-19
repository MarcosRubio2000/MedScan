package com.medscan.medscan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.medscan.medscan.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.Locale

val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

@ExperimentalGetImage
class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var imageAnalysis: ImageAnalysis? = null
    private var latestImageProxy: ImageProxy? = null

    private lateinit var tts: TextToSpeech
    private lateinit var dbHelper: MedicineDatabaseHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        // Inicializar DB y TTS
        dbHelper = MedicineDatabaseHelper(this)
        tts = TextToSpeech(this, this)

        // Pedir permisos
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        // Botón Detectar → dispara OCR con el último frame disponible
        viewBinding.detectionButton.setOnClickListener {
            detectTextFromLatestFrame()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
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
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy ->
                        // guardamos siempre el último frame
                        latestImageProxy?.close()
                        latestImageProxy = imageProxy

                        Log.d(TAG, "Nuevo frame recibido: ${imageProxy.width}x${imageProxy.height}, rotación=${imageProxy.imageInfo.rotationDegrees}")
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Error al iniciar la cámara", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun detectTextFromLatestFrame() {
        val imageProxy = latestImageProxy ?: return
        latestImageProxy = null // lo consumo ya mismo

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    val detectedText = visionText.text
                    Log.d(TAG, "Texto detectado: $detectedText")

                    val bestMatch = dbHelper.findClosestMedicine(detectedText)

                    if (bestMatch != null) {
                        // 👇 Mostrar solo la coincidencia en pantalla
                        viewBinding.textView.text = bestMatch
                        speakOut(bestMatch)
                    } else {
                        viewBinding.textView.text = "No se encontró coincidencia"
                        speakOut("Intente nuevamente")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Error en OCR", e)
                    viewBinding.textView.text = "Error al detectar texto"
                    speakOut("Error al detectar texto")
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun speakOut(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
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
            val result = tts.setLanguage(Locale("es", "ES"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Idioma no soportado")
            }
        } else {
            Log.e(TAG, "Error al inicializar TTS")
        }
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
