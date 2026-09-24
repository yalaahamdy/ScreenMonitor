package com.example.screenmonitor.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.screenmonitor.data.ScreenshotItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    screenshots: List<ScreenshotItem>,
    initialIndex: Int,
    onClose: () -> Unit,
    onDeletePhoto: (ScreenshotItem) -> Unit
) {
    if (screenshots.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val safeInitialIndex = initialIndex.coerceIn(0, screenshots.size - 1)
    val pagerState = rememberPagerState(
        initialPage = safeInitialIndex,
        pageCount = { screenshots.size }
    )

    var showControls by remember { mutableStateOf(true) }
    var showDetailsSheet by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    // Swipe down to dismiss gesture state
    var dismissDragY by remember { mutableFloatStateOf(0f) }
    val animatedDismissY by animateFloatAsState(
        targetValue = dismissDragY,
        animationSpec = spring(),
        label = "dismiss_drag"
    )

    val currentPhoto = screenshots.getOrNull(pagerState.currentPage) ?: screenshots.first()
    val thumbnailListState = rememberLazyListState()

    LaunchedEffect(pagerState.currentPage) {
        thumbnailListState.animateScrollToItem(pagerState.currentPage)
    }

    val backdropAlpha = (1f - (abs(animatedDismissY) / 600f)).coerceIn(0.2f, 1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backdropAlpha))
    ) {
        // Horizontal Pager for fluid swiping between screenshots
        HorizontalPager(
            state = pagerState,
            pageSpacing = 16.dp,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = animatedDismissY
                    scaleX = 1f - (abs(animatedDismissY) / 1800f).coerceIn(0f, 0.2f)
                    scaleY = 1f - (abs(animatedDismissY) / 1800f).coerceIn(0f, 0.2f)
                },
            key = { index -> screenshots[index].file.absolutePath }
        ) { pageIndex ->
            val photoItem = screenshots[pageIndex]
            ZoomablePhotoItem(
                photoItem = photoItem,
                onTap = { showControls = !showControls },
                onVerticalDrag = { deltaY ->
                    dismissDragY += deltaY
                },
                onVerticalDragEnd = {
                    if (abs(dismissDragY) > 160f) {
                        onClose()
                    } else {
                        dismissDragY = 0f
                    }
                }
            )
        }

        // Top Floating Frosted Navigation Bar (Ultra-Compact & Refined)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Compact Close Button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(BorderStroke(0.7.dp, Color.White.copy(alpha = 0.2f)), CircleShape)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "رجوع",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Minimalist Floating Index Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(BorderStroke(0.7.dp, Color.White.copy(alpha = 0.18f)), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${screenshots.size}",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(3.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.5f))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = currentPhoto.relativeTime,
                            color = Color.White.copy(alpha = 0.75f),
                            fontSize = 11.sp
                        )
                    }
                }

                // Action Buttons (Info & Delete)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(BorderStroke(0.7.dp, Color.White.copy(alpha = 0.2f)), CircleShape)
                            .clickable { showDetailsSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = "التفاصيل",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .border(BorderStroke(0.7.dp, Color.White.copy(alpha = 0.2f)), CircleShape)
                            .clickable { showDeleteConfirmDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Delete,
                            contentDescription = "حذف",
                            tint = Color(0xFFF87171),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Bottom Floating Filmstrip Scrubber (Compact, Floating, Non-Obtrusive)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Micro Floating Time Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .border(BorderStroke(0.7.dp, Color.White.copy(alpha = 0.15f)), RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${currentPhoto.formattedDate}  ${currentPhoto.formattedTime}",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Compact Scrubber Strip
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(BorderStroke(0.7.dp, Color.White.copy(alpha = 0.12f)), RoundedCornerShape(14.dp))
                        .padding(vertical = 8.dp)
                ) {
                    LazyRow(
                        state = thumbnailListState,
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        itemsIndexed(screenshots) { index, item ->
                            val isSelected = index == pagerState.currentPage
                            ScrubberCompactThumbnail(
                                item = item,
                                isSelected = isSelected,
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDetailsSheet) {
        PhotoDetailsBottomSheet(
            item = currentPhoto,
            onDismiss = { showDetailsSheet = false }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("حذف لقطة الشاشة", fontWeight = FontWeight.Bold) },
            text = { Text("هل تريد بالتأكيد حذف هذه اللقطة بشكل نهائي من التخزين؟") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        onDeletePhoto(currentPhoto)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("حذف")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("إلغاء")
                }
            }
        )
    }
}

@Composable
fun ZoomablePhotoItem(
    photoItem: ScreenshotItem,
    onTap: () -> Unit,
    onVerticalDrag: (Float) -> Unit,
    onVerticalDragEnd: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val animatedScale by animateFloatAsState(
        targetValue = scale,
        animationSpec = spring(),
        label = "zoom_scale"
    )

    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = photoItem.file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            decodeFullBitmap(photoItem.file.absolutePath)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(photoItem.file.absolutePath) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 4f)
                    if (scale > 1f) {
                        val maxOffset = (scale - 1f) * 600f
                        offset = Offset(
                            x = (offset.x + pan.x).coerceIn(-maxOffset, maxOffset),
                            y = (offset.y + pan.y).coerceIn(-maxOffset, maxOffset)
                        )
                    } else {
                        offset = Offset.Zero
                    }
                }
            }
            .pointerInput(photoItem.file.absolutePath) {
                detectDragGestures(
                    onDrag = { _, dragAmount ->
                        if (scale <= 1.05f) {
                            onVerticalDrag(dragAmount.y)
                        }
                    },
                    onDragEnd = {
                        if (scale <= 1.05f) {
                            onVerticalDragEnd()
                        }
                    },
                    onDragCancel = {
                        if (scale <= 1.05f) {
                            onVerticalDragEnd()
                        }
                    }
                )
            }
            .pointerInput(photoItem.file.absolutePath) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                    onTap = { onTap() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        } else {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        }
    }
}

