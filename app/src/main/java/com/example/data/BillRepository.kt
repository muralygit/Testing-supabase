package com.example.data

import kotlinx.coroutines.flow.Flow

class BillRepository(private val billDao: BillDao) {
    val allBills: Flow<List<Bill>> = billDao.getAllBills()

    fun getBillsByCategory(category: String): Flow<List<Bill>> = billDao.getBillsByCategory(category)

    suspend fun getBillById(id: String): Bill? = billDao.getBillById(id)

    suspend fun insertBill(bill: Bill) {
        billDao.insertBill(bill)
    }

    suspend fun deleteBill(bill: Bill) {
        billDao.deleteBill(bill)
    }

    suspend fun deleteBillById(id: String) {
        billDao.deleteBillById(id)
    }
}
