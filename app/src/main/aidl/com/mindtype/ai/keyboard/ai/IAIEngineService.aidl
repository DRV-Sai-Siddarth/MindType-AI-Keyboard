package com.mindtype.ai.keyboard.ai;

import com.mindtype.ai.keyboard.ai.IAICallback;

interface IAIEngineService {
    void generateText(String prompt, String context, boolean preferRemote, IAICallback callback);
    void cancelGeneration();
}