@Composable
fun ScrubberCompactThumbnail(
    item: ScreenshotItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val thumbBitmap by produceState<Bitmap?>(initialValue = null, key1 = item.file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            decodeThumbnail(item.file.absolutePath)
        }
    }

    Box(
        modifier = Modifier
            .size(width = 34.dp, height = 50.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(
                width = if (isSelected) 2.dp else 0.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable { onClick() }
    ) {
        if (thumbBitmap != null) {
            Image(
                bitmap = thumbBitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF222226))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoDetailsBottomSheet(
    item: ScreenshotItem,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "معلومات لقطة الشاشة",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailRow(
                        icon = Icons.AutoMirrored.Outlined.InsertDriveFile,
                        title = "اسم الملف",
                        value = item.file.name
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                    DetailRow(
                        icon = Icons.Outlined.CalendarToday,
                        title = "التاريخ والوقت",
                        value = "${item.formattedDate}  ${item.formattedTime}"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                    DetailRow(
                        icon = Icons.Outlined.Schedule,
                        title = "التوقيت النسبي",
                        value = item.relativeTime
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                    val resolution = if (item.width > 0 && item.height > 0) {
                        val mp = "%.1f".format((item.width * item.height) / 1_000_000.0)
                        "${item.width} × ${item.height} بكسل ($mp MP)"
                    } else {
                        "شاشة الهاتف القياسية"
                    }
                    DetailRow(
                        icon = Icons.Outlined.AspectRatio,
                        title = "الأبعاد والدقة",
                        value = resolution
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                    val sizeKb = item.sizeBytes / 1024
                    DetailRow(
                        icon = Icons.Outlined.Storage,
                        title = "حجم الملف",
                        value = "$sizeKb KB (${"%.2f".format(sizeKb / 1024.0)} MB)"
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

                    DetailRow(
                        icon = Icons.Outlined.Folder,
                        title = "مكان الحفظ",
                        value = "التخزين الداخلي المحمي"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun DetailRow(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun decodeThumbnail(path: String): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 8
        }
        BitmapFactory.decodeFile(path, options)
    } catch (e: Exception) {
        null
    }
}

private fun decodeFullBitmap(path: String): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
        }
        BitmapFactory.decodeFile(path, options)
    } catch (e: Exception) {
        null
    }
}
