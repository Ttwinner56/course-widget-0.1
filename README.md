# 课表小组件（Android）

桌面小组件：**今日课程列表 + 本周概览**。数据直接读系统日历里你导入的课表，所以课表一变，小组件自动跟着变，不用重新编译。

<!-- LATEST:START -->
**最新版本：`v0.1.5-beta`**（beta）

➡️ [点此下载 APK](https://github.com/Ttwinner56/course-widget-0.1/releases/latest)
<!-- LATEST:END -->

```
┌──────────────────────────────┐
│ 今日课程          9月7日 · 15:32 │   ← 点这里打开日历日视图
│ ┌──────────────────────────┐ │
│ │ 16:00  大学英语Ⅱ          │ │   ← 今天剩下的课
│ │ 17:30  商学楼103          │ │
│ └──────────────────────────┘ │
│ ──────────────────────────── │
│ 本周概览                      │
│  一    二    三    四    五    六   日 │
│  1    1    —   3    3    —   —  │   ← 今天高亮
└──────────────────────────────┘
```

## 一、先准备一份课表在系统日历里

这个小组件**不存储课表**，它从系统日历读事件。所以先把课表 `.ics` 导入手机日历，并且**给那个日历起名带"课表"两个字**——小组件会自动选中它。不想改名的话，首次添加小组件时手动勾选也行。

## 二、安装

1. 打开 [Releases](https://github.com/Ttwinner56/course-widget-0.1/releases) 下载最新 APK
2. 手机安装（首次需允许"安装未知来源应用"）
3. 打开「课表小组件」→ 授予**读取日历**权限
4. 桌面长按空白处 → **添加小组件** → 找到「课表小组件」→ 拖到桌面
5. 弹出配置页 → 勾选你的课表日历 → 确定

> 从 `v0.1.0` 起使用**固定签名**，后续版本可以直接覆盖安装，不用卸载。
>
> 如果你装过更早的临时构建版本（签名不同），需要先卸载一次，之后就不会再遇到签名冲突了。

## 三、刷新机制

| 触发条件 | 说明 |
|---|---|
| 下一节课开始 | AlarmManager 精确刷新（无精确闹钟权限时退化，最多晚几分钟） |
| 每天 0 点 | 跨天自动切到新的一天 |
| 日历数据变化 | 注册了 ContentObserver，重新导入 `.ics` 后立即刷新 |
| 系统时间/时区变化、应用更新 | 都会触发刷新 |

另外 `updatePeriodMillis` 设了 30 分钟作为系统兜底。

## 四、版本历史

| 版本 | 类型 | 主要变化 |
|---|---|---|
| [v0.1.5-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.5-beta) | beta | 修复"刚添加正常、几秒后变成没有课程数据"：自动选日历会挑中空名日历；改为候选日历逐个验证"本周是否真有日程"并缓存 |
| [v0.1.4-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.4-beta) | beta | 修复 0.1.2/0.1.3 的 `Can't load widget`：移除裸 `<View>` 占位符与 `setTextColor`，改纯文字方案；新增兜底界面（失败时显示原因） |
| [v0.1.3-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.3-beta) | beta | 修复读不到课程数据：自动识别课表日历改用"本周日程条数最多"；配置页显示各日历条数；无数据时显示正在读取的日历名 |
| [v0.1.2-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.2-beta) | beta | 字号调大；今日显示全部课程（已结束带 `✓` 且浅色）；周概览空课改圆点；周概览固定底部 |
| [v0.1.1-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.1-beta) | beta | 修复桌面 `Can't load widget`：改为静态布局，不再用 `RemoteViews.addView` |
| [v0.1.0-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.0-beta) | beta | 首个测试版：今日课程 + 本周概览；修复 `instances` URI 查询报错；固定签名；矢量自适应图标 |

完整历史见 [CHANGELOG.md](CHANGELOG.md)。

## 五、自己构建

- **云端（推荐，不用装环境）**：推送到 `main` 即自动构建 + 发版，配置见 [RELEASE.md](RELEASE.md)
- **本地**：装好 JDK 17 + Android SDK + Gradle 8.7 后

  ```powershell
  powershell -ExecutionPolicy Bypass -File build-local.ps1
  ```

## 六、改外观 / 改行为

| 想改什么 | 改哪里 |
|---|---|
| 卡片底色、圆角、字号 | `app/src/main/res/values/colors.xml`、`dimens.xml`、`drawable/widget_bg.xml` |
| 深色模式配色 | `app/src/main/res/values-night/colors.xml` |
| 应用图标 | `app/src/main/res/drawable/ic_launcher_*.xml` |
| 今日列表最多几行 | `ScheduleRepository.MAX_TODAY_ROWS` |
| 小组件默认尺寸 | `app/src/main/res/xml/course_widget_info.xml` |
| 一周从周日开始 | `CourseWidgetProvider.DAY_LABELS` + `ScheduleRepository.startOfWeek` |
| 标题文字 | `app/src/main/res/values/strings.xml` |
| 版本号 | `version.json`（唯一来源） |

## 七、已知限制

- **只显示"今天剩下的课"**，已结束的课不占位置（会显示"今天的课上完了"）。
- 全天事件会被忽略（课表导入不会产生全天事件，避免节假日标记挤掉课表）。
- 同一时间有多门课时会叠加显示，不做并排布局。
- 国产 ROM 的省电策略可能冻结小组件刷新，建议在系统设置里给本应用**关闭省电限制 / 允许后台运行**。
- 点击事件默认跳系统日历的日视图；不同日历 App 支持程度不一，跳转失败时不会崩溃，只是没反应。

## 八、工程结构

```
course-widget/
├─ version.json                    版本号唯一来源
├─ CHANGELOG.md                    版本历史
├─ RELEASE.md                      签名与发布说明
├─ build-local.ps1                 本机构建脚本
├─ gen-keystore.ps1                生成固定签名密钥
├─ .github/workflows/build.yml     云端构建 + 自动发版
├─ keystore/                       签名密钥(不进仓库)
└─ app/
   ├─ build.gradle.kts             版本号、签名、产物命名
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/veid/coursewidget/
      │  ├─ CourseWidgetProvider.kt  小组件本体:渲染 / 定时刷新 / 监听数据
      │  ├─ ScheduleRepository.kt    读 CalendarProvider,算今日与本周
      │  ├─ ConfigActivity.kt        选择课表日历
      │  └─ MainActivity.kt          权限引导
      └─ res/
         ├─ layout/widget_*.xml      小组件布局
         ├─ drawable/ic_launcher_*   矢量自适应图标
         ├─ xml/course_widget_info.xml
         └─ values/, values-night/   颜色、尺寸、字符串、主题
```
