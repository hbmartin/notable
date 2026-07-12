package com.ethran.notable.sync

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import com.ethran.notable.utils.AppResult
import com.ethran.notable.utils.DomainError

interface WebDavClientFactoryPort {
    // Abstraction used by sync flow to avoid direct dependency on WebDAVClient construction.
    fun create(serverUrl: String, username: String, password: String): RemoteSyncProvider
}

@Singleton
class WebDavClientFactoryAdapter @Inject constructor() : WebDavClientFactoryPort {
    override fun create(serverUrl: String, username: String, password: String): RemoteSyncProvider {
        return WebDAVClient(serverUrl, username, password)
    }
}

interface RemoteSyncProviderFactoryPort {
    suspend fun create(settings: SyncSettings): AppResult<RemoteSyncProvider, DomainError>
}

@Singleton
class RemoteSyncProviderFactory @Inject constructor(
    private val webDavFactory: WebDavClientFactoryPort,
    private val driveAuthorizationManager: DriveAuthorizationManager,
) : RemoteSyncProviderFactoryPort {
    override suspend fun create(settings: SyncSettings): AppResult<RemoteSyncProvider, DomainError> =
        when (settings.providerType) {
            SyncProviderType.WEBDAV -> {
                if (settings.serverUrl.isBlank() || settings.username.isBlank() || settings.password.isBlank()) {
                    AppResult.Error(DomainError.SyncAuthError)
                } else AppResult.Success(webDavFactory.create(settings.serverUrl, settings.username, settings.password))
            }
            SyncProviderType.GOOGLE_DRIVE -> {
                val folderId = settings.googleDriveFolderId
                    ?: return AppResult.Error(DomainError.SyncError("Choose a Google Drive folder first"))
                when (val authorization = driveAuthorizationManager.authorizeSilently(settings.googleAccountName)) {
                    is DriveAuthorizationResult.Authorized -> AppResult.Success(
                        GoogleDriveProvider(
                            folderId = folderId,
                            tokenProvider = { driveAuthorizationManager.currentToken },
                        )
                    )
                    is DriveAuthorizationResult.NeedsUserAction -> AppResult.Error(
                        DomainError.SyncError("Google Drive authorization needs attention; open Sync settings")
                    )
                    is DriveAuthorizationResult.Unavailable -> AppResult.Error(
                        DomainError.SyncError(authorization.reason)
                    )
                }
            }
        }
}

@Suppress("unused")
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncPortsModule {
    // Hilt consumes this binding at compile time; no explicit call site in app code.
    @Binds
    abstract fun bindWebDavClientFactory(impl: WebDavClientFactoryAdapter): WebDavClientFactoryPort

    @Binds
    abstract fun bindRemoteSyncProviderFactory(impl: RemoteSyncProviderFactory): RemoteSyncProviderFactoryPort
}
