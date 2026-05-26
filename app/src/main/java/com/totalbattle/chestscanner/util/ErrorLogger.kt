package com.totalbattle.chestscanner.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedList

data class ErrorLog(
    val timestamp: Long,
    val tag: String,
    val message: String,
    val exceptionMessage: String?
)

object ErrorLogger {
    private const val MAX_LOGS = 100
    private val logsList = LinkedList<ErrorLog>()
    private val _logsFlow = MutableStateFlow<List<ErrorLog>>(emptyList())
    
    val logs: StateFlow<List<ErrorLog>> = _logsFlow.asStateFlow()

    @Synchronized
    fun logError(tag: String, message: String, e: Throwable? = null) {
        val errorLog = ErrorLog(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            message = message,
            exceptionMessage = e?.message ?: e?.localizedMessage
        )
        
        Log.e(tag, "[$tag] $message", e)

        logsList.addFirst(errorLog)
        if (logsList.size > MAX_LOGS) {
            logsList.removeLast()
        }
        
        _logsFlow.value = logsList.toList()
    }

    @Synchronized
    fun clear() {
        logsList.clear()
        _logsFlow.value = emptyList()
    }
}
