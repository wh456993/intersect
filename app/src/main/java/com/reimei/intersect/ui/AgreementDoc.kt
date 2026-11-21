package com.reimei.intersect.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reimei.intersect.data.LangSetting
import com.reimei.intersect.ui.theme.AppTheme

/**
 * 文档里的一节。
 *
 * [level] 为 1 是「部分」大标题（第一部分 / 第二部分 / 第三部分），2 是小节标题。
 * 页面是纯文本渲染，原文里的表格被摊平成一条条「条目：说明」的段落，
 * 移动端窄屏上比挤成几列更好读；条目顺序与原文一致。
 *
 * 段落文本支持两种轻量标记（见 [LinkText]）：`**重点**` 渲染为粗体，裸网址与邮箱自动变成可点链接。
 */
internal class DocBlock(
  val heading: String,
  val paragraphs: List<String>,
  val level: Int = 2
)

/** 取当前语言对应的整份文档。抽出来只为让单测能直接比对中英两份。 */
internal fun agreementDocFor(english: Boolean): AgreementDoc = if (english) EN_DOC else ZH_DOC

/**
 * 一整份协议：开头的效力声明、几行元信息、各节正文。
 *
 * 中英两种语言各存一份完整文档（见 [ZH_DOC] / [EN_DOC]），而不是「中文段落 + 英文对照表」——
 * 这样英文版的段落顺序、分节方式可以按英文习惯调整，也不必让法律文本走 t() 的查表逻辑。
 */
internal class AgreementDoc(
  val disclaimer: String,
  val meta: List<String>,
  val blocks: List<DocBlock>
) {
  /** 全文纯文本，按渲染顺序拼接（标题 + 段落），供测试校验内容。 */
  internal fun plainText(): String = buildString {
    append(disclaimer).append('\n')
    meta.forEach { append(it).append('\n') }
    blocks.forEach { block ->
      append(block.heading).append('\n')
      block.paragraphs.forEach { append(it).append('\n') }
    }
  }
}

/**
 * 效力声明，放在正文最前面，中英各一份。
 *
 * 这是双语法律文本的通用做法：英文只作阅读便利，一切解释以中文原文为准。
 * 万一翻译与原文有偏差（比如把「可能」译成了「一定会」），责任范围仍回到中文原文，
 * 不至于因为一句译文的强弱差异产生额外的义务。
 */
private const val DISCLAIMER_ZH =
  "**本协议以中文版本为准。**其他语言的翻译版本仅供参考；如与中文版有任何冲突，一律以中文版为准。"

private const val DISCLAIMER_EN =
  "**This agreement is governed by the Chinese version.** Translations into other languages " +
    "are provided for convenience of reading only. In case of any discrepancy between a " +
    "translation and the Chinese version, the Chinese version shall prevail."

