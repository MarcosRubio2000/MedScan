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

@Database(entities = [Drug::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicineDao(): MedicineDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

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

private fun normalize(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .replace("[^\\p{L}\\p{Nd}\\s]".toRegex(), " ")
        .replace("\\s+".toRegex(), " ")
        .trim()
        .uppercase()

private class Prepopulate(private val context: Context) : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        val instance = AppDatabase.get(context)
        val dao = instance.medicineDao()
        CoroutineScope(Dispatchers.IO).launch {
            // Carga desde assets/medicamentos.json (tu archivo existente)
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
