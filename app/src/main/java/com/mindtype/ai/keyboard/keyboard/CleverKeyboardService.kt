package com.mindtype.ai.keyboard.keyboard
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.inputmethodservice.InputMethodService
import android.os.IBinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.mindtype.ai.keyboard.ai.IAICallback
import com.mindtype.ai.keyboard.ai.IAIEngineService
import com.mindtype.ai.keyboard.clipboard.AppDatabase
import com.mindtype.ai.keyboard.clipboard.ClipboardItem
import com.mindtype.ai.keyboard.clipboard.ClipboardManagerHelper
import com.mindtype.ai.keyboard.ui.AIDrawerLayout
import com.mindtype.ai.keyboard.ui.GoogleEmojiPickerLayout
import kotlinx.coroutines.delay

enum class ShiftState { LOWER, SHIFT_ONCE, CAPS_LOCK }
enum class KeyboardPage { QWERTY, SYMBOLS_PRIMARY, SYMBOLS_SECONDARY, EMOJI, CLIPBOARD, AI }

class CleverKeyboardService : InputMethodService(),
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private var aiService: IAIEngineService? = null
    private var isBound = false
    private lateinit var clipboardHelper: ClipboardManagerHelper

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            aiService = IAIEngineService.Stub.asInterface(service)
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            aiService = null
            isBound = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        clipboardHelper = ClipboardManagerHelper(this)
        clipboardHelper.startListening()

        try {
            val intent = Intent(this, com.mindtype.ai.keyboard.ai.AIEngineService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreateInputView(): View {
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val composeView = ComposeView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            setViewTreeLifecycleOwner(this@CleverKeyboardService)
            setViewTreeViewModelStoreOwner(this@CleverKeyboardService)
            setViewTreeSavedStateRegistryOwner(this@CleverKeyboardService)

            setContent {
                val db = remember { AppDatabase.getDatabase(this@CleverKeyboardService) }
                val clipboardItems by db.clipboardDao().getAllItems().collectAsState(initial = emptyList())

                StyledKeyboardEngineUI(
                    clipboardItems = clipboardItems,
                    onAITrigger = { prompt -> executeAITask(prompt) },
                    onKeyPress = { char -> currentInputConnection?.commitText(char, 1) },
                    onBackspace = { handleSingleBackspace() },
                    onDeleteWord = { handleDeleteWordByWord() },
                    onCursorMove = { steps -> moveCursorByOffset(steps) },
                    onEnter = { handleEnterAction() },
                    getWordSuggestions = { getDynamicNextWordSuggestions() }
                )
            }
        }

        rootLayout.addView(composeView)

        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        return rootLayout
    }

    private fun moveCursorByOffset(steps: Int) {
        val ic = currentInputConnection ?: return
        val keyCode = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        val count = kotlin.math.abs(steps)
        for (i in 0 until count) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    private fun handleSingleBackspace() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
        } else {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun handleDeleteWordByWord() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }

        val beforeText = ic.getTextBeforeCursor(100, 0)?.toString() ?: ""
        if (beforeText.isEmpty()) return

        val match = Regex("""(\s+|\w+|\W)$""").find(beforeText)
        val deleteLength = match?.value?.length ?: 1
        ic.deleteSurroundingText(deleteLength, 0)
    }

    private fun handleEnterAction() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo

        if (info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            when (action) {
                EditorInfo.IME_ACTION_GO,
                EditorInfo.IME_ACTION_NEXT,
                EditorInfo.IME_ACTION_SEARCH,
                EditorInfo.IME_ACTION_SEND,
                EditorInfo.IME_ACTION_DONE -> {
                    ic.performEditorAction(action)
                    return
                }
            }
        }
        ic.commitText("\n", 1)
    }

    private fun getDynamicNextWordSuggestions(): List<String> {
        val ic = currentInputConnection ?: return listOf("I", "the", "you")
        val before = ic.getTextBeforeCursor(30, 0)?.toString()?.trim() ?: ""

        if (before.isEmpty()) return listOf("I", "the", "you")

        val lastWord = before.split(" ").lastOrNull()?.lowercase() ?: ""
        return when (lastWord) {
            "how" -> listOf("are", "is", "do")
            "thank" -> listOf("you", "so", "much")
            "where" -> listOf("are", "is", "were")
            else -> listOf("I", "the", "you")
        }
    }

    private fun executeAITask(promptPrefix: String) {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)?.toString()
        val textToProcess = if (!selectedText.isNullOrEmpty()) {
            selectedText
        } else {
            ic.getTextBeforeCursor(500, 0)?.toString() ?: ""
        }

        if (textToProcess.isBlank()) return
        val service = aiService ?: return

        try {
            service.generateText(
                "$promptPrefix: $textToProcess",
                "",
                false,
                object : IAICallback.Stub() {
                    private var firstToken = true

                    override fun onTokenReceived(token: String) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (firstToken && !selectedText.isNullOrEmpty()) {
                                ic.commitText("", 1)
                                firstToken = false
                            }
                            ic.commitText(token, 1)
                        }
                    }

                    override fun onError(message: String) {}
                    override fun onComplete() {}
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        if (isBound) {
            try { unbindService(serviceConnection) } catch (e: Exception) { e.printStackTrace() }
        }
    }
}