private val ZH_DOC = AgreementDoc(
  disclaimer = DISCLAIMER_ZH,
  meta = listOf(
    "版本：v2026-11-21",
    "生效日期：2026-11-21",
    "完整文本：https://github.com/wh456993/intersect/blob/main/PRIVACY.md"
  ),
  blocks = listOf(

    DocBlock(
      "应用定位", listOf(
        "Intersect 是由 LuoMing 独立开发的开源、本地优先型 AI 对话与角色管理客户端（Android 包名 com.reimei.intersect）。开发者**不设立、不运营任何中心服务器**，不主动收集应用内用户数据；对话推理通过你自行配置的第三方 AI 端点完成，语义向量化在设备本地执行。本页为摘要，完整条款见文末链接。"
      )
    ),

    DocBlock(
      "数据存储", listOf(
        "角色配置、对话记录、记忆数据、用量记录与 API 密钥均保存在设备本地，你可随时在应用内查看、编辑或删除。Android 系统级备份**默认开启**（应用 Manifest 为 allowBackup=true 且未声明备份规则），上述数据可能随系统云备份或换机迁移一同离开设备；如需避免，请在系统设置中关闭本应用的备份。"
      )
    ),

    DocBlock(
      "网络请求", listOf(
        "应用的外部网络请求共 **6 类**，全部发往你自行配置的 AI 端点：主对话请求、微总结请求、大总结请求、连通性测试、拉取模型列表、旧链路总结（仅由开发者控制台触发）。不存在用于遥测、分析、广告、更新检查或远程配置的请求，应用也不会自动检查更新。"
      )
    ),

    DocBlock(
      "系统权限", listOf(
        "Manifest 共声明 9 项权限，用途与授予方式见完整文本第 10 章。其中 MANAGE_EXTERNAL_STORAGE 仅用于用量导出与开发日志导出向外部存储根目录写入文件（角色卡与聊天备份已走 SAF，不依赖该权限）；通知权限在当前目标 SDK 下不弹运行时权限窗，需在系统设置中手动开启。上述权限均可在系统设置中收回。"
      )
    ),

    DocBlock(
      "安全限制", listOf(
        "API 密钥当前以未加密形式存于应用私有目录，依赖 Android 沙箱隔离，不构成加密保险箱。发布构建已配置与调试签名**相互隔离**的独立 release 签名，CI 以 apksigner 校验证书指纹，不匹配则构建失败。排障日志（BugLog）位于应用外部私有目录，仅记录端点、模型、状态码与截断后的文本，不记录 Authorization 请求头。按平台默认行为，明文 HTTP 请求会被拒绝。"
      )
    ),

    DocBlock(
      "开源许可", listOf(
        "源代码依 Apache License 2.0 开源，全文见 http://www.apache.org/licenses/LICENSE-2.0，仓库根目录另有 LICENSE 与 NOTICE。项目名称、应用图标与启动图标等品牌素材不在该许可范围内，相关声明见仓库根目录的 BRANDING 文件。仓库地址：https://github.com/wh456993/intersect"
      )
    ),

    DocBlock(
      "完整文本与历史版本", listOf(
        "完整隐私政策与开源协议（v2026-11-21，生效日期 2026-11-21）见：https://github.com/wh456993/intersect/blob/main/PRIVACY.md 旧版 v2026-11-15 全文归档于仓库 docs/privacy/2026-11-15.md，供比对与追溯。意见与隐私请求请联系 wba456456@qq.com，或提交 Issue:https://github.com/wh456993/intersect/issues"
      )
    )
  )
)

/**
 * 英文版。
 *
 * 与中文版逐节对应（节数、编号、顺序一一对齐，方便对照阅读），效力以中文版为准，
 * 见 [DISCLAIMER_EN]。摘要版同样是「要点 + 完整文本链接」的结构：条款正文只在
 * PRIVACY.md 里维护一份，应用内不再重复整套条文，避免两处各改各的。
 */
