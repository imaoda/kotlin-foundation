package io.github.imaoda.foundation.examples

import io.github.imaoda.foundation.components.ManagedComponent
import io.github.imaoda.foundation.events.EventEmitter
import io.github.imaoda.foundation.events.EventSource
import io.github.imaoda.foundation.events.ListenerFailurePolicy
import io.github.imaoda.foundation.resources.CloseableGroup
import io.github.imaoda.foundation.resources.CloseableSlot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A borrowed service. Its owner, rather than each document, closes the repository. */
internal interface DocumentRepository : AutoCloseable {
    fun open(id: String): DocumentConnection
}

internal interface DocumentConnection : AutoCloseable {
    fun read(): String
    fun write(text: String)
}

internal data class DocumentState(val id: String, val text: String)
internal data class DocumentChanged(val id: String, val text: String)

/** All calls and callbacks in this example must be serialized by the caller. */
internal class DocumentWorkspace(
    private val repository: DocumentRepository,
    private val onListenerFailure: (Throwable) -> Unit,
) : ManagedComponent() {
    private val documents: MutableMap<String, DocumentSession> = mutableMapOf()
    private val ownedDocuments = own(CloseableGroup())

    init {
        onClose { documents.clear() }
    }

    fun openDocument(id: String): DocumentSession {
        ensureOpen()
        documents[id]?.takeUnless { it.isClosed }?.let { return it }
        documents.remove(id)?.let { ownedDocuments.removeAndClose(it) }
        val document = ownedDocuments.add(DocumentSession.open(id, repository, onListenerFailure))
        documents[id] = document
        return document
    }

    fun closeDocument(id: String): Boolean {
        ensureOpen()
        val document = documents.remove(id) ?: return false
        return ownedDocuments.removeAndClose(document)
    }
}

/** Construction allocates memory only; [open] protects subsequent resource acquisition. */
internal class DocumentSession private constructor(
    private val id: String,
    private val repository: DocumentRepository,
    onListenerFailure: (Throwable) -> Unit,
) : ManagedComponent() {
    // This example chooses isolation for independent observers. It is not a library default.
    private val changes = own(
        EventEmitter<DocumentChanged>(ListenerFailurePolicy.ReportAndContinue(onListenerFailure)),
    )
    private val plugin = own(CloseableSlot<DocumentPlugin>())
    private val mutableState = MutableStateFlow(DocumentState(id, ""))
    private lateinit var connection: DocumentConnection

    val state: StateFlow<DocumentState> = mutableState.asStateFlow()
    val events: EventSource<DocumentChanged> = changes.event

    private fun initialize() {
        connection = own(repository.open(id))
        // If reading fails, the factory closes the connection acquired immediately above.
        mutableState.value = DocumentState(id, connection.read())
    }

    fun changeText(text: String) {
        ensureOpen()
        connection.write(text)
        mutableState.value = DocumentState(id, text)
        // This is a fact notification after the change. A callback cannot roll it back.
        changes.emit(DocumentChanged(id, text))
    }

    fun replacePlugin(observer: (DocumentChanged) -> Unit): DocumentPlugin {
        ensureOpen()
        val replacement = DocumentPlugin.attach(events, observer)
        plugin.value = replacement
        return replacement
    }

    companion object {
        fun open(
            id: String,
            repository: DocumentRepository,
            onListenerFailure: (Throwable) -> Unit,
        ): DocumentSession {
            val document = DocumentSession(id, repository, onListenerFailure)
            try {
                document.initialize()
                return document
            } catch (original: Throwable) {
                try {
                    document.close()
                } catch (cleanup: Throwable) {
                    if (cleanup !== original) original.addSuppressed(cleanup)
                }
                throw original
            }
        }
    }
}

/** An independently closeable child whose only owned resource is its subscription. */
internal class DocumentPlugin private constructor() : ManagedComponent() {
    companion object {
        fun attach(
            events: EventSource<DocumentChanged>,
            observer: (DocumentChanged) -> Unit,
        ): DocumentPlugin {
            val plugin = DocumentPlugin()
            try {
                plugin.own(events.subscribe(observer))
                return plugin
            } catch (original: Throwable) {
                try {
                    plugin.close()
                } catch (cleanup: Throwable) {
                    if (cleanup !== original) original.addSuppressed(cleanup)
                }
                throw original
            }
        }
    }
}
