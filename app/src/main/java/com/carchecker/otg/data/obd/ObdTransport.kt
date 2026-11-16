package com.carchecker.otg.data.obd

interface ObdTransport {
    val isConnected: Boolean
    val description: String

    suspend fun connect(): Result<Unit>
    suspend fun sendCommand(command: String, timeoutMs: Long = 3500L): Result<String>
    suspend fun disconnect()
}
