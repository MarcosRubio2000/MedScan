package com.medscan.medscan.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.medscan.medscan.db.entities.Drug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.text.Normalizer

/**
 * Base de datos Room de MedScan.
 *
 * - Entidad principal: [Drug].
 * - Versión: 1 (sin exportar esquema).
 * - Pre-poblado desde `assets/medicamentos.json` en el primer arranque.
 * - Singleton thread-safe con `INSTANCE` + `synchronized`.
 */

@Database(entities = [Drug::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    /** DAO principal para operaciones sobre la tabla `drugs`. */
    abstract fun medicineDao(): MedicineDao

    // ---------------------------------------------------------------------
    // Singleton
    // ---------------------------------------------------------------------
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * Obtiene la instancia única de la BD.
         *
         * - Usa `applicationContext` para evitar fugas de memoria.
         * - `addCallback(Prepopulate(...))` pre-carga los fármacos desde assets.
         * - `fallbackToDestructiveMigration()` mantiene el comportamiento original.
         */
        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "medscan.db"
                )
                    .addCallback(Prepopulate(context))
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
    }
}

// -------------------------------------------------------------------------
// Normalización utilitaria (para generar el campo `normalized` de Drug)
// -------------------------------------------------------------------------

/**
 * Normaliza un nombre eliminando diacríticos/símbolos y dejando MAYÚSCULAS.
 * Usado al pre-poblar la tabla desde `medicamentos.json`.
 */
private fun normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")                 // quita acentos
        .replace("[^\\p{L}\\p{Nd}\\s]".toRegex(), " ")     // símbolos → espacio
        .replace("\\s+".toRegex(), " ")                    // colapsa espacios
        .trim()
        .uppercase()

// -------------------------------------------------------------------------
// Callback de pre-poblado (se ejecuta al crear la BD por primera vez)
// -------------------------------------------------------------------------

/**
 * Lee `assets/medicamentos.json`, crea entidades [Drug] y las inserta en la BD.
 * Se ejecuta en un `Dispatchers.IO` para no bloquear el hilo principal.
 */
private class Prepopulate(private val context: Context) : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)

        val instance = AppDatabase.get(context)
        val dao = instance.medicineDao()

        CoroutineScope(Dispatchers.IO).launch {
            // Carga desde assets/medicamentos.json
            val json = context.assets.open("medicamentos.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(json)

            val list = ArrayList<Drug>(arr.length())
            for (i in 0 until arr.length()) {
                val name = arr.getString(i).trim()
                if (name.isNotEmpty()) {
                    list.add(Drug(name = name, normalized = normalize(name)))
                }
            }
            dao.insertDrugs(list)
        }
    }
}
