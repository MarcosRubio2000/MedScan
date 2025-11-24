package com.medscan.medscan

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ExperimentalGetImage

/**
 * SplashActivity
 * --------------
 * Pantalla de inicio simple que muestra el layout de splash durante un breve tiempo
 * y luego navega a la pantalla principal (MainActivity).
 *
 */
class SplashActivity : AppCompatActivity() {

    @OptIn(ExperimentalGetImage::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Vista de splash definida en: res/layout/activity_splash.xml
        setContentView(R.layout.activity_splash)

        // Esperar 2 segundos y luego abrir MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish() // cerrar Splash para que no vuelva con "atrás"
        }, 2000) // 2000 ms = 2 segundos
    }
}
