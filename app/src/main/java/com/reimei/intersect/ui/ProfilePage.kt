package com.reimei.intersect.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.Icon
import com.reimei.intersect.i18n.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.R
import com.reimei.intersect.data.MeProfile
import com.reimei.intersect.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 头像统一走这里渲染,没换过头像时退回内置图片。 */
@Composable
fun MeAvatar(
  modifier: Modifier = Modifier,
  size: Dp = 64.dp,
  shape: androidx.compose.ui.graphics.Shape = CircleShape
) {
  val context = LocalContext.current
  val bitmap = MeProfile.avatarBitmap(context)
  Box(modifier.size(size).clip(shape)) {
    if (bitmap != null) {
      Image(
        bitmap.asImageBitmap(),
        contentDescription = "头像",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )
    } else {
      Image(
        painterResource(R.drawable.ui_avatar_me),
        contentDescription = "头像",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )
    }
  }
}

/**
 * 个人资料页:头像 / 昵称 / 签名,改完即生效,没有保存按钮。
 */
@Composable
fun ProfilePage(modifier: Modifier = Modifier, onBack: () -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  // 选完图待裁切的位图
  var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var pickError by remember { mutableStateOf<String?>(null) }

  val openGallery = rememberGalleryPicker(
    onPicked = {
      pickError = null
      pendingBitmap = it
    },
    onError = { pickError = "无法读取这张图片,请换一张试试" }
  )

  val cropping = pendingBitmap != null
  BackHandler {
    if (!cropping) onBack()
  }

  if (pendingBitmap != null) {
    CropImageScreen(
      bitmap = pendingBitmap!!,
      onCancel = { pendingBitmap = null },
      onDone = { cropped ->
        pendingBitmap = null
        scope.launch {
          withContext(Dispatchers.IO) {
            saveBitmapToFile(cropped, MeProfile.avatarFile(context))
          }
          MeProfile.onAvatarSaved(context)
        }
      }
    )
  } else {
    Column(
      modifier
        .fillMaxSize()
        .blockTouchesThrough()
        .background(AppTheme.colors.background)
    ) {
      ProfileTopBar(onBack)
      Column(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.listItem)
      ) {
        AvatarRow(onClick = openGallery)
        RowDivider()
        NameRow()
        RowDivider()
        SignatureRow()
      }
      if (pickError != null) {
        Text(
          pickError!!,
          Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
          fontSize = 12.sp,
          color = AppTheme.colors.badge
        )
      }
    }
  }
}

@Composable
private fun ProfileTopBar(onBack: () -> Unit) {
  Box(
    Modifier
      .fillMaxWidth()
      .statusBarsPadding()
  ) {
    Icon(
      painterResource(R.drawable.ui_chevron_left),
      contentDescription = "返回",
      Modifier
        .align(Alignment.CenterStart)
        .clickable(onClick = onBack)
        .size(48.dp)
        .padding(14.dp),
      tint = AppTheme.colors.icon
    )
    Text(
      "个人资料",
      Modifier
        .align(Alignment.Center)
        .padding(vertical = 14.dp),
      fontSize = 17.sp,
      fontWeight = FontWeight.Medium,
      color = AppTheme.colors.textPrimary
    )
  }
}

@Composable
private fun RowDivider() {
  Box(
    Modifier
      .fillMaxWidth()
      .padding(start = 16.dp)
      .height(1.dp)
      .background(AppTheme.colors.chatListDivider)
  )
}

@Composable
private fun AvatarRow(onClick: () -> Unit) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      "头像",
      Modifier.weight(1f),
      fontSize = 16.sp,
      color = AppTheme.colors.textPrimary
    )
    MeAvatar(size = 52.dp)
    Spacer(Modifier.width(8.dp))
    Icon(
      painterResource(R.drawable.ui_chevron_right),
      contentDescription = null,
      Modifier.size(14.dp),
      tint = AppTheme.colors.more
    )
  }
}

@Composable
private fun NameRow() {
  val context = LocalContext.current
  var input by remember { mutableStateOf(MeProfile.name) }
  // 外部改过资料时同步回输入框
  LaunchedEffect(MeProfile.name) {
    if (MeProfile.name != input) input = MeProfile.name
  }
  val tooShort = input.length < MeProfile.NAME_MIN
  EditableRow(
    label = "昵称",
    value = input,
    hint = if (tooShort) "至少 ${MeProfile.NAME_MIN} 个字符" else "",
    hintColor = if (tooShort) AppTheme.colors.badge else AppTheme.colors.textSecondary,
    maxLength = MeProfile.NAME_MAX,
    onValueChange = { next ->
      input = next
      // 长度合法就立刻落盘,不需要保存键
      if (next.length in MeProfile.NAME_MIN..MeProfile.NAME_MAX && next != MeProfile.name) {
        MeProfile.setName(context, next)
      }
    }
  )
}

@Composable
private fun SignatureRow() {
  val context = LocalContext.current
  var input by remember { mutableStateOf(MeProfile.signature) }
  LaunchedEffect(MeProfile.signature) {
    if (MeProfile.signature != input) input = MeProfile.signature
  }
  val tooShort = input.length < MeProfile.SIGNATURE_MIN
  EditableRow(
    label = "签名",
    value = input,
    hint = if (tooShort) {
      "至少 ${MeProfile.SIGNATURE_MIN} 个字符"
    } else {
      ""
    },
    hintColor = if (tooShort) AppTheme.colors.badge else AppTheme.colors.textSecondary,
    maxLength = MeProfile.SIGNATURE_MAX,
    onValueChange = { next ->
      input = next
      if (next.length in MeProfile.SIGNATURE_MIN..MeProfile.SIGNATURE_MAX && next != MeProfile.signature) {
        MeProfile.setSignature(context, next)
      }
    }
  )
}


@Composable
private fun EditableRow(
  label: String,
  value: String,
  hint: String,
  hintColor: Color,
  maxLength: Int,
  onValueChange: (String) -> Unit
) {
  var focused by remember { mutableStateOf(false) }
  Column(
    Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp)
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(label, Modifier.width(64.dp), fontSize = 16.sp, color = AppTheme.colors.textPrimary)
      BasicTextField(
        value = value,
        onValueChange = { if (it.length <= maxLength) onValueChange(it) },
        modifier = Modifier
          .weight(1f)
          .onFocusChanged { focused = it.isFocused },
        textStyle = androidx.compose.ui.text.TextStyle(
          fontSize = 16.sp,
          color = AppTheme.colors.textPrimary,
          textAlign = TextAlign.End
        ),
        singleLine = true,
        cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { })
      )
    }
    if (focused && hint.isNotEmpty()) {
      Text(
        hint,
        Modifier
          .fillMaxWidth()
          .padding(top = 6.dp),
        fontSize = 11.sp,
        color = hintColor,
        textAlign = TextAlign.End
      )
    }
  }
}
