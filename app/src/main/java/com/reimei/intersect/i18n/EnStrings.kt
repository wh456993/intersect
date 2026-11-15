package com.reimei.intersect.i18n

/**
 * 中英对照表。key 是源码里出现的中文原文,value 是英文。
 *
 * 两张表:
 * - [exact]:整句对整句,绝大多数文案都在这里;
 * - [patterns]:带变量的句子(由 ${...} 拼出来,拼完就查不到整句了),用正则取回变量再重排成英文。
 *
 * 新增文案时把中文原样抄进 key,漏抄只会显示中文,不会坏。
 */
internal object EnStrings {
  val exact: Map<String, String> = mapOf(
    // ---- 通用 ----
    "取消" to "Cancel",
    "头像" to "Avatar",
    "返回" to "Back",
    "删除" to "Delete",
    "未命名" to "Untitled",
    "完成" to "Done",
    "当前" to "Current",
    "复制" to "Copy",
    "保存" to "Save",
    "模型" to "Model",
    "备份失败" to "Backup failed",
    "请求体" to "Request body",
    // ---- 导演模式面板 ----
    "导演模式" to "Director mode",
    "时间" to "Time",
    "地点" to "Place",
    "天气" to "Weather",
    "未设定" to "Not set",
    "场景没有变化,没有写时钟" to "Nothing changed; the clock was not updated",
    "时间格式看不懂" to "Could not read that time",
    "时间要写成 2026.5.6 8:22 或 第三纪 +302.5.6 8:22" to
      "Write the time as 2026.5.6 8:22 or Era III +302.5.6 8:22",
    "地点太长" to "Place is too long",
    "天气太长" to "Weather is too long",
    "AI 无权写入时钟:只能给建议,执行必须由导演发起" to
      "the AI may not write to the clock: it can only suggest, the director must execute",
    "已更新场景:" to "Scene updated:",
    // ---- 基准时间(创建角色时的开局时刻,程序设定的) ----
    "基准时间(程序设定的)" to "Base time (set by the program)",
    "基准时间" to "Base time",
    "格式不对" to "Wrong format",
    "看不懂这个时间:年份可带正负号,纪年名不能含数字" to
      "Could not read that time: the year may carry a sign, and the era name may not contain digits",
    "正负号 + 纪年 + 年.月.日 时:分,如 第三纪 +302.5.6 8:22" to
      "Sign + era + year.month.day hour:minute, e.g. Era III +302.5.6 8:22",
    "基准时间是程序设定的开局时刻;改它只改当前的时刻,以前的时间记录不会被动。清空就是回到出厂基准 2026.1.1 8:00。" to
      "The base time is the starting point set by the program. Editing it only changes the current " +
      "moment; earlier time records are left alone. Clearing the field goes back to the factory " +
      "default of 2026.1.1 8:00.",
    "输出" to "Output",
    "请求失败(原因未知)" to "Request failed (reason unknown)",
    "角色信息" to "Character info",
    "角色代入" to "Avatar-based Role-Play",
    "本体代入" to "Self-insert Role-Play",
    "数据备份" to "Backup",
    "设置" to "Settings",
    "后台常驻" to "Keep running in background",
    "无法读取这张图片,请换一张试试" to "Could not read this image, try another one",
    "昵称" to "Nickname",
    "签名" to "Signature",
    "添加 API KEY" to "Add API KEY",
    "名称" to "Name",
    "选择模型" to "Choose a model",
    "无法读取" to "Cannot read",
    "未知错误" to "Unknown error",
    "这个人很懒,什么都没写" to "Nothing here yet",
    "角色身份" to "Character identity",
    "聊天信息" to "Chat info",
    "我" to "Me",
    "未填写模型" to "No model set",
    "报文" to "Raw payload",
    "通讯录" to "Contacts",
    "开发者" to "Developer",
    "保存失败" to "Save failed",
    "还没有调用记录" to "No usage yet",
    "范围分析" to "Range analysis",
    "已经开启电池无限制了吗?" to "Is battery usage already unrestricted?",
    "已开启" to "Enabled",
    "还没开启" to "Not yet",
    "仍然进入" to "Enter anyway",
    "继续授权" to "Continue granting",
    "开源协议及隐私政策" to "Privacy Policy & Licenses",
    "开启必要权限" to "Grant required permissions",
    "已完成全部授权" to "All permissions granted",
    "可稍后再授权" to "You can grant these later",
    "继续" to "Continue",
    "请先完成授权" to "Please grant permissions first",
    "访问所有文件" to "All files access",
    "读写手机中的图片、视频与文档" to "Read and write images, videos and documents on this device",
    "电池无限制" to "Unrestricted battery",
    "后台常驻,避免被系统省电策略清理" to "Stay in background so the system power saver does not kill the app",
    "自启动" to "Auto start",
    "开机或被清理后台后能自动恢复" to "Recover automatically after a reboot or being killed",
    "信息通知" to "Notifications",
    "收到新消息时及时提醒" to "Get notified as soon as new messages arrive",
    "应用通知管理" to "App notification settings",
    "千问 Qwen" to "Qwen",
    "小米 MiMo" to "Xiaomi MiMo",
    "已取消" to "Cancelled",
    "请仅回复数字1" to "Reply with the number 1 only",
    "接口地址不要带 ? 或 # 参数,请只填到 /v1 这一级" to
      "Do not put ? or # parameters in the endpoint; enter it up to the /v1 level only",
    "该地址" to "That address",

    // ---- 备份(明文 .json,不加密) ----
    "无法写入所选位置" to "Cannot write to the selected location",
    "写入后文件仍是空的" to "The file is still empty after writing",
    "无法读取所选文件" to "Cannot read the selected file",
    "聊天记录" to "Chat history",
    "合并" to "Merge",
    "覆盖" to "Replace",
    "这不是有效的备份文件:内容不是 JSON" to "Not a valid backup file: the content is not JSON",
    "这不是 intersect 的备份文件" to "This is not an intersect backup file",
    "备份文件缺少版本号" to "The backup file has no version number",
    "备份文件由更新版本的应用生成,请先升级应用" to
      "This backup was created by a newer version of the app; please update the app first",
    "这是旧版 .ibk 备份,新版不再支持;请用旧版本应用恢复后重新导出 .json" to
      "This is an old .ibk backup, which is no longer supported; restore it with the previous " +
      "version of the app and export a .json again",
    "备份内容与文件头不一致" to "The backup content does not match its header",
    "未知时间" to "Unknown time",
    "没有可恢复的内容" to "Nothing to restore",
    "Base64 长度不合法" to "Invalid Base64 length",

    // ---- 对话 / 请求 ----
    "你是" to "You are",
    "对方" to "the other party",
    "。身份:" to ". Identity: ",
    "。性格:" to ". Personality: ",
    "还没有注册 API KEY,先去「我 → API KEY 管理」添加一条" to
      "No API KEY registered yet; add one under 「Me → API KEY」 first",
    "这条 API KEY 没有填模型名,先在 API KEY 里选一个模型" to
      "This API KEY has no model; pick one in the API KEY first",
    "这条 API KEY 的接口地址不对,要以 http:// 或 https:// 开头" to
      "This API KEY's endpoint is invalid; it must start with http:// or https://",
    "这条 API KEY 没有填 KEY" to "This API KEY has no key",
    "没有可以发送的内容" to "Nothing to send",
    "欢迎加入 intersect" to "Welcome to intersect",
    "未设置模型" to "No model set",
    "intersect日志" to "intersect log",
    "没有可写的外部目录" to "No writable external directory",
    "API KEY管理" to "API KEYs",
    "API KEY 管理" to "API KEYs",
    "Token统计" to "Token usage",
    "支持开发者" to "Support the developer",
    // ---- 支持开发者页 ----
    "作者头像" to "Author avatar",
    "开源仓库" to "GitHub repository",
    "QQ 邮箱" to "QQ mail",
    "抖音" to "Douyin",
    "intersect 作者 · 欢迎来仓库看看" to "Author of intersect · drop by the repository",
    "点击任一项会在浏览器或对应的应用里打开" to
      "Tap any item to open it in your browser or the matching app",
    "隐私政策" to "Privacy Policy & Licenses",
    "撤销授权" to "Revoke consent",
    "清空本机记录的同意状态并退出应用,不会删除任何数据;下次打开需重新同意本协议" to
      "Clear the consent recorded on this device and exit. No data is deleted; you will need to accept this agreement again next time",
    "确认撤销授权?" to "Revoke consent?",
    "撤销后应用会立即退出,不会删除你的角色、聊天记录或 API KEY。下次打开需要重新阅读并同意本协议才能进入。" to
      "The app will close right away. Your characters, chats and API KEYs are kept; next time you open the app you must read and accept this agreement again",
    "撤销并退出" to "Revoke and exit",
    "小先" to "Xiaoxian",
    "平行宇宙没有交点......" to "Parallel universes never intersect...",
    "欢迎加入" to "Welcome",
    "没有可写的目录" to "No writable directory",

    // ---- 用量统计 ----
    "全部" to "All",
    "密钥组" to "Key group",
    "聊天窗口" to "Chat",
    "每日" to "Daily",
    "每周" to "Weekly",
    "累计" to "Cumulative",
    "全部时间" to "All time",
    "在线" to "Online",
    "忙碌" to "Busy",
    "勿扰" to "Do not disturb",
    "离开" to "Away",
    "摸鱼中" to "Slacking off",
    "工作中" to "Working",
    "学习中" to "Studying",
    "通勤中" to "Commuting",
    "运动中" to "Working out",
    "休息中" to "Resting",
    "听歌中" to "Listening to music",
    "想聊天" to "Up for a chat",
    "保持应用在后台运行" to "Keep the app running in the background",
    "intersect 正在后台运行" to "intersect is running in the background",
    "保持在线,及时接收消息" to "Stay online and receive messages promptly",

    // ---- 角色 / 资料 ----
    "添加角色" to "Add character",
    "还没有添加 API KEY" to "No API KEY added yet",
    "先在上面的「模型」里选一个再测试" to "Choose a model above before testing",
    "先把 URL 填成 http:// 或 https:// 开头" to "First make the URL start with http:// or https://",
    "先把 KEY 填好" to "Fill in the KEY first",
    "接口没有返回模型" to "The endpoint returned no models",
    "修改 API KEY" to "Edit API KEY",
    "必须以 http:// 或 https:// 开头" to "Must start with http:// or https://",
    "删除这条 API KEY" to "Delete this API KEY",
    "删除这条 API KEY?" to "Delete this API KEY?",
    "删除后无法恢复,使用它的对话将无法调用服务。" to
      "This cannot be undone; chats using it will no longer be able to call the service.",
    "测试连接" to "Test connection",
    "测试中..." to "Testing...",
    "测试成功" to "Test succeeded",
    "失败" to "Failed",
    "选择或填写模型" to "Pick or enter a model",
    "隐藏" to "Hide",
    "显示" to "Show",
    "拉取模型" to "Fetch models",
    "使用填写的模型" to "Use the entered model",
    "导出未完成" to "Export not finished",
    "已经选好位置,但待写入的备份找不到了。\n\n请重新点一次「导出」。" to
      "A location was chosen, but the pending backup is gone.\n\nPlease tap 「Export」 again.",
    "导出完成" to "Export complete",
    "无法打开该文件" to "Cannot open that file",
    "它可能不是本应用的备份文件" to "It is probably not a backup file of this app",
    "打不开文件选择器" to "Cannot open the file picker",
    "无法打开文件选择器" to "Cannot open the file picker",
    "导出" to "Export",
    "导入" to "Import",
    "删除全部" to "Delete all",
    "正在导出..." to "Exporting...",
    "导出失败" to "Export failed",
    "好" to "OK",
    "恢复完成" to "Restore complete",
    "合并完成" to "Merge complete",
    "覆盖完成" to "Replace complete",
    "备份文件不合法" to "Invalid backup file",
    "恢复失败" to "Restore failed",
    "恢复方式" to "Restore mode",
    "正在恢复..." to "Restoring...",
    "恢复" to "Restore",
    "已删除" to "Deleted",
    "删除全部聊天记录" to "Delete all chat history",
    "导出时由你选择保存位置,文件是可直接打开的 .json。" to
      "You choose where to save it; the file is a .json you can open directly.",
    "备份文件是明文 .json,不加密,导出时由你选择保存位置。" to
      "Backup files are plain .json and are not encrypted. You choose where to save them.",
    "角色卡" to "Characters",
    "跨格式备份、导出与恢复" to "Cross-format backup, export and restore",
    "备份与恢复角色卡配置" to "Back up and restore character profiles",
    "跨配置备份与恢复,保持思维链一致" to
      "Back up and restore across setups, keeping the reasoning chain consistent",
    "模型配置" to "Model settings",
    "备份与恢复所有模型设置,包括 API 密钥和参数" to
      "Back up and restore every model setting, including the API key and parameters",
    "合并:保留本机已有角色,补充备份里多出来的;同 id 的角色以备份为准" to
      "Merge: keep the characters on this device and add the extra ones from the backup; " +
      "a character with the same id follows the backup",
    "覆盖:本机多出来的角色会被清掉,只留备份里的角色卡" to
      "Replace: characters that exist only on this device are removed, leaving only the backup",
    "合并:保留本机会话,只补充备份里多出来的消息" to
      "Merge: keep the chats on this device and only add the messages found in the backup",
    "覆盖:本机多出来的会话会被清掉,只留备份里的聊天记录" to
      "Replace: chats that exist only on this device are removed, leaving only the backup",
    "合并:只补本机还没有记忆的角色,已有记忆保持不动" to
      "Merge: only fill in characters that have no memory on this device; existing memory is kept",
    "覆盖:本机所有角色的记忆都会被替换成备份里的内容" to
      "Replace: every character's memory on this device is replaced by the backup",
    "合并:保留本机配置,补充备份里多出来的;同 id 的配置以备份为准" to
      "Merge: keep the settings on this device and add the extra ones from the backup; " +
      "a setting with the same id follows the backup",
    "覆盖:本机多出来的配置会被清掉,只留备份里的模型配置" to
      "Replace: settings that exist only on this device are removed, leaving only the backup",
    "记忆库" to "Memory",
    "即时记忆 / 时间线索引 / 长期记忆" to "Immediate memory / Timeline index / Long-term memory",
    "即时记忆层" to "Immediate memory",
    "时间线索引层" to "Timeline index",
    "长期记忆层" to "Long-term memory",
    "把每段记忆按发生时间排成一条线,方便按时间回看角色经历过什么。" to
      "Lines every memory up by the time it happened, so you can look back at what the character has been through.",
    "把反复出现的人、地点与约定沉淀成长期设定,不必每次都从总结里推断。" to
      "Distills recurring people, places and promises into long-term settings instead of inferring them from summaries.",
    "即将上线" to "Coming soon",
    "总结长度上限" to "Max summary length",
    "每 20 轮对话自动总结一次;这是长度上限,实际长度在 250 token 到该值之间按内容浮动。" to
      "Summarizes automatically every 20 rounds; this is the length cap, and the actual length lands between " +
        "250 tokens and the cap depending on the content.",
    "重试" to "Retry",
    "还没有即时记忆" to "No immediate memory yet",
    "正在生成第一条总结..." to "Generating the first summary...",
    "生成完会出现在这里,不用离开这个页面。" to "It will show up here when it is done; you can stay on this page.",
    "每聊完 20 轮会自动总结一次,总结之后这些轮的原文不再发给模型,只发总结。" to
      "Every 20 rounds are summarized automatically; after that those rounds are no longer sent verbatim, only the summary is.",
    "开发者面板" to "Developer panel",
    "当前轮数" to "Rounds so far",
    "距下次总结" to "Rounds until next summary",
    "上次总结时间" to "Last summary",
    "还没有总结过" to "No summary yet",
    "当前注入总结条数" to "Summaries injected",
    "当前注入 token 估算" to "Injected token estimate",
    "滑块当前值" to "Slider value",
    "总结中..." to "Summarizing...",
    "手动触发总结" to "Summarize now",
    "查看最近一次请求体记忆部分" to "View memory part of the last request",
    "收起最近一次请求体的记忆部分" to "Collapse memory part of the last request",
    "还没有发过请求。下面是按现在的总结算出来的记忆块(下一轮会注入这一份):" to
      "No request sent yet. Below is the memory block built from the current summaries (it will be injected next round):",
    "上一轮请求里没有记忆块(那时还没有总结):system 仍带着记忆使用规则,记忆块整块省略。" to
      "The last request had no memory block (there was no summary yet); the system prompt still carried the memory rules.",
    "上一轮请求体里的记忆部分:" to "Memory part of the last request:",
    "【暂无历史总结】这一轮不注入记忆块。" to "[No summary yet] no memory block is injected this round.",
    "清空即时记忆" to "Clear immediate memory",
    "清空即时记忆?" to "Clear immediate memory?",
    "这一层里的全部总结、错误日志都会删掉(滑块值与当前进度保留),删除后无法恢复;之后新聊的轮次从头开始攒。" to
      "All summaries and the error log in this layer are deleted (slider value and current progress are kept). This cannot be undone; new rounds start counting from scratch.",
    "错误日志" to "Error log",
    "没有错误" to "No errors",
    "注入格式:system(角色卡 + 记忆规则) → 未总结原文 → 一条独立的记忆块 → 当前输入。" to
      "Injection order: system (character card + memory rules) → raw unsummarized history → a separate memory block → current input.",
    "还没有可以总结的轮次" to "No rounds to summarize yet",
    "空间管理器" to "Space manager",
    "全选" to "Select all",
    "取消全选" to "Deselect all",
    "回滚" to "Roll back",
    "回滚到这里" to "Roll back to here",
    "退出多选" to "Exit multi-select",
    "自定义总结提示词" to "Custom summary prompt",
    "当前:自定义" to "Current: custom",
    "当前:内置模板" to "Current: built-in template",
    "打开后可以把总结请求的 system 换成自己写的一段(比如要求按时间线写、保留情绪变化)。" to
      "Turn this on to replace the summary request's system prompt with your own (e.g. write along a timeline, keep emotional shifts).",
    "已改用自定义提示词" to "Now using the custom prompt",
    "已回到内置模板" to "Back to the built-in template",
    "恢复默认" to "Restore default",
    "已恢复内置模板" to "Built-in template restored",
    "已保存,下次总结生效" to "Saved; takes effect on the next summary",
    "总结提示词" to "Summary prompt",
    "自定义" to "Custom",
    "内置模板" to "Built-in template",
    "物品管理器" to "Inventory",
    "时间管理器" to "Time manager",
    "世界观" to "Worldview",
    "背景" to "Background",
    "法则体系" to "Laws",
    "势力分布" to "Factions",
    "经济架构" to "Economy",
    "历史与重大事件" to "History & major events",
    "还没填写 · 法则体系 / 势力分布 / 经济架构 / 历史与重大事件" to
      "Not filled in yet · Laws / Factions / Economy / History & major events",
    "可留空,比如:这个世界没有神,一切力量都要以记忆为代价" to
      "Optional, e.g. there are no gods here; every power has to be paid for with memory",
    "可留空,比如:北境军府与商会联盟对峙,教团在两方之间卖消息" to
      "Optional, e.g. the northern garrison faces off against the merchant league, while the order sells word between them",
    "可留空,比如:以盐为硬通货,长途贸易全走河运,税按船算" to
      "Optional, e.g. salt is hard currency, long-distance trade goes by river, taxes are counted per boat",
    "可留空,比如:十七年前的断河之战之后,旧王族再没出现过" to
      "Optional, e.g. the old royal line has not been seen since the Battle of the Broken River seventeen years ago",
    "可留空,比如:出身铁匠铺,十六岁进城当学徒,如今欠着一笔还不上的债" to
      "Optional, e.g. raised in a smithy, apprenticed in the city at sixteen, now carrying a debt that cannot be repaid",
    "创建后可用" to "Available once created",
    "点选后进入该模式的功能页,完成后按最后进入的模式创建" to
      "Tap a mode to open its feature page; the last one you enter is what gets created",
    "扮演模式" to "Role-playing mode",
    "填写这个角色的来历与处境(可留空)" to "Where this character comes from and where they stand (optional)",
    "这四项只在本机记录,不会发给模型;你的每一次对话仍只带名称、身份与性格。" to
      "These four fields stay on this device and are not sent to the model; each request still carries only name, identity and personality.",
    "只在本机记录,不会发给模型。" to "Kept on this device only; not sent to the model.",
    "名称、性格、身份都已填写" to "Name, personality and identity are all filled in",
    "开发中" to "In development",
    "不能为空" to "Cannot be empty",
    "性格" to "Personality",
    "可留空,比如:说话简短直接,偶尔吐槽,不喜欢寒暄" to
      "Optional, e.g. speaks briefly and directly, occasionally snarky, dislikes small talk",
    "身份" to "Identity",
    "可留空,比如:本软件的向导,负责带新用户上手" to
      "Optional, e.g. a guide of this app who helps new users get started",
    "查询聊天记录" to "Search chat history",
    "输入关键词" to "Enter a keyword",
    "删除该好友" to "Delete this contact",
    "删除好友" to "Delete contact",
    "会同时清除与该好友的聊天记录,且无法恢复。" to
      "The chat history with this contact will be deleted too, and cannot be recovered.",
    "还没有注册 API KEY" to "No API KEY registered yet",
    " · 跟随默认" to " · Follow default",
    "跟随默认" to "Follow default",
    "删除聊天记录" to "Delete chat history",
    "这条消息将被删除,且无法恢复。" to "This message will be deleted and cannot be recovered.",
    "还没有聊天记录,打个招呼吧" to "No messages yet — say hi",
    "对方正在输入..." to "Typing...",
    "知道了" to "Got it",
    "附件" to "Attach",
    "全部显示" to "Show all",
    "收起全部" to "Hide all",
    "发送" to "Send",
    "多选" to "Select",
    "已选择" to "Selected",
    "开发者控制台" to "Developer console",
    "error: 没有可用的 API KEY" to "error: no API KEY available",
    "(没有响应体)" to "(no response body)",
    "日志" to "Log",
    "正在请求..." to "Requesting...",
    "清空" to "Clear",
    "还没有请求" to "No requests yet",
    "响应" to "Response",
    "重置默认" to "Reset to default",
    "URL 必须以 http:// 或 https:// 开头" to "The URL must start with http:// or https://",
    "请求体不是合法的 JSON 对象" to "The request body is not a valid JSON object",
    "请求体里缺少 model 字段" to "The request body has no model field",
    "裁切图片" to "Crop image",
    "处理中" to "Processing",
    "确定" to "OK",
    "更多" to "More",
    "+ 状态" to "+ Status",
    "选择状态" to "Choose a status",
    "清除状态" to "Clear status",
    "功能开发中" to "Coming soon",
    "添加" to "Add",
    "朋友" to "Friend",
    "个人资料" to "Profile",
    "还没有聊天记录" to "No messages yet",

    // ---- 设置 ----
    "界面语言" to "Language",
    "跟随系统" to "Follow system",
    "切换界面语言" to "Change interface language",
    "浅色模式" to "Light mode",
    "深色模式" to "Dark mode",
    "外观" to "Appearance",
    "开发者模式" to "Developer mode",
    "从最近任务隐藏" to "Hide from recent tasks",
    "聊天" to "Chat",

    // ---- 用量页 ----
    "不足 1 分钟" to "Less than a minute",
    "趋势" to "Trend",
    "Token 使用统计" to "Token usage",
    "保存中" to "Saving",
    "已保存到本地" to "Saved locally",
    "关闭" to "Close",
    "复制路径" to "Copy path",
    "周期总览" to "Period overview",
    "总费用" to "Total cost",
    "峰值 Token" to "Peak tokens",
    "总请求次数" to "Total requests",
    "缓存率" to "Cache rate",
    "活跃记录" to "Activity",
    "Token 构成" to "Token breakdown",
    "缓存读取" to "Cache read",
    "未缓存输入" to "Uncached input",
    "模型累计" to "By model",
    "(未知模型)" to "(unknown model)",
    "趋势分析" to "Trend analysis",
    "配置详情" to "Configuration details",
    "收起" to "Collapse",
    "展开" to "Expand",
    "这个范围内没有调用记录" to "No usage in this range",
    "统计设置" to "Statistics settings",
    "总计币种" to "Total currency",
    "汇率设置" to "Exchange rate",
    "默认" to "Default",
    "已手动设置" to "Set manually",
    "模型单价" to "Model prices",
    "每百万 token" to "Per million tokens",
    "正在统计..." to "Aggregating...",
    "还没有消耗记录" to "No usage recorded yet",
    "清除筛选条件" to "Clear filters",
    "费用" to "Cost",
    "请求" to "Requests",
    "统计范围" to "Range",
    "单价设置" to "Price settings",
    "美元汇率" to "USD exchange rate",
    "元" to "CNY",
    "$/百万" to "$/1M",
    "¥/百万" to "¥/1M",
    "输入" to "Input",
    "缓存读" to "Cache read",
    "保存时重算历史费用" to "Recalculate past costs on save",
    "全部范围" to "All ranges",
    "简介" to "Description",
    "添加物品" to "Add item",
    "还没有添加物品" to "No items added yet",
    "删除这件物品?" to "Delete this item?",
    "删除后无法恢复。" to "Cannot be recovered after deletion.",
    "登记物品,对话提到名称时自动补充简介" to "Register items; their descriptions are filled in when mentioned",
    "可留空,补充这件物品的说明" to "Optional, describe this item",

    // ---- 记忆库 / 开发者控制台(补充) ----
    "intersect 模式" to "Intersect mode",
    "模式" to "Mode",
    "本次请求体" to "This request",
    "报文不是合法的 JSON 对象" to "The payload is not a valid JSON object",
    "未命名角色" to "Unnamed character",
    "还没有填写角色设定" to "No character prompt yet",
    "这就是这一轮真正要发出去的报文,可以逐字改:再点「发送」才发出,点「取消」则整轮作废(原文回填输入框)。改动不写回「请求体」配置。" to
      "This is the exact payload for this round. Edit it word by word if you like: tap Send again to send it, " +
      "or Cancel to discard the whole round (the original text goes back to the input box). " +
      "Edits are not written back to the Request body configuration.",
    "角色设定在「角色」页填写,发送时会作为一条 system 消息插到最前面;这里只放请求体本身。" +
      "模型与两个开关也是改这份请求体,改完点下面的「保存」才落盘。" to
      "The character prompt is set on the Character page and is inserted as the first system message when sending; " +
      "only the payload itself goes here. The model and the two switches also edit this payload — " +
      "tap Save below to keep the changes.",
    // ---- 开发者控制台的请求配置(模型 / 深度思考 / 联网搜索) ----
    "深度思考" to "Deep thinking",
    "联网搜索" to "Web search",
    "{0}:开" to "{0}: On",
    "{0}:关" to "{0}: Off",
    "{0}:不支持" to "{0}: N/A",
    "写进请求体:{0}" to "Written into the request body: {0}",
    "打开时模型切到 {0}" to "Switches the model to {0} when on",
    "该服务商未内置这个参数,可以在请求体里手写" to
      "This provider has no built-in parameter for it; write it in the request body instead",
    "还没写内容,现在用的是内置模板;写一段再点保存。" to
      "Nothing written yet, so the built-in template is used; write something and tap Save.",
    "这条消息已经不在了。" to "This message is no longer here.",
    "本轮总结期间删过消息或回滚过,结果已作废" to
      "Messages were deleted or rolled back during this summary, so the result was discarded",
  )
  /**
   * 一行场景变更:`<旧值变更新值>`。两侧是日期/地点/天气原文(用户内容,不翻),
   * 只把中间的「变更」换成箭头。声明在 [patterns] 之前:规则表初始化时就要用它。
   */
  private val SCENE_CHANGE = Regex("<(.+?)变更(.+?)>")

