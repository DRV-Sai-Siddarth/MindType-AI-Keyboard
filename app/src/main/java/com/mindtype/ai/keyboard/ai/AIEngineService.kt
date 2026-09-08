package com.mindtype.ai.keyboard.ai

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*

class AIEngineService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var activeJob: Job? = null

    private val binder = object : IAIEngineService.Stub() {
        override fun generateText(
            prompt: String,
            context: String,
            preferRemote: Boolean,
            callback: IAICallback
        ) {
            activeJob?.cancel()
            activeJob = serviceScope.launch {
                try {
                    if (preferRemote) {
                        streamRemoteApi(prompt, callback)
                    } else {
                        streamLocalSlm(prompt, callback)
                    }
                    callback.onComplete()
                } catch (e: Exception) {
                    callback.onError(e.localizedMessage ?: "Generation failed")
                }
            }
        }

        override fun cancelGeneration() {
            activeJob?.cancel()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private suspend fun streamLocalSlm(prompt: String, callback: IAICallback) {
        // Native llama.cpp JNI token streaming simulation
        val dummyTokens = listOf("This ", "is ", "your ", "corrected ", "text ", "from ", "local ", "SLM.")
        for (token in dummyTokens) {
            delay(60) // Simulates streaming latency
            callback.onTokenReceived(token)
        }
    }

    private suspend fun streamRemoteApi(prompt: String, callback: IAICallback) {
        // Ktor SSE client streaming simulation
        val dummyTokens = listOf("Generated ", "response ", "via ", "Remote ", "LLM ", "API.")
        for (token in dummyTokens) {
            delay(40)
            callback.onTokenReceived(token)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}