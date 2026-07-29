package com.fug.openthumb.ui.chat

// [T-android-split-chat] Small UI-state toggle methods extracted from
// ChatViewModel as extension functions (verbatim): tool-detail sheet,
// browser sheet, memory sheet, attachment list. The 4 backing state fields
// were flipped private->internal. No logic change.

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.fug.openthumb.agent.Level
import com.fug.openthumb.agent.ToolLoopDetector
import com.fug.openthumb.browser.BrowserActionInput
import com.fug.openthumb.browser.BrowserTabPool
import com.fug.openthumb.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.fug.openthumb.data.BPETokenizer
import com.fug.openthumb.data.ContextOffload
import com.fug.openthumb.data.ContextPolicy
import com.fug.openthumb.logging.AppLogger
import com.fug.openthumb.data.FileMentionIndex
import com.fug.openthumb.data.db.CompactMarkerEntity
import com.fug.openthumb.data.model.AgentContentPart
import com.fug.openthumb.data.model.AgentToolDefinition
import com.fug.openthumb.data.model.LLMMessage
import com.fug.openthumb.data.model.LLMModel
import com.fug.openthumb.data.model.LLMStreamChunk
import com.fug.openthumb.data.model.LLMUsage
import com.fug.openthumb.data.model.ModelGroup
import com.fug.openthumb.data.model.ThinkingLevel
import com.fug.openthumb.R
import com.fug.openthumb.data.repository.ChatRepository
import com.fug.openthumb.data.repository.MemoryRepository
import com.fug.openthumb.data.repository.ProviderRepository
import com.fug.openthumb.provider.ImageBudget
import com.fug.openthumb.provider.LLMProvider
import com.fug.openthumb.provider.ProviderFactory
import com.fug.openthumb.sandbox.ExecutionCoordinator
import com.fug.openthumb.terminal.MinisOpenUrlBroker
import com.fug.openthumb.terminal.MinisUrlMarker
import com.fug.openthumb.tools.AgentTools
import com.fug.openthumb.tools.FileEditTool
import com.fug.openthumb.tools.FileReadTool
import com.fug.openthumb.tools.FileWriteTool
import com.fug.openthumb.tools.MemoryTools
import com.fug.openthumb.tools.ReadImageTool
import com.fug.openthumb.tools.ToolExecutionResult
import com.fug.openthumb.offload.OffloadPermissionManager
import com.fug.openthumb.service.SessionActivityTracker
import com.fug.openthumb.service.SessionConcurrencyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.ByteArrayOutputStream

internal fun ChatViewModel.openToolDetail(toolBlockId: String) {
    _selectedToolDetailId.value = toolBlockId
}

internal fun ChatViewModel.closeToolDetail() {
    _selectedToolDetailId.value = null
}

internal fun ChatViewModel.toggleBrowserSheet() {
    val opening = !_showBrowserSheet.value
    if (opening) browserTabPool.ensureTabForUI()
    _showBrowserSheet.value = opening
}

internal fun ChatViewModel.dismissBrowserSheet() {
    _showBrowserSheet.value = false
}

/**
 * Open the session browser sheet, focused on the tab whose URL matches
 * [url]. If no pool tab currently has that URL, a new tab is created and
 * loaded. Used by the tool-call preview's globe button so the agent's
 * existing browser_use page is reused when available instead of spawning
 * a duplicate tab.
 */
internal fun ChatViewModel.openBrowserSheetForUrl(url: String) {
    if (url.isBlank()) {
        browserTabPool.ensureTabForUI()
    } else {
        browserTabPool.selectOrCreateTabForURL(url)
    }
    _showBrowserSheet.value = true
}

internal fun ChatViewModel.toggleMemorySheet() {
    _showMemorySheet.value = !_showMemorySheet.value
}

internal fun ChatViewModel.dismissMemorySheet() {
    _showMemorySheet.value = false
}

internal fun ChatViewModel.addAttachment(attachment: InputAttachment) {
    _attachments.value = _attachments.value + attachment
}

internal fun ChatViewModel.removeAttachment(id: String) {
    _attachments.value = _attachments.value.filter { it.id != id }
}

internal fun ChatViewModel.clearAttachments() {
    _attachments.value = emptyList()
}
