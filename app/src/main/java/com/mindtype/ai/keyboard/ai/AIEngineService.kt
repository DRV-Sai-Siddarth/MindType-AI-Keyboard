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
                        callback.onError("Remote generation is disabled: this keyboard is offline-only.")
                        return@launch
                    }
                    streamLocalSlm(prompt, callback)
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
        // A real local SLM implementation belongs behind this method (for example,
        // a bundled JNI runtime). It intentionally has no network fallback.
        callback.onError("No bundled local language model is configured.")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
