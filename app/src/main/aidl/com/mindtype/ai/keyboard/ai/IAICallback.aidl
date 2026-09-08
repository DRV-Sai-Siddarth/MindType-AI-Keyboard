
package com.mindtype.ai.keyboard.ai;

interface IAICallback {
    void onTokenReceived(String token);
    void onError(String message);
    void onComplete();
}