package com.aifhandoff.plugin

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.PROJECT)
@State(name = "AifHandoffSettings", storages = [Storage("aifHandoff.xml")])
class AifSettingsService : PersistentStateComponent<AifSettingsService.SettingsState> {

    data class SettingsState(
        var remoteHost: String = ""
    )

    private var myState = SettingsState()

    override fun getState(): SettingsState = myState

    override fun loadState(state: SettingsState) {
        myState = state
    }

    /** Returns trimmed remote host or empty string */
    var remoteHost: String
        get() = myState.remoteHost.trim()
        set(value) { myState.remoteHost = value.trim() }

    /** True when a remote host is configured (non-blank) */
    fun isRemoteMode(): Boolean = remoteHost.isNotBlank()

    /** Base URL — remote host or local */
    fun getBaseUrl(localWebPort: Int): String {
        return if (isRemoteMode()) {
            remoteHost.trimEnd('/')
        } else {
            "http://localhost:$localWebPort"
        }
    }

    /** API base URL — remote host + /api or local API port */
    fun getApiBaseUrl(localApiPort: Int): String {
        return if (isRemoteMode()) {
            "${remoteHost.trimEnd('/')}/api"
        } else {
            "http://localhost:$localApiPort"
        }
    }
}
