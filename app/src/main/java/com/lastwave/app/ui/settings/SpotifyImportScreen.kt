package com.lastwave.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lastwave.app.data.spotify.LikedSongsTarget
import com.lastwave.app.data.spotify.SPOTIFY_REDIRECT_URI
import com.lastwave.app.data.spotify.SpotifyAuthStatus
import com.lastwave.app.data.spotify.SpotifyCollection
import com.lastwave.app.data.spotify.SpotifyImportOutcome
import com.lastwave.app.data.spotify.SpotifyImportState
import com.lastwave.app.ui.common.ExpressiveHeader
import com.lastwave.app.ui.common.GroupPosition
import com.lastwave.app.ui.common.adaptiveContentWidth
import com.lastwave.app.ui.common.groupPositionFor
import com.lastwave.app.ui.common.groupShape
import com.lastwave.app.ui.player.LocalMiniPlayerScrollClearance

private const val SPOTIFY_DASHBOARD_URL = "https://developer.spotify.com/dashboard"

/**
 * Signs in to Spotify and imports the whole library: Liked Songs, playlists
 * and saved albums. Every track is matched to a playable YouTube Music song.
 *
 * States: not connected (client ID + sign-in) → library picker → progress →
 * summary. Import work lives in a singleton, so leaving mid-import is safe.
 */
