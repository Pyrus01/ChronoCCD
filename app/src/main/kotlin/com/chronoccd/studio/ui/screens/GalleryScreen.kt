package com.chronoccd.studio.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.typography
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.chronoccd.studio.data.CapturedPhotoItem
import com.chronoccd.studio.ui.AppTab
import com.chronoccd.studio.ui.ChronoCCDUiState
import com.chronoccd.studio.ui.theme.LocalAppFonts

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun GalleryScreen(
    uiState: ChronoCCDUiState,
    contentPadding: PaddingValues,
    onOpenInDarkroom: (CapturedPhotoItem) -> Unit,
    onDeleteItem: (CapturedPhotoItem) -> Unit,
    onSaveCurrentPreviewToRoll: () -> Unit,
    onNavigateTab: (AppTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val fonts = LocalAppFonts.current

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 112.dp
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize()
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Memory Stick Roll",
                        fontFamily = fonts.topBarTitle,
                        style = typography.headlineMedium,
                        color = colorScheme.onSurface
                    )
                    Text(
                        text = "DCIM / 100MSDCF • ${uiState.capturedRoll.size} Digicam JPEG(s)",
                        style = typography.labelMedium,
                        color = colorScheme.primary
                    )
                }

                FilledTonalButton(
                    onClick = onSaveCurrentPreviewToRoll,
                    shapes = ButtonDefaults.shapes()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Save Current", style = typography.labelMedium)
                }
            }
        }

        if (uiState.capturedRoll.isEmpty()) {
            item {
                Card(
                    shape = RoundedCornerShape(30.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colorScheme.primaryContainer,
                            modifier = Modifier.size(54.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.SdCard,
                                    contentDescription = null,
                                    tint = colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = "Memory Stick / SD Card Empty",
                            style = typography.titleLarge,
                            color = colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Take a photo with the 2000s Camera Viewfinder or tap 'Save Current' to write your active Darkroom Studio image to the Memory Stick Roll.",
                            style = typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(20.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { onNavigateTab(AppTab.CAMERA) },
                                shapes = ButtonDefaults.shapes(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CameraAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Open Camera")
                            }

                            FilledTonalButton(
                                onClick = onSaveCurrentPreviewToRoll,
                                shapes = ButtonDefaults.shapes()
                            ) {
                                Text("Save Active Scene")
                            }
                        }
                    }
                }
            }
        } else {
            items(uiState.capturedRoll, key = { it.id }) { item ->
                val decodedBmp = remember(item.filePath) {
                    runCatching { BitmapFactory.decodeFile(item.filePath) }.getOrNull()
                }
                Card(
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        if (decodedBmp != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(4f / 3f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color.Black)
                            ) {
                                Image(
                                    bitmap = decodedBmp.asImageBitmap(),
                                    contentDescription = item.personaDisplayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = "${item.personaDisplayName} (${item.personaShortCode})",
                                    style = typography.titleMedium,
                                    color = colorScheme.onSurface
                                )
                                Text(
                                    text = "Captured ${item.capturedAtFormatted} • ${item.processingTimeMs} ms ISP",
                                    style = typography.labelSmall,
                                    color = colorScheme.primary
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { onOpenInDarkroom(item) },
                                    shapes = ButtonDefaults.shapes()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Tune,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Re-Edit")
                                }

                                FilledTonalIconButton(
                                    onClick = { onDeleteItem(item) },
                                    shapes = IconButtonDefaults.shapes()
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = "Delete Photo"
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RollMetaBadge(text = item.resolutionLabel)
                            RollMetaBadge(text = "JPEG Q${item.jpegQuality} 4:2:0")
                            if (item.flashUsed) {
                                RollMetaBadge(text = "Xenon Strobe ON")
                            }
                            if (item.timestampImprinted != null) {
                                RollMetaBadge(text = "Stamp: ${item.timestampImprinted}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RollMetaBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colorScheme.surfaceBright
    ) {
        Text(
            text = text,
            style = typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
