package com.reimei.intersect.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.PREFS_NAME
import com.reimei.intersect.i18n.Text
import com.reimei.intersect.revokeAgreement
import com.reimei.intersect.ui.theme.AppTheme

/**
 * 只读模式的《开源协议及隐私政策》页面:「我 → 隐私政策」点进来的就是这一页。
 *
 * 与开屏页(Activation.kt 的 AgreementPage)共用同一份正文 [AgreementDocument],
 * 区别只在页框:
 * - 这里有顶栏与返回键,返回键**返回上一级**(绝不退出应用);
 * - 没有底部「同意」箭头——协议早已同意过,这里只供查阅;
 * - 底部多一条「撤销授权」:二次确认后清掉同意标记并退出应用。
 *
 * 撤销**不会**删除任何数据(角色、聊天记录、API KEY 全部保留),
 * 只是让下次启动重新回到开屏协议页,重新同意即可继续使用。
 */
@Composable
fun PrivacyPolicyPage(modifier: Modifier = Modifier, onBack: () -> Unit) {
  val context = LocalContext.current
  val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
  var showRevokeConfirm by remember { mutableStateOf(false) }

  // 只读模式:返回键回到「我」页。这里的动作绝不能是退出应用。
  BackHandler { onBack() }

  Column(
    modifier
      .fillMaxSize()
      .blockTouchesThrough()
      .background(AppTheme.colors.background)
  ) {
    TitleBar(title = "隐私政策", onBack = onBack)
    Column(
      Modifier
        .weight(1f)
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 32.dp)
    ) {
      Spacer(Modifier.height(8.dp))
      AgreementDocument()
      Spacer(Modifier.height(28.dp))
      RevokeAgreementRow(onClick = { showRevokeConfirm = true })
      Spacer(Modifier.height(28.dp))
    }
  }

  if (showRevokeConfirm) {
    AlertDialog(
      onDismissRequest = { showRevokeConfirm = false },
      title = {
        Text("确认撤销授权?", fontSize = 16.sp, color = AppTheme.colors.textPrimary)
      },
      text = {
        Text(
          "撤销后应用会立即退出,不会删除你的角色、聊天记录或 API KEY。下次打开需要重新阅读并同意本协议才能进入。",
          fontSize = 13.sp,
          lineHeight = 20.sp,
          color = AppTheme.colors.textSecondary
        )
      },
      confirmButton = {
        Text(
          "撤销并退出",
          Modifier
            .clickable {
              showRevokeConfirm = false
              context.revokeAgreement(prefs)
              // 与开屏页的返回键同一条路径:整个任务退出。
              // 常驻服务可能让进程活着,但下次启动一定重新落在开屏协议页。
              context.findActivity()?.finishAffinity()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.badge
        )
      },
      dismissButton = {
        Text(
          "取消",
          Modifier
            .clickable { showRevokeConfirm = false }
            .padding(horizontal = 12.dp, vertical = 8.dp),
          fontSize = 15.sp,
          color = AppTheme.colors.textSecondary
        )
      }
    )
  }
}

/** 撤销授权入口:卡片样式与「我」页的功能入口一致,文字取警示色。 */
@Composable
private fun RevokeAgreementRow(onClick: () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .background(AppTheme.colors.listItem)
      .clickable(onClick = onClick)
      .padding(horizontal = 18.dp, vertical = 16.dp)
  ) {
    Text(
      "撤销授权",
      fontSize = 16.sp,
      fontWeight = FontWeight.Medium,
      color = AppTheme.colors.badge
    )
    Spacer(Modifier.height(4.dp))
    Text(
      "清空本机记录的同意状态并退出应用,不会删除任何数据;下次打开需重新同意本协议",
      fontSize = 12.sp,
      lineHeight = 18.sp,
      color = AppTheme.colors.textSecondary
    )
  }
}