  /** 场景回显正文:逐行处理变更行,其余行按整句表反查。 */
  private fun sceneBody(zh: String): String = zh.lines().joinToString("\n") { line ->
    val m = SCENE_CHANGE.matchEntire(line)
    if (m != null) "<${m.g(1)} → ${m.g(2)}>" else inner(line)
  }

  /**
   * 由变量拼出来的句子。
   *
   * 顺序有讲究:更具体的规则放前面(如「${DawnAssistant.NAME}是系统内置角色...」要在通用规则之前)。
   */
  val patterns: List<Pair<Regex, (MatchResult) -> String>> = listOf(
    // Activation
    Regex("还有 (.+?) 项未开启") to { m -> "Still ${m.g(1)} not enabled" },
    Regex("接口返回了空响应\\(HTTP (.+?)\\)") to { m -> "The endpoint returned an empty response (HTTP ${m.g(1)})" },
    Regex("响应不是可识别的模型列表:(.*)", RegexOption.DOT_MATCHES_ALL) to { m ->
      "The response is not a recognizable model list: ${m.g(1)}"
    },
    Regex("解析不到域名 (.+?):检查地址拼写,或换个网络再试") to { m ->
      "Cannot resolve the host ${m.g(1)}: check the address spelling, or try another network"
    },
    Regex("连不上 (.+?):这个地址在当前网络下不通,可能需要代理") to { m ->
      "Cannot reach ${m.g(1)}: this address is not reachable on the current network, a proxy may be needed"
    },
    Regex("连不上 (.+?):建连超时,该域名可能被网络拦截,或需要代理") to { m ->
      "Cannot reach ${m.g(1)}: connection timed out; the domain may be blocked by the network or need a proxy"
    },
    Regex("已连上 (.+?),但一直没有返回数据:模型可能太慢,或服务端在排队") to { m ->
      "Connected to ${m.g(1)}, but no data came back: the model may be too slow or the server is queued"
    },
    Regex("请求超时\\((.+?) 秒没有响应\\):地址可能不可达,或被网络/代理拦截") to { m ->
      "Request timed out (no response in ${m.g(1)}s): the address may be unreachable, or blocked by the network/proxy"
    },
    Regex("(.+?) 秒内没有返回:可能模型响应慢,也可能是地址或网络不通") to { m ->
      "No response within ${m.g(1)}s: either the model is slow, or the address/network is unreachable"
    },

    // 备份(明文 .json)
    Regex("只写入了 (.+?) 字节") to { m -> "Only ${m.g(1)} bytes were written" },
    Regex("(.+?) 个角色") to { m -> "${m.g(1)} characters" },
    Regex("(.+?) 个会话\\((.+?) 条消息\\)") to { m -> "${m.g(1)} chats (${m.g(2)} messages)" },
    Regex("(.+?) 条记忆") to { m -> "${m.g(1)} memories" },
    Regex("(.+?) 个模型配置") to { m -> "${m.g(1)} model settings" },
    Regex("当前共有 (.+?) 条聊天记录\\(共 (.+?) 条消息\\)。") to { m ->
      "There are ${m.g(1)} chats (${m.g(2)} messages) on this device."
    },
    Regex("当前共有 (.+?) 个角色卡。") to { m ->
      "There are ${m.g(1)} characters on this device."
    },
    Regex("当前共有 (.+?) 条记忆\\((.+?) 个角色\\)。") to { m ->
      "There are ${m.g(1)} memories (${m.g(2)} characters) on this device."
    },
    Regex("当前共有 (.+?) 个模型配置。") to { m ->
      "There are ${m.g(1)} model settings on this device."
    },
    Regex("已清空 (.+?) 个会话的全部聊天记录。") to { m ->
      "Cleared all chat history of ${m.g(1)} chats."
    },
    Regex(
      "本机的 (.+?) 个会话及其中的 (.+?) 条消息会全部删除,无法恢复。\\n\\n角色卡、记忆库与模型配置不受影响。",
      RegexOption.DOT_MATCHES_ALL
    ) to { m ->
      "All ${m.g(1)} chats and their ${m.g(2)} messages will be deleted and cannot be recovered." +
        "\n\nCharacters, memory and model settings are not affected."
    },
    Regex("导入(聊天记录|角色卡|记忆库|模型配置)") to { m ->
      "Import " + when (m.g(1)) {
        "聊天记录" -> "chat history"
        "角色卡" -> "characters"
        "记忆库" -> "memory"
        else -> "model settings"
      }
    },
    Regex("应用版本 (.+)") to { m ->
      "App version " + if (m.g(1) == "未知") "unknown" else m.g(1)
    },
    Regex("备份文件的 (.+?) 缺失") to { m -> "The backup file is missing the ${m.g(1)} field" },
    Regex("备份文件的 (.+?) 不合法") to { m -> "The backup file has an invalid ${m.g(1)} field" },
    Regex("角色 \\+(.+?) / 更新 (.+)") to { m -> "Characters +${m.g(1)} / updated ${m.g(2)}" },
    Regex("会话 \\+(.+?) / 补充 (.+?)\\((.+?) 条消息\\)") to { m ->
      "Chats +${m.g(1)} / merged ${m.g(2)} (${m.g(3)} messages)"
    },
    Regex("记忆 \\+(.+)") to { m -> "Memories +${m.g(1)}" },
    Regex("模型配置 \\+(.+?) / 更新 (.+)") to { m -> "Model settings +${m.g(1)} / updated ${m.g(2)}" },
    Regex("Base64 含非法字符: (.*)", RegexOption.DOT_MATCHES_ALL) to { m ->
      "Base64 contains an illegal character: ${m.g(1)}"
    },
    // 导出完成的「保存到哪」是独立一段,不和规模信息拼成一整串(拼了查不到翻译)
    Regex("已保存到你选择的位置:\n(.+)", RegexOption.DOT_MATCHES_ALL) to { m ->
      "Saved to the location you chose:\n${m.g(1)}"
    },
    Regex("(.+?)完成\n(.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "${m.g(1)} complete\n${m.g(2)}" },

    // 请求 / 响应
    Regex("请求没有发出去:(.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "The request was not sent: ${m.g(1)}" },
    Regex("响应不是合法 JSON:(.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "The response is not valid JSON: ${m.g(1)}" },
    Regex("响应里没有正文:(.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "The response has no body: ${m.g(1)}" },

    // 用量
    Regex("近 (.+?) 天") to { m -> "Last ${m.g(1)} days" },
    Regex("(.+?) 天 (.+?) 小时") to { m -> "${m.g(1)}d ${m.g(2)}h" },
    Regex("(.+?) 小时 (.+?) 分钟") to { m -> "${m.g(1)}h ${m.g(2)}m" },
    Regex("(.+?) 分钟") to { m -> "${m.g(1)}m" },

    // 字符数 / 数量
    Regex("最多 (.+?) 个字符") to { m -> "At most ${m.g(1)} characters" },
    Regex("至少 (.+?) 个字符") to { m -> "At least ${m.g(1)} characters" },
    Regex("(.+?) 个字符以内") to { m -> "Up to ${m.g(1)} characters" },
    Regex("名称、性格、身份 · 已填写 (.+?) 项") to { m -> "Name, personality, identity · ${m.g(1)} filled in" },
    Regex("已填 (.+?)/4 · 法则体系 / 势力分布 / 经济架构 / 历史与重大事件") to { m ->
      "Filled ${m.g(1)}/4 · Laws / Factions / Economy / History & major events"
    },
    Regex("找到 (.+?) 条记录") to { m -> "${m.g(1)} records found" },
    Regex("没有找到包含「(.*?)」的记录") to { m -> "No records containing “${m.g(1)}”" },
    Regex("(.+?)是系统内置角色,不能删除") to { m -> "${m.g(1)} is a built-in character and cannot be deleted" },
    Regex("已选择 (.+?) 条") to { m -> "${m.g(1)} selected" },
    Regex("选中的 (.+?) 条消息将被删除,且无法恢复。") to { m ->
      "The ${m.g(1)} selected messages will be deleted and cannot be recovered."
    },
    Regex("从这条起的 (.+?) 条都会被删除,回到它之前的状态。删除后无法恢复。") to { m ->
      // 英文没有量词问题,但单复数要处理:1 条时说 the 1 message 很别扭
      if (m.g(1) == "1") {
        "This message will be deleted and you will be back to the state before it. This cannot be undone."
      } else {
        "The ${m.g(1)} messages starting from this one will be deleted, returning to the state before it. " +
          "This cannot be undone."
      }
    },
    // 记忆库:总结进行中 / 结果 / 状态条与说明(轮数、长度上限都由常量拼出来)
    Regex("正在总结前 (.+?) 轮对话\\.\\.\\.") to { m -> "Summarizing the last ${m.g(1)} rounds..." },
    Regex("上次总结失败:(.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "Last summary failed: ${m.g(1)}" },
    Regex("总结失败:(.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "Summary failed: ${m.g(1)}" },
    Regex("总结完成 · 实际 (.+?) token") to { m -> "Summary done · ${m.g(1)} tokens" },
    Regex("上限 (.+?) token · 实际 (.+?) token") to { m -> "Cap ${m.g(1)} tokens · actual ${m.g(2)} tokens" },
    Regex("最大 (.+?) token") to { m -> "Max ${m.g(1)} tokens" },
    Regex("每 (.+?) 轮对话自动总结一次;这是长度上限,实际长度在 (.+?) token 到该值之间按内容浮动。") to { m ->
      "Summarizes every ${m.g(1)} rounds; this is the length cap, and the actual length lands between " +
        "${m.g(2)} tokens and the cap depending on the content."
    },
    Regex("每聊完 (.+?) 轮会自动总结一次,总结之后这些轮的原文不再发给模型,只发总结。") to { m ->
      "After every ${m.g(1)} rounds a summary is generated automatically; from then on those raw rounds are no " +
        "longer sent to the model, only the summary."
    },
    Regex("占位符:(.+?) 长度上限、(.+?) 长度下限\\(固定 (.+?)\\)。写成空文本会退回内置模板。") to { m ->
      "Placeholders: ${m.g(1)} for the length cap, ${m.g(2)} for the lower bound (fixed at ${m.g(3)}). " +
        "An empty text falls back to the built-in template."
    },
    Regex("已登记 (.+?) 件 · 对话提到名称时自动补充简介") to { m ->
      "${m.g(1)} items registered · descriptions are filled in when mentioned"
    },
    Regex("已用 (.+?) / (.+?) 字符") to { m -> "Using ${m.g(1)} / ${m.g(2)} characters" },
    Regex("已复制 (.+?) 行到剪贴板") to { m -> "Copied ${m.g(1)} lines to the clipboard" },
    Regex("已保存:(.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "Saved: ${m.g(1)}" },
    Regex("保存失败:(.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "Save failed: ${m.g(1)}" },
    // 开发者模式里 AI 回复下那条记忆索引(角色 UID / 用户 UID / 落盘时间)
    Regex("记忆索引:角色 UID (.+?) · 用户 UID (.+?) · 存入 (.+)") to { m ->
      "Memory index: character UID ${m.g(1)} · user UID ${m.g(2)} · stored ${m.g(3)}"
    },
    Regex("没有可用的 API KEY。请到「我 → API KEY 管理」里添加一条与 (.+?) 地址匹配的配置。") to { m ->
      "No API KEY available. Go to 「Me → API KEY」 and add one that matches the ${m.g(1)} endpoint."
    },
    Regex("测试中 (.+?)s · 取消") to { m -> "Testing ${m.g(1)}s · Cancel" },
    Regex("取消 (.+?)s") to { m -> "Cancel ${m.g(1)}s" },
    Regex("失败 · HTTP (.+)") to { m -> "Failed · HTTP ${m.g(1)}" },
    Regex("从接口拉取 \\((.+?)\\)") to { m -> "Fetch from endpoint (${m.g(1)})" },
    Regex("已按新单价重算 (.+?) 条历史流水") to { m -> "Recalculated ${m.g(1)} past records with the new price" },
    Regex("(.+?) 字节") to { m -> "${m.g(1)} bytes" },
    Regex("当前连续 (.+?) 天") to { m -> "Current streak ${m.g(1)} days" },
    Regex("最长连续 (.+?) 天") to { m -> "Longest streak ${m.g(1)} days" },
    Regex("按天统计 · 峰值 (.+?) Token") to { m -> "Daily · peak ${m.g(1)} tokens" },
    Regex("按周统计 · 峰值 (.+?) Token") to { m -> "Weekly · peak ${m.g(1)} tokens" },
    Regex("累计到每天为止的合计 · 日峰值 (.+?) Token") to { m -> "Cumulative total by day · daily peak ${m.g(1)} tokens" },
    Regex("(.+?) 个模型") to { m -> "${m.g(1)} models" },
    Regex("(.+?) 个配置") to { m -> "${m.g(1)} configurations" },
    Regex("(.+?) 次 · (.+?) tokens · 最后 (.*)") to { m ->
      "${m.g(1)} calls · ${m.g(2)} tokens · last ${m.g(3)}"
    },
    Regex("(.+?)趋势") to { m -> "${inner(m.g(1))} trend" },
    Regex("点击柱子查看单个(.+?)的数据") to { m -> "Tap a bar to see the data of one ${inner(m.g(1))}" },
    Regex("已选中一(.+?),再点一次其它柱子可切换") to { m ->
      "One ${inner(m.g(1))} selected; tap another bar to switch"
    },
    Regex("这段时间里没有(.+?)维度的流水") to { m -> "No usage for the ${inner(m.g(1))} dimension in this range" },
    Regex("(.+?):全部(.+?)") to { m -> "${inner(m.g(1))}: all ${inner(m.g(2))}" },
    Regex("全部(.+)") to { m -> "All ${inner(m.g(1))}" },
    // 导演模式面板与会话回显。顺序要紧:「最多 N 个字,这次没有写时钟」两条具体规则必须排在
    // 通用收尾「...,这次没有写时钟」前面,否则内层那句带变量的中文查不到(inner 只查整句表)。
    Regex("地点最多 (.+?) 个字,这次没有写时钟") to { m ->
      "Place is at most ${m.g(1)} characters; the clock was not updated"
    },
    Regex("天气最多 (.+?) 个字,这次没有写时钟") to { m ->
      "Weather is at most ${m.g(1)} characters; the clock was not updated"
    },
    Regex("(.+?),这次没有写时钟") to { m -> "${inner(m.g(1))}; the clock was not updated" },
    Regex("已更新场景:\n(.*)", RegexOption.DOT_MATCHES_ALL) to { m ->
      "Scene updated:\n${sceneBody(m.g(1))}"
    },
    SCENE_CHANGE to { m -> "<${m.g(1)} → ${m.g(2)}>" },
    Regex("基准时间 · (.+)") to { m -> "Base time · ${m.g(1)}" },
    Regex("角色信息 · (.+)") to { m -> "Character info · ${inner(m.g(1))}" },
    Regex("没有改场景:(.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "Scene not changed: ${inner(m.g(1))}" },
    Regex("没有改时钟:(.*)", RegexOption.DOT_MATCHES_ALL) to { m -> "Clock not changed: ${inner(m.g(1))}" },
  )
}
