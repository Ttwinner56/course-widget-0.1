# 版本历史

版本号规则：`主版本.次版本.修订号`，当前处于 **测试阶段**，所有版本均为 `beta`。

- **主版本**：界面或数据模型发生不兼容变化时递增
- **次版本**：新增功能
- **修订号**：修 bug、调整样式

`versionCode` 计算方式：`主*1000000 + 次*1000 + 修*10`（如 `0.1.0` → `1000`），保证单调递增，安卓据此判断升级。

| 版本 | 日期 | 类型 | 主要变化 |
|---|---|---|---|
| [v0.1.1-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.1-beta) | 2026-10-09 | beta | 修复桌面 `Can't load widget`：小组件改为静态布局，不再用 `RemoteViews.addView`；今日最多显示 4 节课并显示起止时间 |
| [v0.1.0-beta](https://github.com/Ttwinner56/course-widget-0.1/releases/tag/v0.1.0-beta) | 2026-10-09 | beta | 首个测试版：今日课程列表 + 本周概览；修复 `instances` URI 查询报错；固定签名；矢量自适应图标 |

## 如何发布新版本

1. 修改 `version.json`：升 `versionName`、同步 `versionCode`、更新 `notes`
2. 在本文件顶部表格加一行
3. 提交并推送 → CI 自动构建、打 tag、发 Release、更新 README

```bash
git add -A
git commit -m "chore: 发布 v0.1.1-beta"
git push
```
