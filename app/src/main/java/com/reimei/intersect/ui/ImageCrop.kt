package com.reimei.intersect.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import com.reimei.intersect.ui.theme.accentNight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val CROP_OUTPUT_SIZE = 512

/**
 * 相册选图 → 裁切 的通用入口。
 *
 * 返回一个「打开相册」的函数;选完图后自动解码并回调 [onPicked],
 * 解码失败或选图取消走 [onError](取消不一定需要提示,传空即可)。
 */
@Composable
fun rememberGalleryPicker(
  onPicked: (Bitmap) -> Unit,
  onError: () -> Unit = {}
): () -> Unit {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
      val bitmap = withContext(Dispatchers.IO) { decodeForCrop(context, uri) }
      if (bitmap != null) onPicked(bitmap) else onError()
    }
  }
  return { launcher.launch("image/*") }
}

/** 把裁切结果写成 JPEG 文件。 */
fun saveBitmapToFile(bitmap: Bitmap, file: File) {
  file.parentFile?.mkdirs()
  FileOutputStream(file).use { out ->
    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
  }
}

/** 裁切头像:正方形取景框,拖动 + 双指缩放自由取景。 */
@Composable
fun CropImageScreen(
  bitmap: Bitmap,
  onCancel: () -> Unit,
  onDone: (Bitmap) -> Unit
) {
  val scope = rememberCoroutineScope()
  var zoom by remember { mutableStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  var saving by remember { mutableStateOf(false) }

  BackHandler { onCancel() }

  // 裁切页是黑底,状态栏图标这里要临时换回浅色
  val view = LocalView.current
  DisposableEffect(Unit) {
    val window = view.context.findActivity()?.window
    val controller = window?.let { WindowCompat.getInsetsController(it, view) }
    controller?.isAppearanceLightStatusBars = false
    controller?.isAppearanceLightNavigationBars = false
    onDispose {
      controller?.isAppearanceLightStatusBars = true
      controller?.isAppearanceLightNavigationBars = true
    }
  }

  BoxWithConstraints(
    Modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(Color.Black)
  ) {
    val density = LocalDensity.current
    val sideDp = minOf(maxWidth, maxHeight * 0.62f)
    val sidePx = with(density) { sideDp.toPx() }
    // 取景框铺满正方形时的基准缩放:保证图片始终盖满取景框
    val baseScale = remember(bitmap, sidePx) {
      if (sidePx <= 0f) 1f else max(sidePx / bitmap.width, sidePx / bitmap.height)
    }
    val totalScale = baseScale * zoom

    Column(Modifier.fillMaxSize()) {
      CropTopBar(
        saving = saving,
        onCancel = onCancel,
        onDone = {
          if (saving) return@CropTopBar
          saving = true
          scope.launch {
            val result = withContext(Dispatchers.Default) {
              cropSquare(bitmap, totalScale, offset, sidePx, CROP_OUTPUT_SIZE)
            }
            saving = false
            if (result != null) onDone(result) else onCancel()
          }
        }
      )
      Spacer(Modifier.weight(1f))
      Box(
        Modifier
          .align(Alignment.CenterHorizontally)
          .size(sideDp)
          .clip(RoundedCornerShape(4.dp))
          .pointerInput(bitmap, sidePx) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
              val nextZoom = (zoom * gestureZoom).coerceIn(1f, 5f)
              val nextTotal = baseScale * nextZoom
              val moved = offset + pan
              val limitX = max(0f, (bitmap.width * nextTotal - sidePx) / 2f)
              val limitY = max(0f, (bitmap.height * nextTotal - sidePx) / 2f)
              zoom = nextZoom
              offset = Offset(
                moved.x.coerceIn(-limitX, limitX),
                moved.y.coerceIn(-limitY, limitY)
              )
            }
          }
      ) {
        Image(
          bitmap.asImageBitmap(),
          contentDescription = null,
          modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
              scaleX = zoom
              scaleY = zoom
              translationX = offset.x
              translationY = offset.y
            },
          contentScale = ContentScale.Crop
        )
        CropGridOverlay(Modifier.fillMaxSize())
      }
      Spacer(Modifier.weight(1f))
    }
  }
}