@Composable
fun SpotifyImportScreen(
    onBack: () -> Unit,
    viewModel: SpotifyImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val connection = state.connection
    val importState = state.importState
    val lib = state.library
    val showPicker = connection != null && importState is SpotifyImportState.Idle && lib.loaded

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .adaptiveContentWidth(maxWidth = 860.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            ExpressiveHeader(
                title = "Spotify Library",
                subtitle = connection?.displayName?.takeIf { it.isNotBlank() }?.let { "Signed in as $it" }
                    ?: "Liked Songs, playlists & albums",
                onBack = onBack,
            )

            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 10.dp,
                    bottom = 96.dp + LocalMiniPlayerScrollClearance.current,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                (state.authStatus as? SpotifyAuthStatus.Failed)?.let { failed ->
                    item { ErrorCard(failed.message, onDismiss = viewModel::dismissAuthError) }
                }
                lib.error?.let { error ->
                    item { ErrorCard(error, onDismiss = viewModel::clearError) }
                }

                when {
                    connection == null -> item {
                        ConnectCard(
                            clientId = state.clientIdField,
                            isExchanging = state.authStatus is SpotifyAuthStatus.Exchanging,
                            onClientIdChange = viewModel::onClientIdChange,
                            onOpenDashboard = { openInBrowser(context, Uri.parse(SPOTIFY_DASHBOARD_URL)) },
                            onSignIn = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.startLogin { uri -> openInBrowser(context, uri) }
                            },
                        )
                    }

                    importState is SpotifyImportState.Running -> item {
                        ProgressCard(importState, onCancel = viewModel::cancelImport)
                    }

                    importState is SpotifyImportState.Finished -> {
                        item { SummaryHeader(importState, onDone = viewModel::dismissResult) }
                        outcomeRows(importState.outcomes)
                    }

                    lib.isLoading || !lib.loaded -> item { LoadingCard(lib.isLoading, onRetry = viewModel::loadLibrary) }

                    else -> {
                        item {
                            AccountCard(
                                name = connection.displayName.ifBlank { "Spotify account" },
                                onDisconnect = viewModel::disconnect,
                                onRefresh = viewModel::loadLibrary,
                            )
                        }
                        item {
                            LikedSongsCard(
                                count = lib.likedCount,
                                included = lib.includeLiked,
                                target = lib.likedTarget,
                                onIncludedChange = viewModel::setIncludeLiked,
                                onTargetChange = viewModel::setLikedTarget,
                            )
                        }
                        collectionSection(
                            title = "Playlists",
                            collections = lib.playlists,
                            selectedKeys = lib.selectedKeys,
                            onToggle = viewModel::toggle,
                            onSetAll = viewModel::setAll,
                        )
                        collectionSection(
                            title = "Saved albums",
                            collections = lib.albums,
                            selectedKeys = lib.selectedKeys,
                            onToggle = viewModel::toggle,
                            onSetAll = viewModel::setAll,
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showPicker && state.selectedCount > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .adaptiveContentWidth(maxWidth = 600.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(bottom = 16.dp + LocalMiniPlayerScrollClearance.current)
                .padding(horizontal = 20.dp),
        ) {
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.startImport()
                },
                shape = CircleShape,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                val count = state.selectedCount
                Text(
                    if (count == 1) "Import 1 item" else "Import $count items",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun openInBrowser(context: Context, uri: Uri) {
    try {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
private fun ConnectCard(
    clientId: String,
    isExchanging: Boolean,
    onClientIdChange: (String) -> Unit,
    onOpenDashboard: () -> Unit,
    onSignIn: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    SectionCard {
        Text("Connect Spotify", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Spotify only lets approved apps read your Liked Songs, so LastWave signs in through your own free Spotify developer app. It takes about two minutes, once:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SetupStep(1, "Open the Spotify Developer Dashboard and create an app. Tick \"Web API\".")
        OutlinedButton(onClick = onOpenDashboard, shape = CircleShape) { Text("Open dashboard") }
        SetupStep(2, "Add this Redirect URI to the app and save:")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                SPOTIFY_REDIRECT_URI,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(SPOTIFY_REDIRECT_URI)) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "Copy redirect URI")
            }
        }
        SetupStep(3, "Under User Management, add the email of the Spotify account you want to import.")
        SetupStep(4, "Copy the app's Client ID and paste it here:")
        OutlinedTextField(
            value = clientId,
            onValueChange = onClientIdChange,
            placeholder = { Text("Client ID") },
            leadingIcon = { Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onSignIn,
            enabled = clientId.isNotBlank() && !isExchanging,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            if (isExchanging) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text("Finishing sign-in…")
            } else {
                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sign in with Spotify", fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "LastWave asks for read-only access. Your login stays on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SetupStep(number: Int, text: String) {
    Row {
        Text("$number.", fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AccountCard(name: String, onDisconnect: () -> Unit, onRefresh: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "Pick what to bring over. Songs are matched to YouTube Music; anything without a confident match is skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh, shape = CircleShape) { Text("Refresh") }
            TextButton(onClick = onDisconnect) { Text("Sign out") }
        }
    }
}

@Composable
private fun LikedSongsCard(
    count: Int?,
    included: Boolean,
    target: LikedSongsTarget,
    onIncludedChange: (Boolean) -> Unit,
    onTargetChange: (LikedSongsTarget) -> Unit,
) {
    SectionCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onIncludedChange(!included) },
        ) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Liked Songs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    count?.let { if (it == 1) "1 song" else "$it songs" } ?: "Your saved tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(checked = included, onCheckedChange = onIncludedChange)
        }
        if (included) {
            TargetOption(
                selected = target == LikedSongsTarget.LASTWAVE_LIKED_SONGS,
                title = "Add to my LastWave Liked Songs",
                onClick = { onTargetChange(LikedSongsTarget.LASTWAVE_LIKED_SONGS) },
            )
            TargetOption(
                selected = target == LikedSongsTarget.SEPARATE_PLAYLIST,
                title = "Save as a \"Spotify Liked Songs\" playlist",
                onClick = { onTargetChange(LikedSongsTarget.SEPARATE_PLAYLIST) },
            )
        }
    }
}

@Composable
private fun TargetOption(selected: Boolean, title: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(title, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun LazyListScope.collectionSection(
    title: String,
    collections: List<SpotifyCollection>,
    selectedKeys: Set<String>,
    onToggle: (SpotifyCollection) -> Unit,
    onSetAll: (List<SpotifyCollection>, Boolean) -> Unit,
) {
    if (collections.isEmpty()) return
    item(key = "header_$title") {
        val allSelected = collections.all { it.selectionKey() in selectedKeys }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
        ) {
            Text(
                "$title (${collections.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSetAll(collections, !allSelected) }) {
                Text(if (allSelected) "Select none" else "Select all")
            }
        }
    }
    itemsIndexed(collections, key = { _, c -> c.selectionKey() }) { index, collection ->
        CollectionRow(
            collection = collection,
            selected = collection.selectionKey() in selectedKeys,
            position = groupPositionFor(index, collections.size),
            onToggle = { onToggle(collection) },
        )
    }
}

@Composable
private fun CollectionRow(
    collection: SpotifyCollection,
    selected: Boolean,
    position: GroupPosition,
    onToggle: () -> Unit,
) {
    Card(
        shape = groupShape(position),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (collection.kind == SpotifyCollection.Kind.ALBUM) Icons.Filled.Album else Icons.Filled.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    collection.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = listOfNotNull(
                    collection.subtitle.takeIf { it.isNotBlank() },
                    collection.trackCount?.let { if (it == 1) "1 track" else "$it tracks" },
                ).joinToString(" • ")
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun LoadingCard(isLoading: Boolean, onRetry: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("Reading your Spotify library…")
            } else {
                Text("Your library isn't loaded yet.", modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun ProgressCard(running: SpotifyImportState.Running, onCancel: () -> Unit) {
    SectionCard {
        Text(
            "Importing ${running.stepIndex} of ${running.stepCount}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(running.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (running.total > 0) {
            LinearProgressIndicator(
                progress = { (running.done.toFloat() / running.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${running.done} / ${running.total}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            "Big libraries take a while. You can leave this screen; the import keeps going.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCancel, shape = CircleShape) { Text("Cancel") }
    }
}

@Composable
private fun SummaryHeader(finished: SpotifyImportState.Finished, onDone: () -> Unit) {
    val matched = finished.outcomes.sumOf { it.matched }
    val total = finished.outcomes.sumOf { it.totalRows }
    SectionCard {
        Text(
            if (finished.cancelled) "Import cancelled" else "Import finished",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "$matched of $total songs matched and saved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDone, shape = CircleShape) { Text("Done") }
    }
}

private fun LazyListScope.outcomeRows(outcomes: List<SpotifyImportOutcome>) {
    itemsIndexed(outcomes, key = { index, _ -> "outcome_$index" }) { index, outcome ->
        Card(
            shape = groupShape(groupPositionFor(index, outcomes.size)),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    outcome.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = outcome.error
                    ?: "${outcome.matched} of ${outcome.totalRows} matched • ${outcome.detail}"
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (outcome.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
            Text(
                message,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 14.dp),
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}
