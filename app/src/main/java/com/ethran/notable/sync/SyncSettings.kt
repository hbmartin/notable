package com.ethran.notable.sync

import kotlinx.serialization.Serializable

const val SYNC_SETTINGS_KEY = "SYNC_SETTINGS"

@Serializable
data class SyncSettings(
    val providerType: SyncProviderType = SyncProviderType.WEBDAV,
    val syncEnabled: Boolean = false,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "", // KvProxy handles the encryption
    val autoSync: Boolean = true,
    val syncInterval: Int = 15, // minutes
    val lastSyncTime: Long? = null,
    val syncOnNoteClose: Boolean = true,
    val wifiOnly: Boolean = false,
    val uploadOnly: Boolean = false,
    val syncedNotebookIds: Set<String> = emptySet(),
    val googleDriveFolderId: String? = null,
    val googleAccountName: String? = null,
    val remoteNamespaceVersion: Int = 1,
)