private val EN_DOC = AgreementDoc(
  disclaimer = DISCLAIMER_EN,
  meta = listOf(
    "Version: v2026-11-21",
    "Effective date: 2026-11-21",
    "Full text: https://github.com/wh456993/intersect/blob/main/PRIVACY.md"
  ),
  blocks = listOf(

    DocBlock(
      "App Positioning", listOf(
        "Intersect is an open-source, local-first AI chat and character-management client independently developed by LuoMing (Android package com.reimei.intersect). The developer **operates no central server** and does not actively collect in-app user data; conversations are inferred through third-party AI endpoints that you configure yourself, and semantic embedding runs on the device. This page is a summary; the full terms are at the link below."
      )
    ),

    DocBlock(
      "Data Storage", listOf(
        "Character definitions, conversations, memory data, usage records and API keys are stored locally on your device, and you can view, edit or delete them in the App at any time. Android system-level backup is **enabled by default** (the App's manifest sets allowBackup=true and declares no backup rules), so this data may leave the device through system cloud backup or device migration; to prevent that, turn off backup for the App in system settings."
      )
    ),

    DocBlock(
      "Network Requests", listOf(
        "The App makes **6 kinds** of external network requests, all to endpoints you configure yourself: main chat, micro-summary, major summary, connectivity test, model-list fetch, and the legacy-pipeline summary (triggered only from the developer console). There are no requests for telemetry, analytics, advertising, update checks or remote configuration, and the App never checks for updates automatically."
      )
    ),

    DocBlock(
      "System Permissions", listOf(
        "The manifest declares 9 permissions; their purposes and grant methods are described in chapter 10 of the full text. MANAGE_EXTERNAL_STORAGE is used only to write usage exports and developer-log exports to the root of external storage (character cards and chat backups use SAF instead and do not depend on it); the notification permission shows no runtime prompt under the current target SDK and must be enabled manually in system settings. All of these permissions can be revoked in system settings."
      )
    ),

    DocBlock(
      "Security Limits", listOf(
        "API keys are currently stored unencrypted in the App's private directory and rely on the Android sandbox for isolation; that is not an encrypted vault. The release build uses an independent release signing key **kept separate** from the debug key, and CI verifies the certificate fingerprint with apksigner, failing the build on a mismatch. Troubleshooting logs (BugLog) live in the App's external private directory and record only the endpoint, model, status code and truncated text, never the Authorization header. Cleartext HTTP requests are rejected by platform default."
      )
    ),

    DocBlock(
      "Open-Source License", listOf(
        "The source code is open-sourced under the Apache License 2.0; the full text is at http://www.apache.org/licenses/LICENSE-2.0, and the repository root also holds the LICENSE and NOTICE files. Brand assets such as the project name, app icon and launcher icon are outside that licence; see the BRANDING file in the repository root. Repository: https://github.com/wh456993/intersect"
      )
    ),

    DocBlock(
      "Full Text and Version History", listOf(
        "The full privacy policy and open-source statement (v2026-11-21, effective 2026-11-21) are at: https://github.com/wh456993/intersect/blob/main/PRIVACY.md The previous version, v2026-11-15, is archived at docs/privacy/2026-11-15.md in the repository for comparison and traceability. For comments or privacy requests, contact wba456456@qq.com or open an issue: https://github.com/wh456993/intersect/issues"
      )
    )
  )
)

/**
 * 开源协议及隐私政策正文。
 *
 * 只负责画文档本身，滚动与页面留白由调用方决定（首次启动的协议页与
 * 「我 → 隐私政策」页共用这一份，不各抄一遍）。
 *
 * 正文按当前界面语言取 [ZH_DOC] 或 [EN_DOC],**不走 i18n 的查表翻译**:
 * 法律文本靠「中文原文当 key」查表，漏一条就静默退回中文、改错一条就是义务范围的偏差，
 * 两种都不可接受，所以两份摘要各自硬编码，再加一条「以中文版为准」的效力声明兜底。
 */
@Composable
fun AgreementDocument(modifier: Modifier = Modifier) {
  val primary = AppTheme.colors.textPrimary
  val secondary = AppTheme.colors.textSecondary
  // 读一次 LangSetting.english：切换语言时本组合会重组，正文随之换语言
  val doc = agreementDocFor(LangSetting.english)

  Column(modifier.fillMaxWidth()) {
    // 效力声明单独做成一枚浅底卡片，放在正文之前——它比任何一节都更该被先看到
    Column(
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(AppTheme.colors.listItem)
        .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
      LinkText(
        text = doc.disclaimer,
        fontSize = 12.sp,
        lineHeight = 19.sp,
        color = primary,
        emphasisColor = primary
      )
    }
    Spacer(Modifier.height(14.dp))

    doc.meta.forEach { line ->
      LinkText(line, fontSize = 12.sp, lineHeight = 18.sp, color = secondary)
      Spacer(Modifier.height(2.dp))
    }

    doc.blocks.forEachIndexed { index, block ->
      val isPart = block.level == 1
      if (index > 0) {
        // 部分级标题前留得多一些，小节之间只要一点呼吸
        Spacer(Modifier.height(if (isPart) 26.dp else 18.dp))
      }
      // 标题直接用 material 的 Text：文案已是最终语言，不该再过一遍 t()
      MaterialText(
        text = block.heading,
        fontSize = if (isPart) 18.sp else 15.sp,
        lineHeight = if (isPart) 26.sp else 22.sp,
        fontWeight = FontWeight.Bold,
        color = primary
      )
      block.paragraphs.forEach { paragraph ->
        Spacer(Modifier.height(8.dp))
        LinkText(
          paragraph,
          fontSize = 13.sp,
          lineHeight = 21.sp,
          color = secondary,
          emphasisColor = primary
        )
      }
    }
  }
}

