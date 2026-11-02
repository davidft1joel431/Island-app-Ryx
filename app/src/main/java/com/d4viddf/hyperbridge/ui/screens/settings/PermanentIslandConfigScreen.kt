package com.d4viddf.hyperbridge.ui.screens.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.models.IslandAlignment
import com.d4viddf.hyperbridge.service.NotificationReaderService
import com.d4viddf.hyperbridge.ui.components.PermanentIslandPreview
import com.d4viddf.hyperbridge.ui.theme.HyperBridgeTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermanentIslandConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context.applicationContext) }
    val scope = rememberCoroutineScope()

    val isEnabled by prefs.isPermanentIslandEnabledFlow.collectAsState(initial = false)
    val hideInLandscape by prefs.hidePermanentIslandLandscapeFlow.collectAsState(initial = false)
    val positionY by prefs.islandPositionYFlow.collectAsState(initial = 0)
    val alignment by prefs.islandAlignmentFlow.collectAsState(initial = IslandAlignment.CENTER)

    PermanentIslandConfigContent(
        isEnabled = isEnabled,
        hideInLandscape = hideInLandscape,
        positionY = positionY,
        alignment = alignment,
        onEnabledChange = { checked ->
            scope.launch {
                prefs.setPermanentIslandEnabled(checked)
                // Applies the switch immediately instead of waiting for the
                // next trip through onResume.
                runCatching {
                    context.startService(
                        Intent(context, NotificationReaderService::class.java)
                            .setAction(NotificationReaderService.ACTION_REFRESH_ISLAND)
                    )
                }
            }
        },
        onHideInLandscapeChange = { hide -> scope.launch { prefs.setHidePermanentIslandLandscape(hide) } },
        onBack = onBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermanentIslandConfigContent(
    isEnabled: Boolean,
    hideInLandscape: Boolean,
    positionY: Int,
    alignment: IslandAlignment,
    onEnabledChange: (Boolean) -> Unit,
    onHideInLandscapeChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permanent_island_title)) },
                navigationIcon = {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            PermanentIslandPreview(
                positionY = positionY,
                alignment = alignment,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                    SettingsSwitchItem(
                        icon = Icons.Outlined.PushPin,
                        title = stringResource(R.string.permanent_island_title),
                        subtitle = stringResource(R.string.permanent_island_desc),
                        checked = isEnabled,
                        onCheckedChange = onEnabledChange
                    )
                    if (isEnabled) {
                        SettingsSwitchItem(
                            icon = Icons.Outlined.ScreenRotation,
                            title = stringResource(R.string.permanent_island_hide_landscape),
                            subtitle = stringResource(R.string.permanent_island_hide_landscape_desc),
                            checked = hideInLandscape,
                            onCheckedChange = onHideInLandscapeChange
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.permanent_island_screen_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 22.dp)
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.permanent_island_preview_note),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 22.dp)
            )

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PermanentIslandConfigScreenPreview() {
    HyperBridgeTheme {
        PermanentIslandConfigContent(
            isEnabled = true,
            hideInLandscape = false,
            positionY = 0,
            alignment = IslandAlignment.CENTER,
            onEnabledChange = {},
            onHideInLandscapeChange = {},
            onBack = {}
        )
    }
}
