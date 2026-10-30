package com.chronoccd.studio.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme.colorScheme
import androidx.compose.material3.MaterialTheme.motionScheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chronoccd.studio.data.NostalgicDigicamCatalog
import com.chronoccd.studio.ui.screens.CameraScreen
import com.chronoccd.studio.ui.screens.EditorScreen
import com.chronoccd.studio.ui.theme.ChronoCCDTheme

private data class ExpressiveNavItem(
    val tab: AppTab,
    val unselectedIcon: ImageVector,
    val selectedIcon: ImageVector,
    val label: String
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChronoCCDAppRoot(
    initialSharedImageUri: Uri? = null,
    viewModel: ChronoCCDViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(initialSharedImageUri) {
        if (initialSharedImageUri != null) {
            viewModel.loadImageFromUri(initialSharedImageUri)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.statusBannerMessage) {
        val msg = uiState.statusBannerMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBannerMessage()
        }
    }

    // Only two buttons on the floating bar: Camera and Editor
    val navItems = remember {
        listOf(
            ExpressiveNavItem(AppTab.CAMERA, Icons.Outlined.CameraAlt, Icons.Rounded.CameraAlt, "Camera"),
            ExpressiveNavItem(AppTab.DARKROOM, Icons.Outlined.PhotoFilter, Icons.Rounded.PhotoFilter, "Editor")
        )
    }

    val activeSeedColor = uiState.selectedPersona.accentColor

    // Camera-first pitch black dark theme matching the Pixel Camera UI
    ChronoCCDTheme(
        darkTheme = true,
        seedColor = activeSeedColor,
        dynamicColor = true,
        blackTheme = true
    ) {
        val motion = motionScheme
        val systemBarsInsets = WindowInsets.systemBars.asPaddingValues()

        Scaffold(
             containerColor = Color.Black,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                ) {
                    HorizontalFloatingToolbar(
                        expanded = true,
                        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(
                            toolbarContainerColor = colorScheme.surfaceContainerHigh,
                            toolbarContentColor = colorScheme.onSurface
                        ),
                        modifier = Modifier
                            .padding(
                                top = 4.dp,
                                bottom = systemBarsInsets.calculateBottomPadding() + 8.dp
                            )
                            .zIndex(1f)
                    ) {
                        navItems.forEach { item ->
                            val selected = (uiState.currentTab == item.tab) ||
                                (item.tab == AppTab.DARKROOM && uiState.currentTab != AppTab.CAMERA)
                            ToggleButton(
                                checked = selected,
                                onCheckedChange = { viewModel.selectTab(item.tab) },
                                modifier = Modifier.height(48.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Crossfade(targetState = selected, label = "navIcon") { isSelected ->
                                        Icon(
                                            imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                            contentDescription = item.label
                                        )
                                    }
                                    AnimatedVisibility(
                                        visible = selected,
                                        enter = expandHorizontally(motion.defaultSpatialSpec()),
                                        exit = shrinkHorizontally(motion.defaultSpatialSpec())
                                    ) {
                                        Text(
                                            text = item.label,
                                            fontSize = 14.sp,
                                            lineHeight = 20.sp,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Clip,
                                            modifier = Modifier.padding(start = ButtonDefaults.IconSpacing)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) { contentPadding ->
            val currentIndex = NostalgicDigicamCatalog.indexOfFirst {
                it.id == uiState.selectedPersona.id
            }.coerceAtLeast(0)

            val onSwipeNextLook = {
                val nextIdx = (currentIndex + 1) % NostalgicDigicamCatalog.size
                viewModel.selectPersona(NostalgicDigicamCatalog[nextIdx])
            }
            val onSwipePrevLook = {
                val prevIdx = (currentIndex - 1 + NostalgicDigicamCatalog.size) % NostalgicDigicamCatalog.size
                viewModel.selectPersona(NostalgicDigicamCatalog[prevIdx])
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            ) {
                AnimatedContent(
                    targetState = if (uiState.currentTab == AppTab.CAMERA) AppTab.CAMERA else AppTab.DARKROOM,
                    transitionSpec = {
                        fadeIn(motion.defaultEffectsSpec()).togetherWith(fadeOut(motion.defaultEffectsSpec()))
                    },
                    label = "screenTransition",
                    modifier = Modifier.fillMaxSize()
                ) { tab ->
                    when (tab) {
                        AppTab.CAMERA -> CameraScreen(
                            uiState = uiState,
                            contentPadding = contentPadding,
                            onSelectPersona = { viewModel.selectPersona(it) },
                            onSwipeNextLook = onSwipeNextLook,
                            onSwipePrevLook = onSwipePrevLook,
                            onToggleXenonFlash = viewModel::toggleXenonFlash,
                            onToggleTimestamp = {
                                viewModel.updateTimestampConfig { it.copy(enabled = !it.enabled) }
                            },
                            onCycleTimestampFormat = {
                                viewModel.cycleTimestampStyle()
                            },
                            onToggleCameraLens = viewModel::toggleCameraLens,
                            onSetZoom = viewModel::setDigitalZoom,
                            onCapturePhoto = { bmp, note, linearTonemap ->
                                viewModel.captureFromSimulatedSceneOrBitmap(bmp, note, linearTonemap)
                            },
                            onPickGalleryUri = viewModel::loadImageFromUri,
                            onOpenEditor = { viewModel.selectTab(AppTab.DARKROOM) },
                            onToggleNativeSizeExport = viewModel::toggleExportNativeInternalSize
                        )

                        else -> EditorScreen(
                            uiState = uiState,
                            contentPadding = contentPadding,
                            onSelectPersona = { viewModel.selectPersona(it) },
                            onSwipeNextLook = onSwipeNextLook,
                            onSwipePrevLook = onSwipePrevLook,
                            onToggleXenonFlash = viewModel::toggleXenonFlash,
                            onToggleTimestamp = {
                                viewModel.updateTimestampConfig { it.copy(enabled = !it.enabled) }
                            },
                            onCycleTimestampFormat = {
                                viewModel.cycleTimestampStyle()
                            },
                            onSetShowOriginal = viewModel::setShowOriginalComparison,
                            onPickGalleryUri = viewModel::loadImageFromUri,
                            onSaveToGallery = viewModel::saveCurrentToRollAndGallery,
                            onSharePhoto = viewModel::shareCurrentPhoto
                        )
                    }
                }
            }
        }
    }
}
