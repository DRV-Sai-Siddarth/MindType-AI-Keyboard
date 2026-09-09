package com.mindtype.ai.keyboard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView

@Composable
fun GoogleEmojiPickerLayout(
    onEmojiClick: (String) -> Unit,
    onBackToQwerty: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(250.dp)
            .padding(4.dp)
    ) {
        // Navigation Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onBackToQwerty,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26262E)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("← ABC", color = Color.White, fontSize = 13.sp)
            }

            Text("Emojis", color = Color.Gray, fontSize = 13.sp)

            Spacer(modifier = Modifier.width(48.dp))
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Native Google EmojiPickerView
        AndroidView(
            factory = { context ->
                EmojiPickerView(context).apply {
                    setOnEmojiPickedListener { emojiViewItem ->
                        onEmojiClick(emojiViewItem.emoji)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}