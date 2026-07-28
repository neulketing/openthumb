package com.neulketing.openblue.ui.chat

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
import com.neulketing.openblue.agent.Level
import com.neulketing.openblue.agent.ToolLoopDetector
import com.neulketing.openblue.browser.BrowserActionInput
import com.neulketing.openblue.browser.BrowserTabPool
import com.neulketing.openblue.data.db.MessageEntity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Extension
import com.neulketing.openblue.data.BPETokenizer
import com.neulketing.openblue.data.ContextOffload
import com.neulketing.openblue.data.ContextPolicy
import com.neulketing.openblue.logging.AppLogger
import com.neulketing.openblue.data.FileMentionIndex
import com.neulketing.openblue.data.db.CompactMarkerEntity
import com.neulketing.openblue.data.model.AgentContentPart
import com.neulketing.openblue.data.model.AgentToolDefinition
import com.neulketing.openblue.data.model.LLMMessage
import com.neulketing.openblue.data.model.LLMModel
import com.neulketing.openblue.data.model.LLMStreamChunk
import com.neulketing.openblue.data.model.LLMUsage
import com.neulketing.openblue.data.model.ModelGroup
import com.neulketing.openblue.data.model.ThinkingLevel
import com.neulketing.openblue.R
import com.neulketing.openblue.data.repository.ChatRepository
import com.neulketing.openblue.data.repository.MemoryRepository
import com.neulketing.openblue.data.repository.ProviderRepository
import com.neulketing.openblue.provider.ImageBudget
import com.neulketing.openblue.provider.LLMProvider
import com.neulketing.openblue.provider.ProviderFactory
import com.neulketing.openblue.sandbox.ExecutionCoordinator
import com.neulketing.openblue.terminal.MinisOpenUrlBroker
import com.neulketing.openblue.terminal.MinisUrlMarker
import com.neulketing.openblue.tools.AgentTools
import com.neulketing.openblue.tools.FileEditTool
import com.neulketing.openblue.tools.FileReadTool
import com.neulketing.openblue.tools.FileWriteTool
import com.neulketing.openblue.tools.MemoryTools
import com.neulketing.openblue.tools.ReadImageTool
import com.neulketing.openblue.tools.ToolExecutionResult
import com.neulketing.openblue.offload.OffloadPermissionManager
import com.neulketing.openblue.service.SessionActivityTracker
import com.neulketing.openblue.service.SessionConcurrencyManager
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
