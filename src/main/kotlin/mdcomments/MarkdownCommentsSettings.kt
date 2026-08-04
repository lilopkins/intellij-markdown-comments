package uk.hpkns.mdcomments

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "MarkdownCommentsSettings", storages = [Storage("markdown-comments.xml")])
class MarkdownCommentsSettings : SimplePersistentStateComponent<MarkdownCommentsSettings.State>(State()) {
    /** Persistent plugin settings state. */
    class State : BaseState() {
        var enabled by property(true)
    }

    /** Global switch for inline Markdown comment rendering. */
    var enabled: Boolean
        get() = state.enabled
        set(value) {
            state.enabled = value
        }
}
