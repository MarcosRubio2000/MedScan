package com.medscan.medscan.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medscan.medscan.db.entities.Drug

/**
 * DAO de acceso a la tabla `drugs`.
 *
 * - Inserción masiva con IGNORE en conflicto (no duplica por índice único en `normalized`).
 * - Búsqueda por coincidencia de nombre **normalizado** usando LIKE.
 * - Lectura de todo el catálogo (para respaldos y fuzzy).
 */

@Dao
interface MedicineDao {

    // ---------------------------------------------------------------------
    // Escritura
    // ---------------------------------------------------------------------

    /**
     * Inserta una lista de fármacos.
     * OnConflictStrategy.IGNORE evita fallar si ya existe el `normalized` (índice único).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDrugs(list: List<Drug>)

    // ---------------------------------------------------------------------
    // Lecturas / búsquedas
    // ---------------------------------------------------------------------

    /**
     * Busca coincidencias donde el **texto normalizado de entrada** contenga el
     * `normalized` de la BD como substring.
     *
     * Nota: el patrón LIKE queda como:
     *    :normText LIKE '%' || normalized || '%'
     * Es decir, el parámetro queda a la izquierda y se compara contra cada `normalized`.
     */
    @Query("""
        SELECT * FROM drugs
        WHERE :normText LIKE '%' || normalized || '%'
    """)
    suspend fun findDrugsLike(normText: String): List<Drug>

    /**
     * Devuelve todo el catálogo (útil para fuzzy matching y utilidades).
     */
    @Query("SELECT * FROM drugs")
    suspend fun getAllDrugs(): List<Drug>
}
