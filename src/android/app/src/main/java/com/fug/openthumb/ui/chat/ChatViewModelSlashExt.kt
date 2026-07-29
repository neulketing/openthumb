package com.fug.openthumb.ui.chat

// [T-android-split-chat] Slash-menu STATE methods (filter / open / dismiss /
// menu-state) extracted from ChatViewModel as extension functions. The action
// dispatcher executeSlashCommand stays in the class (entangled with compact/
// thinking/memory). 7 slash-state fields flipped private->internal. Verbatim.

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

/** Filter available commands by current filter text, with dynamic
 *  localized subtitles. Subtitles read strings.xml via the injected
 *  Context so the panel respects system locale (T241).
 *
 *  [T-skill-slash a88ea8f9] After the built-in rows, append every
 *  enabled installed Skill as a `/<skill-name>` slash entry — tap
 *  fills the composer with `/<name>` and dismisses the menu (see
 *  [executeSlashCommand]). Disabled skills are hidden so toggling a
 *  skill off in Settings naturally removes it from the menu without
 *  uninstalling it.
 *
 *  [T-session-skill-toggle-override-global-android] Enablement is
 *  resolved per-session, not globally: [SkillRepository.isEnabledForSession]
 *  returns the session-scoped override when one exists and falls back to
 *  the global toggle otherwise. The previous code filtered on the raw
 *  global `isEnabled` flag, so a skill turned off globally but turned ON
 *  for this session never appeared in the `/` picker even though the
 *  prompt-injection path ([SkillRepository.skillPromptFragment]) already
 *  honored the override — the agent knew about the skill but the user
 *  couldn't surface it via slash. (DM 𝙓𝙄𝙉 304891.)
 */
internal fun ChatViewModel.filteredSlashCommands(): List<SlashCommand> {
    val filter = _slashFilter.value.lowercase()
    val base = availableSlashCommands.map { cmd ->
        when (cmd.id) {
            "compact" -> cmd.copy(
                subtitle = context.getString(R.string.slash_compact_subtitle),
            )
            "memory" -> cmd.copy(
                subtitle = context.getString(
                    if (_memoryEnabled.value) R.string.slash_memory_writes_on
                    else R.string.slash_memory_writes_off,
                ),
            )
            "thinking" -> cmd.copy(
                subtitle = if (!currentModelSupportsReasoning) {
                    context.getString(R.string.slash_thinking_unsupported)
                } else {
                    context.getString(
                        R.string.slash_thinking_subtitle,
                        _thinkingLevel.value.localizedName(context),
                    )
                },
            )
            "clear" -> cmd.copy(
                subtitle = context.getString(R.string.slash_clear_subtitle),
            )
            else -> cmd
        }
    }
    val sid = activeSessionId
    val skillRows: List<SlashCommand> = skillRepository?.skills?.value
        ?.filter { skillRepository.isEnabledForSession(it.id, sid) }
        ?.sortedBy { it.name.lowercase() }
        ?.map { skill ->
            val trimmed = skill.description.trim()
            val sub = if (trimmed.isNotEmpty()) trimmed else "Skill · v${skill.version}"
            SlashCommand(
                id = "skill:${skill.id}",
                icon = Icons.Outlined.Extension,
                title = skill.name,
                subtitle = sub,
                isSkill = true,
            )
        } ?: emptyList()
    // [T-mcp-integration-android] MCP servers appear in the / picker too,
    // tagged [mcp] with a wrench icon to distinguish them from skills (⚡).
    // All servers enabled for this session are shown (not just the Top-20
    // disclosed in the system prompt). Tapping fills the composer with the
    // server name; discovery/call happens model-side via minis-mcp-cli.
    val mcpRows: List<SlashCommand> = mcpRepository?.servers?.value
        ?.filter { mcpRepository.isEnabledForSession(it.id, sid) }
        ?.sortedBy { it.id.lowercase() }
        ?.map { server ->
            val note = server.note?.trim().orEmpty()
            val sub = if (note.isNotEmpty()) "[mcp] $note" else "[mcp] ${server.transportSummary}"
            SlashCommand(
                id = "mcp:${server.id}",
                icon = Icons.Outlined.Build,
                title = server.id,
                subtitle = sub,
                isMcp = true,
            )
        } ?: emptyList()
    val all = base + skillRows + mcpRows
    return if (filter.isEmpty()) all else all.filter { it.title.lowercase().contains(filter) }
}

/**
 * Update slash-menu state based on composer text. Call from the composer's
 * `onValueChange`. Mirrors iOS `updateSlashMenuState()`.
 */