@Composable
fun StyledKeyboardEngineUI(
    clipboardItems: List<ClipboardItem>,
    onAITrigger: (String) -> Unit,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onDeleteWord: () -> Unit,
    onCursorMove: (Int) -> Unit,
    onEnter: () -> Unit,
    getWordSuggestions: () -> List<String>
) {
    var page by remember { mutableStateOf(KeyboardPage.QWERTY) }
    var shiftState by remember { mutableStateOf(ShiftState.LOWER) }
    var lastShiftTapTime by remember { mutableLongStateOf(0L) }
    var suggestions by remember { mutableStateOf(listOf<String>()) }
    var isTrackpadActive by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        suggestions = getWordSuggestions()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF000000))
            .padding(vertical = 4.dp, horizontal = 2.dp)
    ) {
        // ROW 1: Blue Icon AI Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BlueIconBadge("✨") { page = KeyboardPage.AI }
            BlueIconBadge("📝") { onAITrigger("Rephrase and improve") }
            BlueIconBadge("🤖") { onAITrigger("Ask ChatGPT") }
            BlueIconBadge("📋") { page = KeyboardPage.CLIPBOARD }
            BlueIconBadge("文A") { onAITrigger("Translate to English") }

            Spacer(modifier = Modifier.width(8.dp))
            Text("|", color = Color(0xFF33333D), fontSize = 18.sp)
            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = { page = KeyboardPage.EMOJI }) {
                Text("😀", fontSize = 20.sp)
            }
            IconButton(onClick = { }) {
                Text("•••", color = Color.White, fontSize = 16.sp)
            }
            IconButton(onClick = { }) {
                Icon(Icons.Default.Mic, contentDescription = "Voice", tint = Color(0xFF536DFE))
            }
        }

        // ROW 2: Word Suggestions Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val words = if (suggestions.size >= 3) suggestions else listOf("I", "the", "you")

            words.take(3).forEachIndexed { index, word ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            onKeyPress("$word ")
                            suggestions = getWordSuggestions()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = word, color = Color.White, fontSize = 17.sp)
                }

                if (index < 2) {
                    Text("|", color = Color(0xFF22222A), fontSize = 16.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ROW 3: Interactive Layout Area
        Box(modifier = Modifier.fillMaxWidth()) {
            val keyAlpha = if (isTrackpadActive) 0.15f else 1.0f

            Column(modifier = Modifier.alpha(keyAlpha)) {
                when (page) {
                    KeyboardPage.AI -> {
                        AIDrawerLayout(
                            onPromptSelect = { promptPrefix ->
                                onAITrigger(promptPrefix)
                                page = KeyboardPage.QWERTY
                            },
                            onCustomPromptSubmit = { customPrompt ->
                                onAITrigger(customPrompt)
                                page = KeyboardPage.QWERTY
                            },
                            onBackToQwerty = { page = KeyboardPage.QWERTY }
                        )
                    }
                    KeyboardPage.QWERTY -> {
                        QwertyLayout(
                            shiftState = shiftState,
                            onKeyInput = { char ->
                                onKeyPress(char)
                                if (shiftState == ShiftState.SHIFT_ONCE) shiftState = ShiftState.LOWER
                                suggestions = getWordSuggestions()
                            },
                            onShiftClick = {
                                val currentTime = System.currentTimeMillis()
                                shiftState = if (currentTime - lastShiftTapTime < 300) {
                                    ShiftState.CAPS_LOCK
                                } else {
                                    when (shiftState) {
                                        ShiftState.LOWER -> ShiftState.SHIFT_ONCE
                                        ShiftState.SHIFT_ONCE, ShiftState.CAPS_LOCK -> ShiftState.LOWER
                                    }
                                }
                                lastShiftTapTime = currentTime
                            },
                            onBackspace = {
                                onBackspace()
                                suggestions = getWordSuggestions()
                            },
                            onDeleteWord = onDeleteWord,
                            onModeToggle = { page = KeyboardPage.SYMBOLS_PRIMARY },
                            onEnter = onEnter,
                            onTrackpadStateChange = { active -> isTrackpadActive = active },
                            onCursorMove = onCursorMove
                        )
                    }

                    KeyboardPage.SYMBOLS_PRIMARY -> {
                        PrimarySymbolsLayout(
                            onKeyInput = { char ->
                                onKeyPress(char)
                                suggestions = getWordSuggestions()
                            },
                            onBackspace = onBackspace,
                            onDeleteWord = onDeleteWord,
                            onSwitchToQwerty = { page = KeyboardPage.QWERTY },
                            onSwitchToSecondary = { page = KeyboardPage.SYMBOLS_SECONDARY },
                            onEnter = onEnter,
                            onTrackpadStateChange = { active -> isTrackpadActive = active },
                            onCursorMove = onCursorMove
                        )
                    }

                    KeyboardPage.SYMBOLS_SECONDARY -> {
                        SecondarySymbolsLayout(
                            onKeyInput = { char ->
                                onKeyPress(char)
                                suggestions = getWordSuggestions()
                            },
                            onBackspace = onBackspace,
                            onDeleteWord = onDeleteWord,
                            onSwitchToQwerty = { page = KeyboardPage.QWERTY },
                            onSwitchToPrimary = { page = KeyboardPage.SYMBOLS_PRIMARY },
                            onEnter = onEnter,
                            onTrackpadStateChange = { active -> isTrackpadActive = active },
                            onCursorMove = onCursorMove
                        )
                    }

                    KeyboardPage.EMOJI -> {
                        GoogleEmojiPickerLayout(
                            onEmojiClick = { emoji -> onKeyPress(emoji) },
                            onBackToQwerty = { page = KeyboardPage.QWERTY }
                        )
                    }

                    KeyboardPage.CLIPBOARD -> {
                        val coroutineScope = rememberCoroutineScope()
                        val context = LocalContext.current
                        val db = remember(context) { AppDatabase.getDatabase(context) }
                        val dao = remember(db) { db.clipboardDao() }

                        ClipboardDrawerLayout(
                            items = clipboardItems,
                            onItemClick = { text ->
                                onKeyPress(text)
                                page = KeyboardPage.QWERTY
                            },
                            onTogglePin = { item ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    dao.updateItem(item.copy(isPinned = !item.isPinned))
                                }
                            },
                            onDeleteItem = { item ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    dao.deleteItem(item)
                                }
                            },
                            onClearAllUnpinned = {
                                coroutineScope.launch(Dispatchers.IO) {
                                    dao.clearUnpinned()
                                }
                            },
                            onBackToQwerty = { page = KeyboardPage.QWERTY }
                        )
                    }
                }
            }

            if (isTrackpadActive) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(Color(0xEE0D0D12), RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            var totalDragX = 0f
                            detectDragGestures(
                                onDragEnd = { isTrackpadActive = false },
                                onDragCancel = { isTrackpadActive = false }
                            ) { change, dragAmount ->
                                change.consume()
                                totalDragX += dragAmount.x
                                if (totalDragX > 20f) {
                                    onCursorMove(1)
                                    totalDragX = 0f
                                } else if (totalDragX < -20f) {
                                    onCursorMove(-1)
                                    totalDragX = 0f
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("👈 Glide Finger Across Keyboard to Move Cursor 👉", color = Color(0xFF81D4FA), fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
fun ClipboardDrawerLayout(
    items: List<ClipboardItem>,
    onItemClick: (String) -> Unit,
    onTogglePin: (ClipboardItem) -> Unit,
    onDeleteItem: (ClipboardItem) -> Unit,
    onClearAllUnpinned: () -> Unit,
    onBackToQwerty: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(4.dp)
    ) {
        // Toolbar
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

            Text("Clipboard History", color = Color.LightGray, fontSize = 13.sp)

            if (items.any { !it.isPinned }) {
                TextButton(onClick = onClearAllUnpinned) {
                    Text("Clear All", color = Color(0xFFFF5252), fontSize = 12.sp)
                }
            } else {
                Spacer(modifier = Modifier.width(48.dp))
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Clipboard is empty.\nCopied text will automatically appear here.",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { item ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (item.isPinned) Color(0xFF232B3E) else Color(0xFF1E1E24),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onItemClick(item.content) }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = item.content,
                                color = Color.White,
                                fontSize = 14.sp,
                                maxLines = 2,
                                modifier = Modifier.weight(1f)
                            )

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Pin/Unpin Icon Button
                                IconButton(
                                    onClick = { onTogglePin(item) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Text(
                                        text = if (item.isPinned) "📌" else "📍",
                                        fontSize = 14.sp
                                    )
                                }

                                // Delete Icon Button
                                IconButton(
                                    onClick = { onDeleteItem(item) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Text(text = "🗑️", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QwertyLayout(
    shiftState: ShiftState,
    onKeyInput: (String) -> Unit,
    onShiftClick: () -> Unit,
    onBackspace: () -> Unit,
    onDeleteWord: () -> Unit,
    onModeToggle: () -> Unit,
    onEnter: () -> Unit,
    onTrackpadStateChange: (Boolean) -> Unit,
    onCursorMove: (Int) -> Unit
) {
    val numberRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val row1 = listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P")
    val row2 = listOf("A", "S", "D", "F", "G", "H", "J", "K", "L")
    val row3 = listOf("Z", "X", "C", "V", "B", "N", "M")
    val isUppercase = shiftState != ShiftState.LOWER

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            numberRow.forEach { num -> KeyTile(text = num, modifier = Modifier.weight(1f)) { onKeyInput(num) } }
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            row1.forEach { key ->
                KeyTile(text = if (isUppercase) key else key.lowercase(), modifier = Modifier.weight(1f)) {
                    onKeyInput(if (isUppercase) key else key.lowercase())
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.weight(0.5f))
            row2.forEach { key ->
                KeyTile(text = if (isUppercase) key else key.lowercase(), modifier = Modifier.weight(1f)) {
                    onKeyInput(if (isUppercase) key else key.lowercase())
                }
            }
            Spacer(modifier = Modifier.weight(0.5f))
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            KeyTile(
                text = when (shiftState) {
                    ShiftState.LOWER -> "⌃"
                    ShiftState.SHIFT_ONCE -> "⇧"
                    ShiftState.CAPS_LOCK -> "⇪"
                },
                modifier = Modifier.weight(1.5f),
                containerColor = Color(0xFF26262E)
            ) { onShiftClick() }

            row3.forEach { key ->
                KeyTile(text = if (isUppercase) key else key.lowercase(), modifier = Modifier.weight(1f)) {
                    onKeyInput(if (isUppercase) key else key.lowercase())
                }
            }

            RepeatingDeleteKey(
                modifier = Modifier.weight(1.5f),
                onClick = onBackspace,
                onLongPressRepeat = onDeleteWord
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            KeyTile(text = "?123", modifier = Modifier.weight(1.5f), containerColor = Color(0xFF26262E)) { onModeToggle() }
            KeyTile(text = ",", modifier = Modifier.weight(1f), containerColor = Color(0xFF26262E)) { onKeyInput(",") }

            SpacebarTrackpadKey(
                modifier = Modifier.weight(4.5f),
                label = "English (United States)",
                onSpaceClick = { onKeyInput(" ") },
                onTrackpadActivate = { onTrackpadStateChange(true) },
                onCursorMove = onCursorMove
            )

            KeyTile(text = ".", modifier = Modifier.weight(1f), containerColor = Color(0xFF26262E)) { onKeyInput(".") }
            KeyTile(text = "↵", modifier = Modifier.weight(1.5f), containerColor = Color(0xFF536DFE)) { onEnter() }
        }
    }
}

@Composable
fun PrimarySymbolsLayout(
    onKeyInput: (String) -> Unit,
    onBackspace: () -> Unit,
    onDeleteWord: () -> Unit,
    onSwitchToQwerty: () -> Unit,
    onSwitchToSecondary: () -> Unit,
    onEnter: () -> Unit,
    onTrackpadStateChange: (Boolean) -> Unit,
    onCursorMove: (Int) -> Unit
) {
    val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
    val row2 = listOf("@", "#", "$", "%", "&", "-", "+", "(", ")", "/")
    val row3 = listOf("*", "\"", "'", ":", ";", "!", "?")

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            row1.forEach { num -> KeyTile(text = num, modifier = Modifier.weight(1f)) { onKeyInput(num) } }
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            row2.forEach { sym -> KeyTile(text = sym, modifier = Modifier.weight(1f)) { onKeyInput(sym) } }
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            KeyTile(text = "=\\<", modifier = Modifier.weight(1.5f), containerColor = Color(0xFF26262E)) { onSwitchToSecondary() }

            row3.forEach { sym -> KeyTile(text = sym, modifier = Modifier.weight(1f)) { onKeyInput(sym) } }

            RepeatingDeleteKey(
                modifier = Modifier.weight(1.5f),
                onClick = onBackspace,
                onLongPressRepeat = onDeleteWord
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            KeyTile(text = "ABC", modifier = Modifier.weight(1.5f), containerColor = Color(0xFF26262E)) { onSwitchToQwerty() }
            KeyTile(text = ",", modifier = Modifier.weight(1f), containerColor = Color(0xFF26262E)) { onKeyInput(",") }
            KeyTile(text = "12\n34", modifier = Modifier.weight(1f), containerColor = Color(0xFF26262E)) {}

            SpacebarTrackpadKey(
                modifier = Modifier.weight(3.5f),
                label = "",
                onSpaceClick = { onKeyInput(" ") },
                onTrackpadActivate = { onTrackpadStateChange(true) },
                onCursorMove = onCursorMove
            )

            KeyTile(text = ".", modifier = Modifier.weight(1f), containerColor = Color(0xFF26262E)) { onKeyInput(".") }
            KeyTile(text = "↵", modifier = Modifier.weight(1.5f), containerColor = Color(0xFF536DFE)) { onEnter() }
        }
    }
}

@Composable
fun SecondarySymbolsLayout(
    onKeyInput: (String) -> Unit,
    onBackspace: () -> Unit,
    onDeleteWord: () -> Unit,
    onSwitchToQwerty: () -> Unit,
    onSwitchToPrimary: () -> Unit,
    onEnter: () -> Unit,
    onTrackpadStateChange: (Boolean) -> Unit,
    onCursorMove: (Int) -> Unit
) {
    val row1 = listOf("~", "`", "|", "•", "√", "π", "÷", "×", "¶", "Δ")
    val row2 = listOf("¥", "£", "€", "¢", "^", "°", "=", "{", "}", "\\")
    val row3 = listOf("-", "©", "®", "™", "✓", "[", "]")

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            row1.forEach { sym -> KeyTile(text = sym, modifier = Modifier.weight(1f)) { onKeyInput(sym) } }
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            row2.forEach { sym -> KeyTile(text = sym, modifier = Modifier.weight(1f)) { onKeyInput(sym) } }
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            KeyTile(text = "?123", modifier = Modifier.weight(1.5f), containerColor = Color(0xFF26262E)) { onSwitchToPrimary() }

            row3.forEach { sym -> KeyTile(text = sym, modifier = Modifier.weight(1f)) { onKeyInput(sym) } }

            RepeatingDeleteKey(
                modifier = Modifier.weight(1.5f),
                onClick = onBackspace,
                onLongPressRepeat = onDeleteWord
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            KeyTile(text = "ABC", modifier = Modifier.weight(1.5f), containerColor = Color(0xFF26262E)) { onSwitchToQwerty() }
            KeyTile(text = "<", modifier = Modifier.weight(1f), containerColor = Color(0xFF26262E)) { onKeyInput("<") }
            KeyTile(text = "12\n34", modifier = Modifier.weight(1f), containerColor = Color(0xFF26262E)) {}

            SpacebarTrackpadKey(
                modifier = Modifier.weight(3.5f),
                label = "",
                onSpaceClick = { onKeyInput(" ") },
                onTrackpadActivate = { onTrackpadStateChange(true) },
                onCursorMove = onCursorMove
            )

            KeyTile(text = ">", modifier = Modifier.weight(1f), containerColor = Color(0xFF26262E)) { onKeyInput(">") }
            KeyTile(text = "↵", modifier = Modifier.weight(1.5f), containerColor = Color(0xFF536DFE)) { onEnter() }
        }
    }
}

@Composable
fun BlueIconBadge(iconSymbol: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF2C3180),
        modifier = Modifier
            .padding(end = 8.dp)
            .size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = iconSymbol, color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
fun KeyTile(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Color(0xFF1E1E24),
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .height(48.dp)
            .background(containerColor, RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
fun SpacebarTrackpadKey(
    modifier: Modifier = Modifier,
    label: String,
    onSpaceClick: () -> Unit,
    onTrackpadActivate: () -> Unit,
    onCursorMove: (Int) -> Unit
) {
    var isLongPressing by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .height(48.dp)
            .background(Color(0xFF1E1E24), RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                var totalDragX = 0f
                detectDragGestures(
                    onDragStart = {
                        isLongPressing = true
                        onTrackpadActivate()
                    },
                    onDragEnd = { isLongPressing = false },
                    onDragCancel = { isLongPressing = false }
                ) { change, dragAmount ->
                    change.consume()
                    totalDragX += dragAmount.x
                    if (totalDragX > 20f) {
                        onCursorMove(1)
                        totalDragX = 0f
                    } else if (totalDragX < -20f) {
                        onCursorMove(-1)
                        totalDragX = 0f
                    }
                }
            }
            .clickable {
                if (!isLongPressing) onSpaceClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = Color.Gray, fontSize = 12.sp)
    }
}

@Composable
fun RepeatingDeleteKey(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongPressRepeat: () -> Unit
) {
    var isPressed by remember { mutableStateOf(false) }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(350)
            while (isPressed) {
                onLongPressRepeat()
                delay(120)
            }
        }
    }

    Box(
        modifier = modifier
            .padding(horizontal = 2.dp)
            .height(48.dp)
            .background(Color(0xFF26262E), RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(text = "⌫", color = Color.White, fontSize = 18.sp)
    }
}

@Composable
fun EmojiPickerLayout(
    onEmojiClick: (String) -> Unit,
    onBackToQwerty: () -> Unit
) {
    val emojis = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
        "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
        "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩"
    )

    Column(modifier = Modifier.height(220.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBackToQwerty, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF26262E))) {
                Text("← ABC", color = Color.White)
            }
            Text("Emojis", color = Color.Gray, fontSize = 12.sp)
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
        ) {
            items(emojis) { emoji ->
                Box(
                    modifier = Modifier
                        .padding(4.dp)
                        .clickable { onEmojiClick(emoji) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = emoji, fontSize = 22.sp)
                }
            }
        }
    }
}