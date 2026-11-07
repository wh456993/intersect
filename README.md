# intersect

一个本地优先的对话与角色管理应用,用 Jetpack Compose 写界面。

## 这是什么

- **会话与角色**:和内置助手对话,也可以自建角色,给角色写身份、性格与签名,换自定义头像。
- **多服务商**:密钥按服务商登记(模型跟着密钥走),每个会话可以绑定某条密钥,也能「跟随默认」。
- **用量账本**:每条回复的 token 与费用都会记账,可按模型、按日期、按会话查看,能导出。
- **备份**:通讯录、会话、密钥配置可打包导出,支持口令加密后再导出。
- **状态与主题**:名片上可切换在线状态;主题跟随系统或手动切换明暗。

数据都在本机:没有服务端账号,不联网也能打开历史记录。

## 目录

```
app/src/main/java/com/reimei/intersect/
  MainActivity.kt / MainViewModel.kt   入口与全局状态
  Activation.kt                        首次启动的协议与权限流程
  data/                                数据模型、持久化、服务商协议、用量与备份
  ui/                                  各页面与通用组件
  ui/theme/                            配色、圆角、主题
  service/ receiver/                   后台上报与开机拉起
tools/                                 生成资源与自查脚本
```

## 工具脚本

都是纯 Node(无第三方依赖),在项目根目录执行:

| 脚本 | 作用 |
| --- | --- |
| `node tools/make_ui_icons.js` | 按脚本里的坐标生成 `res/drawable` 下全部界面图标 |
| `node tools/render_launcher_icons.js` | 生成启动图标(自适应前景 + 各密度位图) |
| `node tools/similarity_scan.js` | 与仓库首个提交做「非空唯一行」重合度扫描,并统计本仓库内部重复行 |

## 构建

```sh
sh gradlew :app:assembleDebug
```

`release` 与 `debug` 共用本机 debug keystore,便于覆盖安装、不丢数据;这样的包不能上架应用商店。

## 来源与致谢

界面布局与交互范式的灵感来自扔物线《三小时用 Compose 写个聊天界面》的示例项目 WeCompose。本项目为独立实现:代码逐行新写,界面图标由 `tools/make_ui_icons.js` 现场生成,启动图标由 `tools/render_launcher_icons.js` 渲染,不包含该示例的源代码、图标、头像或其它素材。

## 第三方组件

构建与运行时依赖均为 Apache-2.0:`gradle/wrapper` 来自 Gradle 官方发行版;界面基于 Jetpack Compose、AndroidX 与 Material Components。分发 APK 时请保留各库的许可声明。

## 许可

本项目代码以 MIT 协议发布,见 [LICENSE](LICENSE)。
