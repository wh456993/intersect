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
 * [level] 为 1 是「部分」大标题(第一部分 / 第二部分 / 第三部分),2 是小节标题。
 * 页面是纯文本渲染,原文里的表格被摊平成一条条「条目:说明」的段落,
 * 移动端窄屏上比挤成几列更好读;条目顺序与原文一致。
 *
 * 段落文本支持两种轻量标记(见 [LinkText]):`**重点**` 渲染为粗体,裸网址与邮箱自动变成可点链接。
 */
internal class DocBlock(
  val heading: String,
  val paragraphs: List<String>,
  val level: Int = 2
)

/** 取当前语言对应的整份文档。抽出来只为让单测能直接比对中英两份。 */
internal fun agreementDocFor(english: Boolean): AgreementDoc = if (english) EN_DOC else ZH_DOC

/**
 * 一整份协议:开头的效力声明、几行元信息、各节正文。
 *
 * 中英两种语言各存一份完整文档(见 [ZH_DOC] / [EN_DOC]),而不是「中文段落 + 英文对照表」——
 * 这样英文版的段落顺序、分节方式可以按英文习惯调整,也不必让法律文本走 t() 的查表逻辑。
 */
internal class AgreementDoc(
  val disclaimer: String,
  val meta: List<String>,
  val blocks: List<DocBlock>
) {
  /** 全文纯文本,按渲染顺序拼接(标题 + 段落),供测试校验内容。 */
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
 * 效力声明,放在正文最前面,中英各一份。
 *
 * 这是双语法律文本的通用做法:英文只作阅读便利,一切解释以中文原文为准。
 * 万一翻译与原文有偏差(比如把「可能」译成了「一定会」),责任范围仍回到中文原文,
 * 不至于因为一句译文的强弱差异产生额外的义务。
 */
private const val DISCLAIMER_ZH =
  "**本协议以中文版本为准。**其他语言的翻译版本仅供参考;如与中文版有任何冲突,一律以中文版为准。"

private const val DISCLAIMER_EN =
  "**This agreement is governed by the Chinese version.** Translations into other languages " +
    "are provided for convenience of reading only. In case of any discrepancy between a " +
    "translation and the Chinese version, the Chinese version shall prevail."

private val ZH_DOC = AgreementDoc(
  disclaimer = DISCLAIMER_ZH,
  meta = listOf(
    "生效日期:2026 年 11 月 7 日",
    "最后更新:2026 年 11 月 7 日",
    "开发者:LuoMing(GitHub:https://github.com/wh456993)",
    "适用应用:intersect(Android 包名 com.reimei.intersect)"
  ),
  blocks = listOf(

    DocBlock(
      "引言与定义", listOf(
        "1.1 欢迎使用 intersect(以下简称「本应用」)。本应用是由开发者 LuoMing 独立开发的一款开源的、纯本地的 AI 对话与角色管理客户端。",
        "1.2 本文件包含《隐私政策》与《开源协议声明》两大部分,旨在清晰、透明地说明本应用的数据处理原则与开源许可条款。",
        "1.3 请在开始使用本应用前仔细阅读本文件全部内容。你下载、安装、启动或以其他方式使用本应用,即视为你已同意本文件所述条款。"
      )
    ),

    DocBlock(
      "第一部分 隐私政策",
      listOf(
        "本应用秉持「**无中心服务器、纯本地存储、不收集数据**」的核心原则。开发者未设立、运营或控制任何用于收集、存储、中转用户数据的中央服务器;本应用不含账号体系、广告 SDK 与统计分析 SDK。"
      ),
      level = 1
    ),

    DocBlock(
      "数据的存储、控制与销毁", listOf(
        "2.1 本地化存储:你在使用本应用过程中产生的全部数据,包括角色设定、提示词、聊天记录、API Key、用量账本、应用偏好设置与本地备份文件,均仅保存在你的设备本地存储空间中。",
        "2.2 数据控制权:你对自己的本地数据拥有完全控制权,可随时在应用内编辑或删除角色、清除会话记录、增删 API Key。",
        "2.3 数据销毁:卸载本应用或清除应用数据,**将永久抹除本应用在你设备上存储的全部信息**。由于开发者不存在云端副本,此操作不可逆,开发者无法也无需提供「云端数据删除」服务。"
      )
    ),

    DocBlock(
      "网络连接与第三方服务", listOf(
        "3.1 点对点直连:本应用唯一的外部网络行为,是作为客户端向你自行配置的 AI 服务端点(如 OpenAI、Google Gemini、Anthropic,或你自建/自托管的兼容 API)发起 HTTPS 请求。",
        "3.2 无中转服务器:你的对话内容与 API Key 由设备直接发送至你所填写的服务端点,**不经过任何由开发者控制或运营的中转服务器**。",
        "3.3 无附加上报:截至本文档所述版本,本应用不会主动访问开发者或代码托管平台的服务器,不具备自动检查更新的网络行为,也不上报崩溃日志与使用统计。",
        "3.4 第三方约束:你所接入的 AI 服务商对数据的收集、处理与存储行为,受其自身隐私政策与服务条款约束。开发者无法干预且不承担相关责任,请在配置前审慎阅读对应服务商的条款。",
        "3.5 本地密钥存储:API Key 保存在本应用的私有数据目录中,除用于向你配置的端点发起请求外不会被外发。请注意这是应用层隔离,不是加密保险箱:已 root 的设备或被他人物理掌控的设备,其数据安全由你自行负责。"
      )
    ),

    DocBlock(
      "系统权限说明", listOf(
        "本应用的权限申请与用途如下,均与实际功能一一对应;你可随时在系统设置中收回授权(收回后相关功能不可用,其余功能不受影响)。",
        "INTERNET:向你配置的 AI 服务端点发送请求、接收回复(发起对话时)。",
        "POST_NOTIFICATIONS:前台常驻服务在通知栏显示一条最低优先级的运行提示(后台常驻服务启动时)。",
        "FOREGROUND_SERVICE:以低优先级前台服务维持后台连接,降低被系统回收的概率(后台常驻服务启动时)。",
        "RECEIVE_BOOT_COMPLETED:开机或应用更新后恢复常驻服务(设备开机 / 应用升级后)。",
        "SCHEDULE_EXACT_ALARM:服务被系统清理后,通过精确闹钟自我唤回(常驻服务被回收后)。",
        "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS:请求加入电池优化白名单,避免长时间生成被中断(你主动在设置中开启时)。",
        "MANAGE_EXTERNAL_STORAGE:导出/导入角色卡与聊天备份文件(仅在你主动执行导入导出时)。",
        "READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE:兼容 Android 10 及以下的旧系统存储访问(同用户主动导入导出时,仅 maxSdkVersion=29)。",
        "补充说明:",
        "· 通知仅用于指示常驻服务的运行状态,不含对话内容,也不会推送营销信息。",
        "· **本应用不会在后台静默扫描设备文件**;文件访问严格发生在你主动点选导入、导出时。",
        "· 图片仅用于角色头像:你选定的图片在本地完成裁剪与缩放后以文件形式保存,不会作为消息内容上传给 AI 服务商(当前版本不支持图文消息)。"
      )
    ),

    DocBlock(
      "未成年人保护", listOf(
        "本应用不面向 13 周岁以下的未成年人提供服务。若你为未成年人,请在监护人陪同下阅读本政策,并在取得监护人同意后使用本应用。"
      )
    ),

    DocBlock(
      "第二部分 开源协议声明与致谢",
      listOf("intersect 的诞生离不开开源社区的支持,我们也坚定地回馈开源社区。"),
      level = 1
    ),

    DocBlock(
      "本项目开源协议", listOf(
        "本应用源代码托管于 GitHub 仓库:https://github.com/wh456993/intersect",
        "本项目遵循 Apache License 2.0 协议开源。你可以在仓库根目录查阅完整的 LICENSE 文件与 NOTICE 文件,或访问 http://www.apache.org/licenses/LICENSE-2.0 查看协议全文。",
        "在遵守本协议的前提下,你被允许自由审查、修改、分发本项目的代码,包括商业性使用。任何分发行为必须:保留原始版权声明与协议文本;随附 NOTICE 文件(如有);若修改过源文件,需在修改的文件中注明变更;不得使用本项目的商标或作者名义为衍生品背书。",
        "若无特别说明,本仓库中的源代码以上述协议授权,但不包含项目名称、应用图标与启动图标等标识性素材,这些素材不可在衍生品中暗示与原项目存在官方关联。"
      )
    ),

    DocBlock(
      "第三方开源组件致谢", listOf(
        "本应用使用的第三方组件均以 Apache License 2.0 授权,特此致谢:",
        "Jetpack Compose(foundation / material / ui / animation)1.5.4:声明式界面。",
        "AndroidX Core-KTX 1.10.0:基础扩展。",
        "AndroidX Activity / Activity-Compose 1.8.0:界面宿主与 Compose 接入。",
        "AndroidX AppCompat 1.6.1:兼容层。",
        "AndroidX Lifecycle(runtime / viewmodel)2.6.1:状态与生命周期管理。",
        "Material Components 1.13.0:主题与控件基础。",
        "AndroidX ExifInterface 1.3.2:头像图片方向校正。",
        "Kotlin 标准库与 kotlinx.coroutines(随上述组件引入):语言运行时与协程。",
        "说明:网络通信由 Android 平台自带的 HttpURLConnection 与 org.json 实现,未引入 OkHttp、Retrofit 等第三方网络库;项目亦未使用任何第三方 UI 框架、图片加载库或数据持久化框架。分发 APK 时请保留上述组件的许可声明。"
      )
    ),

    DocBlock(
      "免责声明", listOf(
        "8.1 本应用为开源软件,按「原样(As-Is)」提供,不附带任何明示或暗示的担保,包括但不限于可商用性、特定用途适用性与无侵权的担保。",
        "8.2 开发者不对使用过程中产生的数据丢失、设备异常,以及你向第三方 AI 服务商产生的任何费用负责。API 调用产生的费用由你与服务商之间的约定决定。",
        "8.3 本应用通过代码托管平台直接分发(侧载)。请确保安装包来源可靠;因使用非官方渠道下载的篡改版本导致的任何损失,开发者概不负责。",
        "8.4 本应用生成的对话内容由第三方 AI 模型产出,不代表开发者观点;请自行判断内容准确性,勿将其用于医疗、法律、金融等专业决策。",
        "8.5 当前 release 构建与 debug 构建共用本机调试密钥,便于覆盖安装、不丢数据。此类签名包不适合上架应用商店,若你计划上架或正式对外分发,请自行使用独立签名重新构建。"
      )
    ),

    DocBlock("第三部分 政策更新与联系方式", emptyList(), level = 1),

    DocBlock(
      "政策更新", listOf(
        "开发者保留不时更新本文件的权利。修订会通过仓库提交更新,并修改本文档顶部的「最后更新」日期;涉及数据处理方式的重大变更,将在版本更新说明(Release Notes)中公示。若你不接受修订后的内容,请停止使用并卸载本应用。"
      )
    ),

    DocBlock(
      "联系方式", listOf(
        "如你对本文件有疑问、意见,或在审查代码时发现安全与隐私问题,请通过以下方式联系开发者:",
        "· 提交 Issue:https://github.com/wh456993/intersect/issues",
        "· 开发者邮箱:wba456456@qq.com"
      )
    )
  )
)

/**
 * 英文版。
 *
 * 与中文版逐节对应(节数、编号、顺序一一对齐,方便对照阅读),效力以中文版为准,
 * 见 [DISCLAIMER_EN]。用词刻意保守:凡是中文写「不外发 / 不经过」的地方,英文也写
 * "is not transmitted / does not pass through",不用 may 之类会扩大义务的措辞。
 */
private val EN_DOC = AgreementDoc(
  disclaimer = DISCLAIMER_EN,
  meta = listOf(
    "Effective date: November 7, 2026",
    "Last updated: November 7, 2026",
    "Developer: LuoMing (GitHub: https://github.com/wh456993)",
    "Applies to: intersect (Android package com.reimei.intersect)"
  ),
  blocks = listOf(

    DocBlock(
      "Introduction and Definitions", listOf(
        "1.1 Welcome to intersect (“the App”). The App is an open-source, fully local AI chat and character-management client independently developed by LuoMing.",
        "1.2 This document consists of two parts — the Privacy Policy and the Open-Source License Statement — and sets out the App's data-handling principles and open-source licensing terms clearly and transparently.",
        "1.3 Please read this document in full before you start using the App. By downloading, installing, launching or otherwise using the App, you are deemed to have agreed to the terms described in this document."
      )
    ),

    DocBlock(
      "Part 1 Privacy Policy",
      listOf(
        "The App is built on a core principle of **no central server, purely local storage and no data collection**. The developer has not set up, operates or controls any central server used to collect, store or relay user data; the App contains no account system, no advertising SDK and no analytics SDK."
      ),
      level = 1
    ),

    DocBlock(
      "Data Storage, Control and Deletion", listOf(
        "2.1 Local storage: all data you generate while using the App — including character definitions, prompts, chat history, API keys, usage ledgers, app preferences and local backup files — is stored only in your device's local storage.",
        "2.2 Control of your data: you have full control over your local data and may at any time edit or delete characters, clear conversations, and add or remove API keys within the App.",
        "2.3 Data destruction: uninstalling the App or clearing its app data **permanently erases all information the App has stored on your device**. Because the developer holds no cloud copy, this operation is irreversible, and the developer can provide no “cloud data deletion” service and is not required to."
      )
    ),

    DocBlock(
      "Network Connections and Third-Party Services", listOf(
        "3.1 Direct client connections: the App's only external network activity is to act as a client issuing HTTPS requests to the AI service endpoints you configure yourself (such as OpenAI, Google Gemini, Anthropic, or a compatible API you host or self-host).",
        "3.2 No relay server: your conversation content and API keys are sent from your device directly to the endpoint you entered, and **do not pass through any relay server controlled or operated by the developer**.",
        "3.3 No additional reporting: as of the version described in this document, the App does not proactively contact the developer's servers or any code-hosting platform's servers, has no automatic update-check behaviour, and reports neither crash logs nor usage statistics.",
        "3.4 Third-party constraints: how the AI providers you connect to collect, process and store data is governed by their own privacy policies and terms of service. The developer cannot intervene and accepts no related liability; please read the relevant provider's terms carefully before configuring it.",
        "3.5 Local key storage: API keys are stored in the App's private data directory and are not transmitted other than for requests to the endpoints you configured. Note that this is application-level isolation, not an encrypted vault: on rooted devices, or devices physically controlled by others, the security of your data is your own responsibility."
      )
    ),

    DocBlock(
      "System Permissions", listOf(
        "The App requests the following permissions, each corresponding one-to-one to a real feature. You may revoke any of them at any time in system settings (revoking disables the related feature; other features are unaffected).",
        "INTERNET: send requests to and receive replies from the AI endpoints you configure (when you start a conversation).",
        "POST_NOTIFICATIONS: show a minimum-priority running indicator for the foreground service in the notification shade (when the background service starts).",
        "FOREGROUND_SERVICE: maintain the background connection with a low-priority foreground service, reducing the chance of the process being reclaimed (when the background service starts).",
        "RECEIVE_BOOT_COMPLETED: restore the background service after boot or an app update (after device boot / app upgrade).",
        "SCHEDULE_EXACT_ALARM: wake the service back up through an exact alarm after the system clears it (after the background service is reclaimed).",
        "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: request an exemption from battery optimisation so that long generations are not interrupted (when you enable it yourself in settings).",
        "MANAGE_EXTERNAL_STORAGE: export/import character cards and chat backup files (only when you start an import or export yourself).",
        "READ_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE: legacy storage access for Android 10 and below (same user-initiated import/export; maxSdkVersion=29 only).",
        "Additional notes:",
        "· Notifications serve only to indicate the running state of the background service; they contain no conversation content and never push marketing messages.",
        "· **The App does not silently scan your device's files in the background**; file access occurs strictly when you actively pick a file to import or export.",
        "· Images are used only for character avatars: the image you pick is cropped and scaled locally and saved as a file; it is not uploaded to any AI provider as message content (the current version does not support image messages)."
      )
    ),

    DocBlock(
      "Protection of Minors", listOf(
        "The App is not intended for minors under 13 years of age. If you are a minor, please read this policy together with your guardian, and use the App only after obtaining your guardian's consent."
      )
    ),

    DocBlock(
      "Part 2 Open-Source License Statement and Acknowledgements",
      listOf("intersect would not exist without the open-source community, and we firmly give back to it."),
      level = 1
    ),

    DocBlock(
      "License of This Project", listOf(
        "The App's source code is hosted in the GitHub repository: https://github.com/wh456993/intersect",
        "This project is open-sourced under the Apache License 2.0. You can read the full LICENSE and NOTICE files in the repository root, or view the full licence text at http://www.apache.org/licenses/LICENSE-2.0",
        "Subject to compliance with this licence, you are free to review, modify and distribute this project's code, including for commercial use. Any distribution must: retain the original copyright notices and licence text; include the NOTICE file (if any); state the changes in any source files you have modified; and must not use this project's trademarks or the author's name to endorse derivatives.",
        "Unless otherwise stated, the source code in this repository is licensed under the licence above; this does not cover identifying assets such as the project name, app icon and launcher icon, which may not be used in derivatives in a way that implies an official association with the original project."
      )
    ),

    DocBlock(
      "Third-Party Open-Source Components", listOf(
        "The third-party components used by the App are all licensed under the Apache License 2.0. Our thanks to:",
        "Jetpack Compose (foundation / material / ui / animation) 1.5.4: declarative UI.",
        "AndroidX Core-KTX 1.10.0: foundational extensions.",
        "AndroidX Activity / Activity-Compose 1.8.0: UI host and Compose integration.",
        "AndroidX AppCompat 1.6.1: compatibility layer.",
        "AndroidX Lifecycle (runtime / viewmodel) 2.6.1: state and lifecycle management.",
        "Material Components 1.13.0: theme and widget foundation.",
        "AndroidX ExifInterface 1.3.2: avatar image orientation correction.",
        "The Kotlin standard library and kotlinx.coroutines (pulled in by the components above): language runtime and coroutines.",
        "Note: networking is implemented with the Android platform's built-in HttpURLConnection and org.json; no third-party networking libraries such as OkHttp or Retrofit are used, and the project uses no third-party UI framework, image-loading library or data-persistence framework. Please retain the licence notices of the components above when redistributing the APK."
      )
    ),

    DocBlock(
      "Disclaimer", listOf(
        "8.1 The App is open-source software provided “as is”, without warranty of any kind, express or implied, including but not limited to the warranties of merchantability, fitness for a particular purpose and non-infringement.",
        "8.2 The developer is not liable for data loss or device malfunction arising during use, nor for any fees you incur with third-party AI providers. Fees for API calls are determined by your agreement with each provider.",
        "8.3 The App is distributed directly via a code-hosting platform (sideloading). Please ensure that your installation package comes from a reliable source; the developer accepts no responsibility for any loss caused by a tampered build downloaded from an unofficial channel.",
        "8.4 Conversations generated by the App are produced by third-party AI models and do not represent the developer's views; please judge the accuracy of the content yourself and do not rely on it for professional decisions in medicine, law, finance or similar fields.",
        "8.5 The current release build shares this machine's debug key with the debug build, so that in-place installation keeps your data intact. Such a signed build is not suitable for distribution on app stores; if you plan to publish it or distribute it officially, please rebuild it with an independent signing key."
      )
    ),

    DocBlock("Part 3 Policy Updates and Contact", emptyList(), level = 1),

    DocBlock(
      "Policy Updates", listOf(
        "The developer reserves the right to update this document from time to time. Revisions are committed to the repository and the “Last updated” date at the top of this document is changed accordingly; material changes concerning how data is handled will be announced in the release notes. If you do not accept a revision, please stop using the App and uninstall it."
      )
    ),

    DocBlock(
      "Contact", listOf(
        "If you have questions or comments about this document, or discover a security or privacy issue while reviewing the code, please contact the developer as follows:",
        "· Open an issue: https://github.com/wh456993/intersect/issues",
        "· Developer email: wba456456@qq.com"
      )
    )
  )
)

/**
 * 开源协议及隐私政策正文。
 *
 * 只负责画文档本身,滚动与页面留白由调用方决定(首次启动的协议页与
 * 「我 → 隐私政策」页共用这一份,不各抄一遍)。
 *
 * 正文按当前界面语言取 [ZH_DOC] 或 [EN_DOC],**不走 i18n 的查表翻译**:
 * 法律文本靠「中文原文当 key」查表,漏一条就静默退回中文、改错一条就是义务范围的偏差,
 * 两种都不可接受,所以整段原文各自硬编码,再加一条「以中文版为准」的效力声明兜底。
 */
@Composable
fun AgreementDocument(modifier: Modifier = Modifier) {
  val primary = AppTheme.colors.textPrimary
  val secondary = AppTheme.colors.textSecondary
  // 读一次 LangSetting.english:切换语言时本组合会重组,正文随之换语言
  val doc = agreementDocFor(LangSetting.english)

  Column(modifier.fillMaxWidth()) {
    // 效力声明单独做成一枚浅底卡片,放在正文之前——它比任何一节都更该被先看到
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
        // 部分级标题前留得多一些,小节之间只要一点呼吸
        Spacer(Modifier.height(if (isPart) 26.dp else 18.dp))
      }
      // 标题直接用 material 的 Text:文案已是最终语言,不该再过一遍 t()
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
 * 匹配三种可处理片段,靠一个正则从左到右一遍扫完(顺序即优先级):
 *
 * 1. `**重点**` —— 去掉星号,渲染为粗体;
 * 2. `https://...` 这类裸网址 —— 渲染为可点链接;
 * 3. `name@host.tld` —— 渲染为 `mailto:` 链接,点击唤起邮箱应用。
 *
 * 网址的字符类里排掉了空白与标点:否则「详见 https://example.com,以官网为准」
 * 会把逗号一起当成网址的一部分。半角的 `:` / `?` 不在排除之列(端口与 query 要用),
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
 * 抽成纯函数是为了能单测:正则的边界(网址尾部的标点、邮箱的 mailto 前缀)
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
      // 邮箱补上 mailto:,系统才会按「发邮件」而不是「访问网页」处理
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
 * 协议里的 GitHub 仓库、Apache 协议全文、Issue 地址与开发者邮箱如果只是纯文本,
 * 用户在手机上既选不中也点不动——它们恰恰是本应用唯一的外链,所以统一渲染成
 * 带下划线的主色文本,点击交给系统的 [androidx.compose.ui.platform.UriHandler]
 * (浏览器 / 邮件应用)。打不开时静默忽略:本机没装浏览器也不该让点击崩溃。
 *
 * 用 [ClickableText] 而不是 `Text` + `LinkAnnotation`:`LinkAnnotation` 要
 * Compose 1.7+,本工程钉在 1.5.4。
 *
 * 文本在 [remember] 里构建一次:协议全文字数不少,重组时不该重跑正则。
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