internal fun ChatViewModel.updateSlashMenuState(text: String) {
    // [T-android-slash-menu-align-ios-prepend] Over-content mode: the
    // composer reads `/<typed-filter> <saved-original>` (see
    // showSlashMenuOverInput). The filter is the token between the leading
    // "/" and the first whitespace — everything after that is the user's
    // preserved original text and must NOT join the filter, or the menu
    // would search the whole `/<filter> <original>` string and never match.
    // Mirrors iOS updateSlashMenuState's over-input branch.
    if (savedInputBeforeSlash != null) {
        val starts = text.startsWith("/") || text.startsWith("／")
        if (!starts) {
            // The leading "/" was deleted — close the menu and leave
            // over-content mode WITHOUT restoring (the user is editing the
            // composer directly now). Drop the saved original so we don't
            // later re-strip a prefix that no longer exists.
            savedInputBeforeSlash = null
            _showSlashMenu.value = false
            _slashMenuSelectedIndex.value = -1
            return
        }
        val afterSlash = text.drop(1)
        val end = afterSlash.indexOfFirst { it.isWhitespace() }.let { if (it < 0) afterSlash.length else it }
        _slashFilter.value = afterSlash.substring(0, end)
        _showSlashMenu.value = true
        _slashMenuSelectedIndex.value = -1
        return
    }
    // Typed-"/" path (no saved original): the whole input is the slash
    // query. Accept full-width "／" (U+FF0F) too — see tryExecuteInputAsSlashCommand.
    val starts = text.startsWith("/") || text.startsWith("／")
    if (starts && !text.contains("\n") && text.length < 30) {
        _showSlashMenu.value = true
        _slashFilter.value = text.drop(1)
        _slashMenuSelectedIndex.value = -1
    } else {
        _showSlashMenu.value = false
        _slashMenuSelectedIndex.value = -1
    }
}

/**
 * "/" button tapped. If composer is empty, set input to "/"; otherwise save
 * and show the menu directly without clobbering existing input.
 * Returns the (possibly updated) input text.
 */
internal fun ChatViewModel.showSlashMenuOverInput(currentInput: String): String {
    val trimmed = currentInput.trim()
    // [T-android-slash-menu-align-ios-prepend] Boundary: the composer text
    // already starts with "/" (the user hand-typed a slash command). Don't
    // prepend another "/ " (which would make "/ /foo") — treat it exactly
    // like the typed-"/" path: open the menu over the existing text and let
    // updateSlashMenuState derive the filter from it. No saved original.
    if (currentInput.startsWith("/") || currentInput.startsWith("／")) {
        savedInputBeforeSlash = null
        updateSlashMenuState(currentInput)
        return currentInput
    }
    return if (trimmed.isEmpty()) {
        // Empty composer: behave like typing "/" — the input becomes the
        // slash query itself, so we're NOT "over content" (no saved text).
        savedInputBeforeSlash = null
        _showSlashMenu.value = true
        _slashFilter.value = ""
        _slashMenuSelectedIndex.value = -1
        "/"
    } else {
        // [T-android-slash-menu-align-ios-prepend] iOS parity: PREPEND "/ "
        // so the composer reads `/ <original>`, save the original for
        // dismiss/skill-prepend, and land the caret right after the slash
        // (index 1) so the user types into the menu's filter slot —
        // identical to having typed "/" at the start themselves.
        savedInputBeforeSlash = currentInput
        _showSlashMenu.value = true
        _slashFilter.value = ""
        _slashMenuSelectedIndex.value = -1
        _pendingCaret.value = 1
        "/ $currentInput"
    }
}

/**
 * Dismiss the slash menu. Returns the text the composer should restore to —
 * either the previously-saved text (if opened via button over content) or
 * an empty string (if opened by typing "/").
 */
internal fun ChatViewModel.dismissSlashMenu(currentInput: String): String {
    // [T-android-slash-menu-align-ios-prepend] iOS parity:
    // - Over-content (saved != null): restore the saved ORIGINAL, stripping
    //   the "/ " prefix showSlashMenuOverInput injected. We restore `saved`,
    //   NOT the live `currentInput` (which is "/ <original>" possibly with a
    //   filter token), so the prefix never lingers and the body is intact.
    // - Typed-"/" (saved == null): the input was the slash query → clear it.
    val saved = savedInputBeforeSlash
    savedInputBeforeSlash = null
    _showSlashMenu.value = false
    _slashMenuSelectedIndex.value = -1
    if (saved != null) {
        _pendingCaret.value = saved.length
        return saved
    }
    return if (currentInput.startsWith("/") || currentInput.startsWith("／")) "" else currentInput
}

internal fun ChatViewModel.slashMenuSetSelectedIndex(index: Int) {
    _slashMenuSelectedIndex.value = index
}
