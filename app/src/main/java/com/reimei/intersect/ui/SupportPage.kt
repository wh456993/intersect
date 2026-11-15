package com.reimei.intersect.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.R
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.ui.theme.AppTheme

/**
 * 「支持开发者」页:作者名片 + 三个外链入口(GitHub 仓库 / QQ 邮箱 / 抖音)。
 *
 * 地址是写死的常量——它们属于作者本人,不随用户数据或设置变化,也没有编辑入口。
 * 点哪一项就用系统的 UriHandler 打开(GitHub 与抖音走浏览器,邮箱走邮件应用);
 * 打不开时静默忽略,本机没装浏览器也不该让点击崩溃(与协议页里的外链一致)。
 */
private const val GITHUB_URL = "https://github.com/wh456993"
private const val MAIL_URL = "mailto:wba456456@qq.com"
private const val DOUYIN_URL = "https://v.douyin.com/m4kPEP9U3rc/"

@Composable
fun SupportPage(modifier: Modifier = Modifier, onBack: () -> Unit) {
  BackHandler { onBack() }
  val uriHandler = LocalUriHandler.current
  val open: (String) -> Unit = { uri -> runCatching { uriHandler.openUri(uri) } }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    TitleBar(title = "支持开发者", onBack = onBack)
    Column(
      Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
    ) {
      SupportHeader()
      Column(
        Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp)
          .clip(RoundedCornerShape(12.dp))
          .background(AppTheme.colors.listItem)
      ) {
        // 显示的值去掉协议前缀:一行放得下,点开的目标仍然是完整地址
        LinkRow(
          icon = R.drawable.ui_link_github,
          label = "开源仓库",
          value = "github.com/wh456993",
          onClick = { open(GITHUB_URL) }
        )
        LinkDivider()
        LinkRow(
          icon = R.drawable.ui_link_mail,
          label = "QQ 邮箱",
          value = "wba456456@qq.com",
          onClick = { open(MAIL_URL) }
        )
        LinkDivider()
        LinkRow(
          icon = R.drawable.ui_link_douyin,
          label = "抖音",
          value = "v.douyin.com/m4kPEP9U3rc",
          onClick = { open(DOUYIN_URL) }
        )
      }
      Text(
        "点击任一项会在浏览器或对应的应用里打开",
        Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        fontSize = 12.sp,
        color = AppTheme.colors.textSecondary
      )
    }
  }
}

/** 顶部的作者名片:圆形头像 + 仓库名。 */
@Composable
private fun SupportHeader() {
  Column(
    Modifier
      .fillMaxWidth()
      .padding(top = 24.dp, bottom = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // 外圈是一道细环,头像与页面底色之间不至于糊成一片
    Box(
      Modifier
        .size(104.dp)
        .border(1.dp, AppTheme.colors.chatListDivider, CircleShape)
        .padding(4.dp)
        .clip(CircleShape)
    ) {
      Image(
        painterResource(R.drawable.support_avatar),
        contentDescription = "作者头像",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop
      )
    }
    Spacer(Modifier.height(12.dp))
    Text(
      "LuoMing",
      fontSize = 20.sp,
      fontWeight = FontWeight.Medium,
      color = AppTheme.colors.textPrimary
    )
    Spacer(Modifier.height(4.dp))
    Text(
      "intersect 作者 · 欢迎来仓库看看",
      fontSize = 12.sp,
      color = AppTheme.colors.textSecondary
    )
  }
}

@Composable
private fun LinkDivider() {
  Box(
    Modifier
      .fillMaxWidth()
      .padding(start = 50.dp)
      .height(1.dp)
      .background(AppTheme.colors.chatListDivider)
  )
}

@Composable
private fun LinkRow(
  icon: Int,
  label: String,
  value: String,
  onClick: () -> Unit
) {
  Row(
    Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    Icon(
      painterResource(icon),
      contentDescription = null,
      Modifier.size(22.dp),
      tint = AppTheme.colors.textPrimary
    )
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text(label, fontSize = 16.sp, color = AppTheme.colors.textPrimary)
      Spacer(Modifier.height(2.dp))
      Text(
        value,
        fontSize = 12.sp,
        color = AppTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    }
    Spacer(Modifier.width(8.dp))
    Icon(
      painterResource(R.drawable.ui_chevron_right),
      contentDescription = null,
      Modifier.size(14.dp),
      tint = AppTheme.colors.more
    )
  }
}