// ---------------------------------------------------------------------------
// 链接与强调
// ---------------------------------------------------------------------------

/** 段落里可以点的那一段。用注解标签把「点击位置」映射回真实 URL。 */
internal const val TAG_URL = "url"

/**
 * 匹配三种可处理片段，靠一个正则从左到右一遍扫完（顺序即优先级）：
 *
 * 1. `**重点**` —— 去掉星号，渲染为粗体；
 * 2. `https://...` 这类裸网址 —— 渲染为可点链接；
 * 3. `name@host.tld` —— 渲染为 `mailto:` 链接，点击唤起邮箱应用。
 *
 * 网址的字符类里排掉了空白与标点：否则「详见 https://example.com，以官网为准」
 * 会把逗号一起当成网址的一部分。半角的 `:` / `?` 不在排除之列（端口与 query 要用），
 * 中英文两侧真正的句读则一并排除。
 */
private val LINK_TOKEN = Regex(
  "\\*\\*[^*]+\\*\\*" +
    "|https?://[^\\s,;()\\[\\]\\u3001\\u3002\\uFF0C\\uFF1A\\uFF1B\\uFF01\\uFF1F" +
    "\\uFF08\\uFF09\\u3010\\u3011\\u300A\\u300B\\u300C\\u300D\\u300E\\u300F]+" +
    "|[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"
)

/**
 * 把一段协议正文切成「普通文本 / 粗体 / 链接」三色富文本。
 *
 * 抽成纯函数是为了能单测：正则的边界（网址尾部的标点、邮箱的 mailto 前缀）
 * 光靠看界面看不出对错。渲染层见 [LinkText]。
 */
internal fun buildAgreementText(
  text: String,
  linkColor: Color,
  emphasisColor: Color
): AnnotatedString = buildAnnotatedString {
  var last = 0
  for (match in LINK_TOKEN.findAll(text)) {
    if (match.range.first > last) append(text.substring(last, match.range.first))
    val token = match.value
    if (token.startsWith("**") && token.length > 4) {
      withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = emphasisColor)) {
        append(token.substring(2, token.length - 2))
      }
    } else {
      // 邮箱补上 mailto：，系统才会按「发邮件」而不是「访问网页」处理
      val uri = if (token.contains('@')) "mailto:$token" else token
      pushStringAnnotation(tag = TAG_URL, annotation = uri)
      withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
        append(token)
      }
      pop()
    }
    last = match.range.last + 1
  }
  if (last < text.length) append(text.substring(last))
}

/**
 * 带链接的正文段落。
 *
 * 协议里的 GitHub 仓库、Apache 协议全文、Issue 地址与开发者邮箱如果只是纯文本，
 * 用户在手机上既选不中也点不动——它们恰恰是本应用唯一的外链，所以统一渲染成
 * 带下划线的主色文本，点击交给系统的 [androidx.compose.ui.platform.UriHandler]
 * （浏览器 / 邮件应用）。打不开时静默忽略：本机没装浏览器也不该让点击崩溃。
 *
 * 用 [ClickableText] 而不是 `Text` + `LinkAnnotation`:`LinkAnnotation` 要
 * Compose 1.7+，本工程钉在 1.5.4。
 *
 * 文本在 [remember] 里构建一次：协议全文字数不少，重组时不该重跑正则。
 */
@Composable
private fun LinkText(
  text: String,
  fontSize: TextUnit,
  lineHeight: TextUnit,
  color: Color,
  emphasisColor: Color = color
) {
  val uriHandler = LocalUriHandler.current
  val linkColor = AppTheme.colors.iconCurrent
  val style = TextStyle(fontSize = fontSize, lineHeight = lineHeight, color = color)

  val annotated = remember(text, linkColor, emphasisColor) {
    buildAgreementText(text, linkColor, emphasisColor)
  }

  ClickableText(
    text = annotated,
    style = style,
    onClick = { offset ->
      annotated.getStringAnnotations(TAG_URL, offset, offset).firstOrNull()?.let { hit ->
        runCatching { uriHandler.openUri(hit.item) }
      }
    }
  )
}
