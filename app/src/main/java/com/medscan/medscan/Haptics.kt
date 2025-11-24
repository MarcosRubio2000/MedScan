package com.medscan.medscan

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Haptics
 * -------
 * Capa de vibración compatible (minSdk 24).
 *
 * - Usa efectos "predefined" (29+) cuando se puede.
 * - Fallback a oneShot/waveform (26+), y a vibrate(ms) (24–25).
 * - Siempre intenta también performHapticFeedback como redundancia.
 *
 */

object Haptics {

    // =========================================================
    // Constantes
    // =========================================================
    private const val TAG = "Haptics"

    // =========================================================
    // Infra (helpers internos)
    // =========================================================

    /** Obtiene el Vibrator compatible con la versión de Android. */
    private fun vibrator(ctx: Context): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (_: Throwable) { null }

    /** Verifica si el dispositivo posee vibrador. */
    private fun hasVibrator(ctx: Context): Boolean = try {
        vibrator(ctx)?.hasVibrator() == true
    } catch (_: Throwable) { false }

    /** Atributos de vibración con uso táctil (API 33+). */
    private fun touchAttrs(): VibrationAttributes? =
        if (Build.VERSION.SDK_INT >= 33)
            VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_TOUCH).build()
        else null

    /**
     * Intenta disparar el haptic del sistema sobre una vista (redundancia).
     */
    private fun sysHaptic(view: View?, type: Int): Boolean {
        if (view == null) return false
        val flags = if (Build.VERSION.SDK_INT >= 28)
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        else 0
        return try { view.performHapticFeedback(type, flags) } catch (_: Throwable) { false }
    }

    /**
     * Vibración compatible:
     * - Si llega un VibrationEffect (26+), lo usa; en 33+ lo acompaña con attrs.
     * - Si no hay effect, usa una duración (one shot) respetando la API.
     * - En 24–25 cae a vibrate(ms).
     */
    private fun vibrateCompat(
        ctx: Context,
        effect: VibrationEffect? = null,
        durationMs: Long? = null
    ) {
        val vib = vibrator(ctx) ?: return
        if (!hasVibrator(ctx)) return

        try {
            if (effect != null) {
                // >>> API guards correctos
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // 26+
                    if (Build.VERSION.SDK_INT >= 33) {
                        val attrs = touchAttrs()
                        if (attrs != null) vib.vibrate(effect, attrs) else @Suppress("DEPRECATION") vib.vibrate(effect)
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(effect)
                    }
                } else {
                    // <26 no sabe usar VibrationEffect: cae a un pulso simple
                    @Suppress("DEPRECATION")
                    vib.vibrate(durationMs ?: 120L)
                }
                Log.d(TAG, "vibrateCompat OK [effect]")
                return
            }

            // Sin 'effect': usar duración simple
            val ms = durationMs ?: 120L
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val eff = VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)
                if (Build.VERSION.SDK_INT >= 33) {
                    val attrs = touchAttrs()
                    if (attrs != null) vib.vibrate(eff, attrs) else @Suppress("DEPRECATION") vib.vibrate(eff)
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(eff)
                }
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(ms)
            }
            Log.d(TAG, "vibrateCompat OK [$ms ms]")
        } catch (t: Throwable) {
            Log.w(TAG, "vibrateCompat error: ${t.message}")
        }
    }

    // =========================================================
    // API pública (todas aceptan una View opcional para haptic del sistema)
    // =========================================================

    /** Tap genérico (botones secundarios). */
    fun click(ctx: Context, anchor: View? = null) {
        var done = false
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                vibrateCompat(ctx, VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                done = true
            } catch (_: Throwable) {}
        }
        if (!done) vibrateCompat(ctx, durationMs = 90)

        // Redundancia del sistema
        sysHaptic(
            anchor,
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.KEYBOARD_PRESS
            else HapticFeedbackConstants.KEYBOARD_TAP
        )
    }

    /** Vibración “Detectar”. */
    fun detect(ctx: Context, anchor: View? = null) {
        // 1) Vibración principal — más fuerte/larga para que no la filtre Samsung
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                vibrateCompat(
                    ctx,
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                )
            } catch (_: Throwable) {
                // Fallback si el predefined lanza (algunos OEM)
                vibrateCompat(ctx, durationMs = 180L)
            }
        } else {
            vibrateCompat(ctx, durationMs = 180L)
        }

        // 2) Refuerzos del sistema
        //    Probamos dos variantes; si la primera no entra, intentamos la segunda.
        val flags = if (Build.VERSION.SDK_INT >= 28)
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING else 0

        var sysOk = false
        if (anchor != null) {
            try { sysOk = anchor.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS, flags) } catch (_: Exception) {}
            if (!sysOk) {
                try { sysOk = anchor.performHapticFeedback(HapticFeedbackConstants.CONFIRM, flags) } catch (_: Exception) {}
            }
        }
        Log.d(TAG, "detect: sysHaptic=$sysOk")
    }

    /** Navegación hacia adelante (ir a AddDrug). */
    fun navForward(ctx: Context, anchor: View? = null) {
        // Pulso algo más largo
        vibrateCompat(ctx, durationMs = 140)
        sysHaptic(anchor, HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Navegación hacia atrás (volver). */
    fun navBack(ctx: Context, anchor: View? = null) {
        // Pulso similar a forward
        vibrateCompat(ctx, durationMs = 140)
        sysHaptic(anchor, HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Linterna: encendido → doble corto (diferente a apagado). */
    fun flashOn(ctx: Context, anchor: View? = null) {
        if (Build.VERSION.SDK_INT >= 26) {
            val timings = longArrayOf(0, 70, 60, 70) // 2 pulsos
            val amps    = intArrayOf(0, 255, 0, 255)
            val eff = if (Build.VERSION.SDK_INT >= 26)
                VibrationEffect.createWaveform(timings, amps, -1) else null
            if (eff != null) vibrateCompat(ctx, eff) else vibrateCompat(ctx, durationMs = 160)
        } else {
            vibrateCompat(ctx, durationMs = 160)
        }
        sysHaptic(anchor, HapticFeedbackConstants.CONFIRM)
    }

    /** Linterna: apagado → pulso único más largo. */
    fun flashOff(ctx: Context, anchor: View? = null) {
        if (Build.VERSION.SDK_INT >= 26) {
            val eff = VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrateCompat(ctx, eff)
        } else {
            vibrateCompat(ctx, durationMs = 220)
        }
        sysHaptic(anchor, HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Diagnóstico opcional. */
    fun diagnostics(ctx: Context) {
        val v = vibrator(ctx)
        val has = hasVibrator(ctx)
        val amp = try { if (Build.VERSION.SDK_INT >= 26) v?.hasAmplitudeControl() else null } catch (_: Throwable) { null }
        Log.i(TAG, "diag: sdk=${Build.VERSION.SDK_INT} hasVibrator=$has vib=$v ampCtrl=$amp")
    }
}
