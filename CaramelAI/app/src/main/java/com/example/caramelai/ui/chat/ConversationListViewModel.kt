package com.example.caramelai.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.caramelai.data.db.CharacterEntity
import com.example.caramelai.data.db.ConversationEntity
import com.example.caramelai.domain.CharacterRepository
import com.example.caramelai.domain.ChatRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ConversationListViewModel(
    private val characterId: String,
    characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
) : ViewModel() {
    val character: StateFlow<CharacterEntity?> =
        characterRepository.observeById(characterId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val conversations: StateFlow<List<ConversationEntity>> =
        chatRepository.observeConversations(characterId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableSharedFlow<ConversationListEvent>()
    val events: SharedFlow<ConversationListEvent> = _events.asSharedFlow()

    fun createChat() {
        viewModelScope.launch {
            val conversation = chatRepository.createConversation(characterId)
            _events.emit(ConversationListEvent.OpenChat(conversation.id))
        }
    }

    fun deleteChat(id: String) {
        viewModelScope.launch {
            chatRepository.deleteConversation(id)
        }
    }

    sealed interface ConversationListEvent {
        data class OpenChat(val conversationId: String) : ConversationListEvent
    }
}
