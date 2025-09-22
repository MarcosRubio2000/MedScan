package com.medscan.medscan.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medscan.medscan.db.entities.Drug

@Dao
interface MedicineDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDrugs(list: List<Drug>)

    // Devolver entidades -> SELECT *
    @Query("""
        SELECT * FROM drugs
        WHERE :normText LIKE '%' || normalized || '%'
    """)
    suspend fun findDrugsLike(normText: String): List<Drug>

    @Query("SELECT * FROM drugs")
    suspend fun getAllDrugs(): List<Drug>
}
