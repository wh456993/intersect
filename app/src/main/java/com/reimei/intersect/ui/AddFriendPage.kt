package com.reimei.intersect.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.reimei.intersect.R
import com.reimei.intersect.data.FriendsStore
import com.reimei.intersect.data.RoleplayMode
import com.reimei.intersect.data.User
import com.reimei.intersect.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 添加角色页:填昵称、改头像、写签名。
 * 昵称必填(3-20 字),签名可留空,填了就是 5-50 字。
 */
@Composable
fun AddFriendPage(
  modifier: Modifier = Modifier,
  onBack: () -> Unit,
  onConfirm: (User) -> Unit
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val friendId = remember { FriendsStore.newFriendId() }

  var name by remember { mutableStateOf("") }
  var signature by remember { mutableStateOf("") }
  var avatarBitmap by remember { mutableStateOf<Bitmap?>(null) }
  var pendingCrop by remember { mutableStateOf<Bitmap?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var mode by remember { mutableStateOf(RoleplayMode.AVATAR_BASED) }

  val openGallery = rememberGalleryPicker(
    onPicked = { pendingCrop = it },
    onError = { error = "无法读取这张图片,请换一张试试" }
  )

  BackHandler { if (pendingCrop == null) onBack() }

  if (pendingCrop != null) {
    CropImageScreen(
      bitmap = pendingCrop!!,
      onCancel = { pendingCrop = null },
      onDone = { cropped ->
        pendingCrop = null
        avatarBitmap = cropped
      }
    )
    return
  }

  val trimmedName = name.trim()
  val nameOk = trimmedName.length in 3..20
  val trimmedSignature = signature.trim()
  val signatureOk = trimmedSignature.isEmpty() || trimmedSignature.length in 5..50
  val canConfirm = nameOk && signatureOk

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    EditTopBar(
      title = "添加角色",
      actionText = "完成",
      actionEnabled = canConfirm,
      onBack = onBack,
      onAction = {
        if (!canConfirm) return@EditTopBar
        scope.launch {
          val avatarPath = if (avatarBitmap != null) {
            withContext(Dispatchers.IO) {
              val file = FriendsStore.avatarFile(context, friendId)
              saveBitmapToFile(avatarBitmap!!, file)
              file.absolutePath
            }
          } else {
            null
          }
          onConfirm(
            User(
              id = friendId,
              name = trimmedName,
              avatar = R.drawable.ui_avatar_blank,
              avatarPath = avatarPath,
              signature = trimmedSignature.ifEmpty { null },
              roleplayMode = mode,
            )
          )
        }
      }
    )

    Column(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(AppTheme.colors.listItem)
    ) {
      Row(
        Modifier
          .fillMaxWidth()
          .clickable(onClick = openGallery)
          .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text("头像", Modifier.weight(1f), fontSize = 16.sp, color = AppTheme.colors.textPrimary)
        AvatarPreview(avatarBitmap)
        Spacer(Modifier.width(8.dp))
        Icon(
          painterResource(R.drawable.ui_chevron_right),
          contentDescription = null,
          Modifier.size(14.dp),
          tint = AppTheme.colors.more
        )
      }
      Box(
        Modifier
          .fillMaxWidth()
          .padding(start = 16.dp)
          .height(1.dp)
          .background(AppTheme.colors.chatListDivider)
      )
      ProfileEditRow(
        label = "昵称",
        value = name,
        maxLength = 20,
        onValueChange = { name = it }
      )
      Box(
        Modifier
          .fillMaxWidth()
          .padding(start = 16.dp)
          .height(1.dp)
          .background(AppTheme.colors.chatListDivider)
      )
      ProfileEditRow(
        label = "签名",
        value = signature,
        maxLength = 50,
        onValueChange = { signature = it }
      )
    }

    Spacer(Modifier.height(12.dp))

    // 扮演模式:Avatar-based 保持现状;Self-insert 由时间管理器注入每轮对话的本地时间
    Column(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(AppTheme.colors.listItem)
    ) {
      Text(
        "扮演模式",
        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        fontSize = 13.sp,
        color = AppTheme.colors.textSecondary
      )
      ChoiceRow(
        title = "Avatar-based Role-Play",
        selected = mode == RoleplayMode.AVATAR_BASED,
        onClick = { mode = RoleplayMode.AVATAR_BASED }
      )
      ChoiceDivider()
      ChoiceRow(
        title = "Self-insert Role-Play",
        selected = mode == RoleplayMode.SELF_INSERT,
        onClick = { mode = RoleplayMode.SELF_INSERT }
      )
    }

    if (error != null) {
      Text(
        error!!,
        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        fontSize = 12.sp,
        color = AppTheme.colors.badge
      )
    }
  }
}

@Composable
private fun AvatarPreview(bitmap: Bitmap?) {
  Box(
    Modifier
      .size(52.dp)
      .clip(CircleShape)
  ) {
    if (bitmap != null) {
      Image(
        bitmap.asImageBitmap(),
        contentDescription = "头像",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )
    } else {
      Image(
        painterResource(R.drawable.ui_avatar_blank),
        contentDescription = "头像",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )
    }
  }
}

/** 标题栏:返回 + 标题 + 右侧动作(完成不可点时变淡)。 */
@Composable
fun EditTopBar(
  title: String,
  actionText: String,
  actionEnabled: Boolean,
  onBack: () -> Unit,
  onAction: () -> Unit
) {
  Box(
    Modifier
      .fillMaxWidth()
      .background(AppTheme.colors.background)
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
      title,
      Modifier
        .align(Alignment.Center)
        .padding(vertical = 14.dp),
      fontSize = 17.sp,
      fontWeight = FontWeight.Medium,
      color = AppTheme.colors.textPrimary
    )
    // 右上角原本就有「完成」
    Row(
      Modifier.align(Alignment.CenterEnd),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        actionText,
        Modifier
          .clickable(enabled = actionEnabled, onClick = onAction)
          .padding(horizontal = 16.dp, vertical = 14.dp),
        fontSize = 15.sp,
        color = if (actionEnabled) AppTheme.colors.iconCurrent else AppTheme.colors.more
      )
    }
  }
}

/** 右侧对齐的单行输入行,聚焦时在下面显示字数提示。 */
@Composable
fun ProfileEditRow(
  label: String,
  value: String,
  maxLength: Int,
  onValueChange: (String) -> Unit,
  hint: String = "",
  hintColor: Color = AppTheme.colors.textSecondary
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
        textStyle = TextStyle(
          fontSize = 16.sp,
          color = AppTheme.colors.textPrimary,
          textAlign = TextAlign.End
        ),
        singleLine = true,
        cursorBrush = SolidColor(AppTheme.colors.iconCurrent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
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
