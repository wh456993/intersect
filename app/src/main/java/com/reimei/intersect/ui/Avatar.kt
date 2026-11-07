package com.reimei.intersect.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.reimei.intersect.data.User
import java.io.File

/**
 * 统一的用户头像渲染(圆形):
 * - 自己(User.Me)走 MeProfile 的自定义头像
 * - 好友有自定义头像文件时解码显示,否则用内置 drawable
 */
@Composable
fun UserAvatar(
  user: User,
  modifier: Modifier = Modifier,
  size: Dp = 40.dp,
  shape: Shape = CircleShape
) {
  if (user.id == User.Me.id) {
    MeAvatar(modifier = modifier, size = size, shape = shape)
    return
  }
  val path = user.avatarPath
  val bitmap = remember(path) {
    path?.let { p ->
      val file = File(p)
      if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }
  }
  Box(modifier.size(size).clip(shape)) {
    if (bitmap != null) {
      Image(
        bitmap.asImageBitmap(),
        contentDescription = user.name,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )
    } else {
      Image(
        painterResource(user.avatar),
        contentDescription = user.name,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )
    }
  }
}

