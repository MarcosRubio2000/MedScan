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

/**
 * Pantalla para agregar un medicamento:
 * - Cámara y linterna como en la main
 * - OCR al presionar "Detectar"
 * - TTS da la instrucción y luego se inicia STT
 * - STT acepta: "El medicamento se llama ...", "medicamento se llama ...", "se llama ..."
 * - Si existe en DB -> informa; si no -> lo inserta
 * - Botón "Salir" (arriba derecha) -> finish()
 */
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
    private var lastPartialSaid: String? = null // cache de parciales

    // IDs para controlar cuándo arrancar STT
    private val UTT_INIT   = "UTT_INIT"
    private val UTT_PROMPT = "UTT_PROMPT"

    // Regex gatillo: opcional "El", opcional "medicamento", y siempre "se llama"
    // Captura todo lo que sigue como nombre.
    private val TRIGGER_REGEX = Regex(
        pattern = """^\s*(?:El\s+)?(?:medicamento\s+)?se\s+llama\s+(.*)$""",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddDrugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = MedicineRepository(this)
        tts = TextToSpeech(this, this)

        // Permisos
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        // Botón Detectar -> OCR
        binding.detectionButton.setOnClickListener {
            vibrateShort()
            imageAnalysis?.setAnalyzer(cameraExecutor) { image ->
                processImage(image)
            }
        }

        // Botón Linterna
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

        // Botón Salir
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

                // Estado linterna
                camera?.cameraInfo?.torchState?.observe(this) { state ->
                    if (state == TorchState.ON) {
                        binding.flashButton.setImageResource(R.drawable.ic_lantern_on)
                    } else {
                        binding.flashButton.setImageResource(R.drawable.ic_lantern_off)
                    }
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

        // 'recognizer' es el cliente de ML Kit declarado a nivel de paquete en tu proyecto
        recognizer.process(input)
            .addOnSuccessListener { visionText ->
                lastOcrText = visionText.text ?: ""
                Log.d("ADD_DRUG_OCR", "OCR:\n$lastOcrText")

                binding.textView.text =
                    "Texto detectado. Diga a continuación: El medicamento se llama... para continuar."
                speak(
                    "Texto detectado. Diga a continuación: El medicamento se llama, para continuar.",
                    UTT_PROMPT
                )
            }
            .addOnFailureListener {
                binding.textView.text = "Error al detectar texto"
                speak("Error al detectar texto", UTT_INIT)
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
                        binding.textView.postDelayed({ startListening() }, 650)
                    }
                }
                override fun onError(utteranceId: String?) {}
            })

            speak(
                "Enfoque la caja y presione Detectar.",
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
                    override fun onReadyForSpeech(params: Bundle?) {
                        Log.d(STT_TAG, "onReadyForSpeech")
                    }
                    override fun onBeginningOfSpeech() {
                        Log.d(STT_TAG, "onBeginningOfSpeech")
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        Log.d(STT_TAG, "onEndOfSpeech")
                    }

                    override fun onError(error: Int) {
                        Log.e(STT_TAG, "onError: ${sttErrorToString(error)} ($error)")

                        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                            // Reiniciar recognizer si quedó ocupado
                            try {
                                stt?.cancel()
                                stt?.destroy()
                            } catch (_: Exception) {}
                            stt = null
                            binding.textView.postDelayed({ startListening() }, 300)
                            return
                        }

                        // Fallback: si hubo error pero tenemos un parcial útil (contiene "llama"), usarlo
                        if (!lastPartialSaid.isNullOrBlank() &&
                            lastPartialSaid!!.contains("llama", ignoreCase = true)) {
                            Log.d(STT_TAG, "Usando parcial tras error: '${lastPartialSaid}'")
                            val fake = Bundle().apply {
                                putStringArrayList(
                                    SpeechRecognizer.RESULTS_RECOGNITION,
                                    arrayListOf(lastPartialSaid!!)
                                )
                            }
                            onResults(fake)
                            return
                        }

                        binding.textView.text =
                            "Error. Vuelva a decir: El medicamento se llama..."
                        speak(
                            "Error. Vuelva a decir: El medicamento se llama, seguido del nombre.",
                            UTT_PROMPT
                        )
                    }

                    override fun onResults(results: Bundle) {
                        val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        var said = matches?.firstOrNull()?.trim().orEmpty()

                        // Fallback si no hay resultado final: usar el último parcial solo si es útil
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
                            Log.d(STT_TAG, "Sin resultado ni parcial utilizable")
                            binding.textView.text = "Error. Vuelva a decir: El medicamento se llama..."
                            speak("Error. Vuelva a decir: El medicamento se llama, seguido del nombre.", UTT_PROMPT)
                            return
                        }


                        // --- Validación y extracción tolerante del trigger ---
                        val m = TRIGGER_REGEX.find(said)
                        if (m == null) {
                            Log.d(STT_TAG, "no matchea trigger regex (con o sin 'El' o 'medicamento')")
                            binding.textView.text = "Debe comenzar con: El medicamento se llama..."
                            speak("Debe comenzar diciendo: El medicamento se llama, y luego el nombre.", UTT_PROMPT)
                            return
                        }

                        val nameRaw = m.groupValues[1].trim()
                        Log.d(STT_TAG, "nameRaw extraído='$nameRaw'")

                        if (nameRaw.isEmpty()) {
                            binding.textView.text = "No escuché el nombre. Intente nuevamente."
                            speak("No escuché el nombre. Diga: El medicamento se llama, y el nombre.", UTT_PROMPT)
                            return
                        }

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

        // Intent configurado para parciales y márgenes de silencio más generosos
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-AR")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga: El medicamento se llama... y el nombre")
        }

        // Limpiar cache de parciales y arrancar
        lastPartialSaid = null
        Log.d(STT_TAG, "startListening con idioma es-AR")
        stt?.startListening(intent)
    }

    private fun handleSpokenName(nameRaw: String) {
        val ocrNorm = normalizeLettersOnly(lastOcrText)

        lifecycleScope.launch(Dispatchers.IO) {
            val nameFromDb = repo.findBestDrugName(nameRaw)
            if (nameFromDb != null) {
                val msg = if (ocrNorm.contains(normalizeLettersOnly(nameFromDb))) {
                    "El medicamento se encuentra: $nameFromDb"
                } else {
                    "Encontré $nameFromDb en la base, pero no lo vi con claridad en la foto."
                }
                runOnUiThread {
                    binding.textView.text = msg
                    speak(msg, UTT_INIT)
                }
                return@launch
            }

            val dao = AppDatabase.get(this@AddDrugActivity).medicineDao()
            val toInsert = toTitleCaseEs(nameRaw.trim())
            val newDrug = Drug(
                name = toInsert,
                normalized = normalizeLettersOnly(toInsert)
            )

            dao.insertDrugs(listOf(newDrug))

            runOnUiThread {
                val msg = "Medicamento añadido: $toInsert"
                binding.textView.text = msg
                speak(msg, UTT_INIT)
            }
        }
    }

    /* ------------------- Utilidades ------------------- */

    private fun normalizeLettersOnly(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("[^\\p{L}\\p{Nd}\\s]".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .uppercase()

    private fun toTitleCaseEs(s: String): String =
        s.lowercase(Locale("es", "AR"))
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { c -> c.titlecase(Locale("es", "AR")) }
            }

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
        try {
            camera?.cameraControl?.enableTorch(false) // Apaga linterna al salir
        } catch (_: Exception) {}
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
