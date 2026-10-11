# 课表小组件（CourseWidget for Android）

安卓桌面课表小组件：**今日课程列表 + 本周概览**。

直接读取系统日历中导入的课表日程，无需手动重复录入。课表更新只需重新导入日历，桌面小组件实时自动跟随更新。

<!-- LATEST:START -->
**最新版本：`v0.1.7-beta`**（beta）

➡️ [点此下载最新 APK](https://github.com/Ttwinner56/course-widget-0.1/releases/latest)
<!-- LATEST:END -->

```text
┌───────────────────────────────────────┐
│ 今日课程          9月7日 · 15:32  [📅周课表]│   ← 点击按钮打开全周课表全景卡片
│ ┌───────────────────────────────────┐ │
│ │ 16:00  大学英语Ⅱ                   │ │   ← 展示选定日期/今日全部课程
│ │ 17:30  商学楼103                   │ │
│ └───────────────────────────────────┘ │
│ ───────────────────────────────────── │
│ 本周概览 (点击任意一天即可在上方查看课表)   │
│ [今]    二    三    四    五    六    日  │   ← 点击格子实时切换对应天课程
│  2     1     ·     3     3     ·     ·   │   ← 选中时显示为 [二]，点击标题返回
└───────────────────────────────────────┘
```

---

## 一、 功能特性

- **数据直读系统日历**：直接读取系统 `CalendarProvider`，无需在 App 内重复维护课程数据。
- **动态交互周课表**：
  - **点击切换单日课表**：点击小组件底部周一至周日任意一格，小组件实时切换展示该天具体课程（时间、课名、教室地点），点击标题一键切回今日。
  - **全周课表全景页**：点击右上角“📅 周课表”按钮，一键打开完整全景视图，按天滚动浏览整周所有课程卡片。
- **今日课程**：按时间顺序展示今天课程，支持已结课标记。
- **本周概览**：周一至周日 7 天课程节数总览，当前选中天与今天高亮标记。
- **极低功耗与防自激刷新**：
  - 基于 AlarmManager 进行下节课开始/结束节点精确刷新。
  - 具备 2 秒 ContentObserver 防抖机制，防止日历变动引发雪崩刷新。
  - 每天 0 点自动跨天切换，单次刷新数据库查询高度合并，不常驻后台常驻服务。
- **高度兼容**：纯原生静态布局方案，完美兼容小米 MIUI / 澎湃 OS、华为 HarmonyOS、ColorOS、OriginOS 等各厂商桌面宿主。
- **固定签名发布**：采用固定签名证书，支持后续版本直接覆盖更新，无需卸载重装。
- **自动发版流水线**：集成 GitHub Actions，推送即可自动完成构建、签名、校验与 Release 发版。

---

## 二、 快速上手

### 1. 准备课表到系统日历
先将课表 `.ics` 文件导入手机系统日历（如 Google 日历、系统自带日历或通过 ICSx5 导入），建议将该日历命名为包含 **"课表"** 或 **"课程"** 字样（小组件会自动优先识别它）。

### 2. 安装与配置
1. 前往 [Releases](https://github.com/Ttwinner56/course-widget-0.1/releases) 下载最新版本的 APK 文件；
2. 手机安装 APK（首次安装需允许未知来源应用）；
3. 打开「课表小组件」App，授予 **日历读取权限**；
4. 返回手机桌面，长按空白处 → **添加小组件** → 搜索找到「课表小组件」拖至桌面；
5. 在弹出的配置页中勾选对应的课表日历，点击确定即可。

> **提示**：自 `v0.1.0` 起统一使用固定签名证书，后续所有更新均可直接覆盖安装，不会提示签名冲突。

---

## 三、 刷新机制

| 触发条件 | 说明 |
|---|---|
| 下一节课开始 / 结束 | 基于 AlarmManager 目标时间精确唤醒刷新（下课即转为已结束，开课即高亮） |
| 跨天 0 点 | 自动切换到新一天的课程概览 |
| 日历数据变动 | 注册系统 ContentObserver，日历事件变动后 2 秒防抖自动刷新 |
| 系统时间/时区/应用升级 | 广播接收器自动感知并即时刷新 |
| 周期性保活兜底 | 30 分钟系统兜底定时更新 |

---

## 四、 版本历史

| 版本 | 日期 | 类型 | 主要变化 |
|---|---|---|---|
| [v0.1.6-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.6-beta) | 2026-10-11 | beta | 彻底修复"只能显示几秒"与"周计划为空"：根治课程进行中刷新时间计算错误导致的 AlarmManager 死循环狂刷；增加 ContentObserver 2秒防抖；合并为 loadSchedule 单次获取本周数据消除并发 IPC 锁死；修复 Instances 查询日历过滤；紧凑排版确保周概览完整显示 |
| [v0.1.5-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.5-beta) | 2026-10-09 | beta | 修复"刚添加正常、几秒后变成没有课程数据"：改用候选日历逐个验证本周日程并缓存，配置页空名日历显示为"(未命名日历)" |
| [v0.1.4-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.4-beta) | 2026-10-09 | beta | 修复 0.1.2/0.1.3 的 `Can't load widget`：移除裸 `<View>` 占位符与 `setTextColor`，改纯文字方案；新增错误兜底界面 |
| [v0.1.3-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.3-beta) | 2026-10-09 | beta | 自动识别课表日历改用"本周日程条数最多"；配置页显示各日历条数；无数据时显示正在读取的日历名 |
| [v0.1.2-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.2-beta) | 2026-10-09 | beta | 字号调大；今日显示全部课程（已结束带 `✓` 且浅色）；周概览空课改圆点；周概览固定底部 |
| [v0.1.1-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.1-beta) | 2026-10-09 | beta | 修复桌面 `Can't load widget`：小组件改为静态布局，杜绝使用 `RemoteViews.addView` |
| [v0.1.0-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.0-beta) | 2026-10-09 | beta | 首个测试版：今日课程列表 + 本周概览；固定签名与自适应矢量图标 |

完整变更说明见 [CHANGELOG.md](CHANGELOG.md)。

---

## 五、 自主构建

### 1. 云端构建（推荐）
代码推送到 `main` 分支后，GitHub Actions 自动完成编译、ApkSigner 校验并发布 GitHub Release。

### 2. 本机构建
需要配置 JDK 17、Android SDK（`platforms;android-35`、`build-tools;35.0.0`）及 Gradle 8.7：

```powershell
powershell -ExecutionPolicy Bypass -File build-local.ps1
```

构建成功后产物位于：`app/build/outputs/apk/release/CourseWidget-v0.1.6-release.apk`。

---

## 六、 自定义配置指南

| 定制项目 | 配置文件位置 |
|---|---|
| 卡片背景、圆角、字号、间距 | `app/src/main/res/values/colors.xml`、`dimens.xml`、`drawable/widget_bg.xml` |
| 深色模式配色 | `app/src/main/res/values-night/colors.xml` |
| 应用图标与小组件预览 | `app/src/main/res/drawable/ic_launcher_*.xml` |
| 今日列表最多展示行数 | `ScheduleRepository.MAX_TODAY_ROWS` |
| 小组件网格尺寸（默认 4x4） | `app/src/main/res/xml/course_widget_info.xml` |
| 版本号与发布说明 | `version.json`（单点维护源） |

---

## 七、 工程结构

```text
course-widget/
├─ version.json                    版本号单点配置源
├─ CHANGELOG.md                    完整版本变更历史
├─ RELEASE.md                      签名配置与发布指引
├─ build-local.ps1                 本地一键编译脚本
├─ gen-keystore.ps1                生成签名密钥库工具
├─ .github/workflows/build.yml     GitHub Actions 自动化流水线
├─ keystore/                       固定签名证书目录（受 .gitignore 保护）
└─ app/
   ├─ build.gradle.kts             Gradle 模块构建与签名配置
   └─ src/main/
      ├─ AndroidManifest.xml       清单文件
      ├─ java/com/veid/coursewidget/
      │  ├─ CourseWidgetProvider.kt 小组件核心：布局渲染 / 周期刷新 / 数据监听
      │  ├─ ScheduleRepository.kt   日历数据仓库：读取 CalendarProvider / 派生今日与本周日程
      │  ├─ ConfigActivity.kt       小组件日历选择配置界面
      │  └─ MainActivity.kt         权限申请与使用引导页
      └─ res/
         ├─ layout/widget_*.xml     小组件静态视图布局
         ├─ drawable/ic_launcher_*  矢量自适应图标
         ├─ xml/course_widget_info.xml 小组件元数据声明
         └─ values/, values-night/  尺寸、颜色、文案与样式定义
```
