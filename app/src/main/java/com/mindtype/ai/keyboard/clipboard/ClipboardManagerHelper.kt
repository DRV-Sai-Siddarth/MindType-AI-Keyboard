package com.mindtype.ai.keyboard.clipboard

import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ClipboardManagerHelper(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.clipboardDao()
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun startListening() {
        clipboard.addPrimaryClipChangedListener {
            val clipData = clipboard.primaryClip
            if (clipData != null && clipData.itemCount > 0) {
                val text = clipData.getItemAt(0).text?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        // Avoid inserting if it matches the last copied item
                        val lastText = dao.getLastItemContent()
                        if (text != lastText) {
                            dao.insertItem(ClipboardItem(content = text))
                        }
                    }
                }
            }
        }
    }
}