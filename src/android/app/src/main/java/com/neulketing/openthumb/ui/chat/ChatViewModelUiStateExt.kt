package com.neulketing.openthumb.ui.chat

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
import com.neulketing.openthumb.agent.Level
import com.neulketing.openthumb.agent.ToolLoopDetector
import com.neulketing.openthumb.browser.BrowserActionInput
import com.neulketing.openthumb.browser.BrowserTabPool
import com.neulketing.openthumb.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.neulketing.openthumb.data.BPETokenizer
import com.neulketing.openthumb.data.ContextOffload
import com.neulketing.openthumb.data.ContextPolicy
import com.neulketing.openthumb.logging.AppLogger
import com.neulketing.openthumb.data.FileMentionIndex
import com.neulketing.openthumb.data.db.CompactMarkerEntity
import com.neulketing.openthumb.data.model.AgentContentPart
import com.neulketing.openthumb.data.model.AgentToolDefinition
import com.neulketing.openthumb.data.model.LLMMessage
import com.neulketing.openthumb.data.model.LLMModel
import com.neulketing.openthumb.data.model.LLMStreamChunk
import com.neulketing.openthumb.data.model.LLMUsage
import com.neulketing.openthumb.data.model.ModelGroup
import com.neulketing.openthumb.data.model.ThinkingLevel
import com.neulketing.openthumb.R
import com.neulketing.openthumb.data.repository.ChatRepository
import com.neulketing.openthumb.data.repository.MemoryRepository
import com.neulketing.openthumb.data.repository.ProviderRepository
import com.neulketing.openthumb.provider.ImageBudget
import com.neulketing.openthumb.provider.LLMProvider
import com.neulketing.openthumb.provider.ProviderFactory
import com.neulketing.openthumb.sandbox.ExecutionCoordinator
import com.neulketing.openthumb.terminal.MinisOpenUrlBroker
import com.neulketing.openthumb.terminal.MinisUrlMarker
import com.neulketing.openthumb.tools.AgentTools
import com.neulketing.openthumb.tools.FileEditTool
import com.neulketing.openthumb.tools.FileReadTool
import com.neulketing.openthumb.tools.FileWriteTool
import com.neulketing.openthumb.tools.MemoryTools
import com.neulketing.openthumb.tools.ReadImageTool
import com.neulketing.openthumb.tools.ToolExecutionResult
import com.neulketing.openthumb.offload.OffloadPermissionManager
import com.neulketing.openthumb.service.SessionActivityTracker
import com.neulketing.openthumb.service.SessionConcurrencyManager
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
