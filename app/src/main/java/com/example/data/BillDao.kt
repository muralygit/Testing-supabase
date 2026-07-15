package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BillDao {
    @Query("SELECT * FROM bills ORDER BY date DESC, createdAt DESC")
    fun getAllBills(): Flow<List<Bill>>

    @Query("SELECT * FROM bills WHERE category = :category ORDER BY date DESC, createdAt DESC")
    fun getBillsByCategory(category: String): Flow<List<Bill>>

    @Query("SELECT * FROM bills WHERE id = :id LIMIT 1")
    suspend fun getBillById(id: String): Bill?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBill(bill: Bill)

    @Delete
    suspend fun deleteBill(bill: Bill)

    @Query("DELETE FROM bills WHERE id = :id")
    suspend fun deleteBillById(id: String)
}
