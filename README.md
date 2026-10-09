# 课表小组件（Android）

桌面小组件：**今日课程列表 + 本周概览**。数据直接读系统日历里你导入的课表，所以课表一变，小组件自动跟着变，不用重新编译。

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

## 一、你需要先有一份课表在系统日历里

这个小组件**不存储课表**，它从系统日历读事件。所以先把 `course-schedule/output/2026-2027-1-课表.ics` 导入手机日历（见 `course-schedule/docs/导入说明.md`），并且**给那个日历起名带"课表"两个字**——小组件会自动选中它。如果不想改名，首次添加小组件时手动勾选也行。

## 二、构建 APK 的两种方式

### 方式 A：GitHub Actions 云端构建（不装任何东西，推荐）

1. 在 GitHub 上建一个**私有仓库**；
2. 把 `course-widget` 目录里的内容推上去（`.github/` 目录要在仓库根目录）：

   ```bash
   cd course-widget
   git init
   git add -A
   git commit -m "课表小组件"
   git branch -M main
   git remote add origin git@github.com:<你的用户名>/<仓库名>.git
   git push -u origin main
   ```

3. 打开仓库的 **Actions** 页，等 "Build APK" 跑完（约 3–5 分钟）；
4. 在该次运行页面底部 **Artifacts** 下载 `course-widget-debug`，解压得到 `app-debug.apk`；
5. 把 APK 传到手机安装（需要允许"安装未知来源应用"）。

> 仓库设为私有，你的课表数据不会被上传——这个工程里**不含任何课表数据**，Actions 只是编译代码。

### 方式 B：本机构建（需要装环境）

前置：**JDK 17** + **Android SDK**（命令行工具即可）。

```powershell
# 1) 装 JDK 17(任选其一)
winget install EclipseAdoptium.Temurin.17.JDK

# 2) 装 Android 命令行工具后,设置环境变量
#    ANDROID_HOME 指向 sdk 目录,例如 C:\Android\Sdk
$env:ANDROID_HOME = "C:\Android\Sdk"
& "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-35" "build-tools;35.0.0" "platform-tools"

# 3) 装 Gradle 8.7(或用 Android Studio 打开本工程,自动补 wrapper)
winget install Gradle.Gradle

# 4) 构建
cd course-widget
gradle assembleDebug
# 产物:app\build\outputs\apk\debug\app-debug.apk
```

工程里**没有提交 gradle wrapper 的二进制 jar**。用 Android Studio 打开会自动生成；纯命令行则用系统 Gradle（如上）。

## 三、安装后怎么用

1. 打开「课表小组件」App → 授权**读取日历**；
2. 桌面长按空白处 → **添加小组件** → 找到「课表小组件」→ 拖到桌面；
3. 弹出配置页，勾选你导入课表的日历 → 确定；
4. 想换日历或改外观：长按小组件 → 编辑（或重新添加），也可在 App 里点"选择课表日历"。

## 四、刷新机制

| 触发条件 | 说明 |
|---|---|
| 下一节课开始 | 用 AlarmManager 精确刷新（无精确闹钟权限时退化为不精确，最多晚几分钟） |
| 每天 0 点 | 跨天自动切到新的一天 |
| 日历数据变化 | 注册了 ContentObserver，重新导入 `.ics` 后立即刷新 |
| 系统时间/时区变化、应用更新 | 都会触发刷新 |

另外 `updatePeriodMillis` 设了 30 分钟作为系统兜底。

## 五、改外观 / 改行为

| 想改什么 | 改哪里 |
|---|---|
| 卡片底色、圆角、字号 | `app/src/main/res/values/colors.xml`、`dimens.xml`、`drawable/widget_bg.xml` |
| 深色模式配色 | `app/src/main/res/values-night/colors.xml` |
| 今日列表最多几行 | `ScheduleRepository.MAX_TODAY_ROWS` |
| 小组件默认尺寸 | `app/src/main/res/xml/course_widget_info.xml` |
| 一周从周日开始 | `CourseWidgetProvider.DAY_LABELS` + `ScheduleRepository.startOfWeek` |
| 标题文字 | `app/src/main/res/values/strings.xml` |

## 六、已知限制

- **只显示"今天剩下的课"**，已结束的课不占位置（会显示"今天的课上完了"）。
- 全天事件会被忽略（课表导入不会产生全天事件，避免节假日标记挤掉课表）。
- 同一时间有多门课时会叠加显示，不做并排布局。
- 国产 ROM 的省电策略可能冻结小组件刷新，建议在系统设置里给本应用**关闭省电限制 / 允许后台运行**。
- 点击事件默认跳系统日历的日视图；不同日历 App 支持程度不一，跳转失败时不会崩溃，只是没反应。

## 七、工程结构

```
course-widget/
├─ settings.gradle.kts / build.gradle.kts / gradle.properties
├─ .github/workflows/build.yml          云端构建
└─ app/
   ├─ build.gradle.kts, proguard-rules.pro
   └─ src/main/
      ├─ AndroidManifest.xml
      ├─ java/com/veid/coursewidget/
      │  ├─ CourseWidgetProvider.kt     小组件本体:渲染 / 定时刷新 / 监听数据
      │  ├─ ScheduleRepository.kt       读 CalendarProvider,算今日与本周
      │  ├─ ConfigActivity.kt           选择课表日历
      │  └─ MainActivity.kt             权限引导
      └─ res/
         ├─ layout/widget_course.xml    小组件根布局
         ├─ layout/widget_row_class.xml 今日课程一行
         ├─ layout/widget_week_cell.xml 本周概览一格
         ├─ layout/activity_config.xml, activity_main.xml
         ├─ xml/course_widget_info.xml  小组件元数据
         ├─ drawable/…                  背景/徽标
         └─ values/… , values-night/…   颜色、尺寸、字符串、主题
```
