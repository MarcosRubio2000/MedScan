package com.medscan.medscan.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entidad de Room para la tabla de medicamentos.
 *
 * - Se guarda el `name` (nombre tal cual se mostrará).
 * - Se guarda `normalized` (nombre normalizado en MAYÚSCULAS sin diacríticos) para
 *   acelerar las búsquedas y evitar falsos negativos del OCR.
 *
 * La combinación de @Entity + @Index(unique = true) sobre `normalized` garantiza:
 * - Búsquedas rápidas por nombre normalizado (LIKE).
 * - No duplicar el mismo fármaco en diferentes variantes de escritura.
 */
@Entity(
    tableName = "drugs",
    indices = [Index(value = ["normalized"], unique = true)]
)
data class Drug(

    /**
     * Clave primaria autogenerada por Room.
     * No se utiliza para búsquedas; es un identificador interno.
     */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * Nombre visible (tal como aparece en la UI / TTS).
     */
    val name: String,

    /**
     * Nombre normalizado (sin acentos/diacríticos, mayúsculas, sin símbolos).
     * Se indexa de forma única para evitar duplicados lógicos y acelerar consultas.
     */
    val normalized: String
)