/** LocalView 的 context 可能是 ContextThemeWrapper,逐层找真正的 Activity。 */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is android.content.ContextWrapper -> baseContext.findActivity()
  else -> null
}

@Composable
private fun CropTopBar(saving: Boolean, onCancel: () -> Unit, onDone: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .padding(horizontal = 8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      "取消",
      Modifier
        .clickable(onClick = onCancel)
        .padding(12.dp),
      fontSize = 15.sp,
      color = Color.White
    )
    Text(
      "裁切图片",
      Modifier.weight(1f),
      fontSize = 17.sp,
      fontWeight = FontWeight.Medium,
      color = Color.White,
      textAlign = TextAlign.Center
    )
    Text(
      if (saving) "处理中" else "确定",
      Modifier
        .clickable(enabled = !saving, onClick = onDone)
        .padding(12.dp),
      fontSize = 15.sp,
      // 裁切页始终是黑底,「确定」用亮星蓝:与全站的主交互色保持一致,在黑底上也够亮
      color = if (saving) Color(0xFF888888) else accentNight
    )
  }
}

/** 取景框上的九宫格参考线 + 白色描边。 */
@Composable
private fun CropGridOverlay(modifier: Modifier = Modifier) {
  Canvas(modifier.border(1.dp, Color(0x66FFFFFF))) {
    val line = Color(0x33FFFFFF)
    for (i in 1..2) {
      val x = size.width / 3f * i
      val y = size.height / 3f * i
      drawLine(line, Offset(x, 0f), Offset(x, size.height), 1f)
      drawLine(line, Offset(0f, y), Offset(size.width, y), 1f)
    }
  }
}

/**
 * 把取景框内的正方形区域从原图抠出来,并缩放到输出尺寸。
 *
 * 图片按 ContentScale.Crop 铺满正方形取景框后,再叠加 [totalScale] 缩放与 [offset] 平移,
 * 这里把取景框的四条边反算回原图坐标。
 */
private fun cropSquare(
  src: Bitmap,
  totalScale: Float,
  offset: Offset,
  viewportPx: Float,
  outputSize: Int
): Bitmap? {
  if (viewportPx <= 0f || totalScale <= 0f) return null
  val side = viewportPx / totalScale
  val centerX = src.width / 2f - offset.x / totalScale
  val centerY = src.height / 2f - offset.y / totalScale
  val left = (centerX - side / 2f).roundToInt().coerceIn(0, max(0, src.width - side.roundToInt()))
  val top = (centerY - side / 2f).roundToInt().coerceIn(0, max(0, src.height - side.roundToInt()))
  val cropSide = min(side.roundToInt(), min(src.width - left, src.height - top))
  if (cropSide <= 0) return null
  val cropped = Bitmap.createBitmap(src, left, top, cropSide, cropSide)
  return Bitmap.createScaledBitmap(cropped, outputSize, outputSize, true)
}

/** 读相册图片:按需采样避免大图 OOM,并按 EXIF 摆正方向。 */
private fun decodeForCrop(context: Context, uri: Uri, maxDimension: Int = 2048): Bitmap? {
  val resolver = context.contentResolver
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

  var sample = 1
  while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDimension) {
    sample *= 2
  }
  val options = BitmapFactory.Options().apply { inSampleSize = sample }
  val raw = resolver.openInputStream(uri)?.use {
    BitmapFactory.decodeStream(it, null, options)
  } ?: return null

  val orientation = try {
    resolver.openInputStream(uri)?.use { stream ->
      ExifInterface(stream).getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
      )
    } ?: ExifInterface.ORIENTATION_NORMAL
  } catch (e: Exception) {
    ExifInterface.ORIENTATION_NORMAL
  }
  return applyExifOrientation(raw, orientation)
}

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
  val matrix = Matrix()
  when (orientation) {
    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
    ExifInterface.ORIENTATION_TRANSPOSE -> {
      matrix.postRotate(90f)
      matrix.postScale(-1f, 1f)
    }
    ExifInterface.ORIENTATION_TRANSVERSE -> {
      matrix.postRotate(270f)
      matrix.postScale(-1f, 1f)
    }
    else -> return bitmap
  }
  return try {
    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
  } catch (e: OutOfMemoryError) {
    bitmap
  }
}
