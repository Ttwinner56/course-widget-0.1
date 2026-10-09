# 签名与发布配置

## 为什么要固定签名

安卓规定：**同一个包名（`com.veid.coursewidget`）的 App，必须用同一个签名才能覆盖安装**。

之前每次用 GitHub 云端构建，用的都是服务器临时生成的调试签名，每次都不一样，所以你每装一次新版都得先卸载。现在换成**一套固定密钥**，配好一次，以后所有版本都能直接覆盖安装。

## 密钥信息

| 项目 | 值 |
|---|---|
| 密钥库文件 | `keystore/coursewidget.p12` |
| 类型 | PKCS#12（传统加密，Java keytool 兼容） |
| 别名 alias | `coursewidget` |
| 库口令 / 密钥口令 | `CourseWidget2026!` |
| 有效期 | 至 **2054-02-24**（10000 天） |
| 算法 | RSA 2048 / SHA256withRSA |
| 证书 SHA-256 指纹 | `11:FC:95:D8:4E:1C:B3:A0:91:CD:8F:93:E5:64:BA:F8:5D:26:EF:8F:85:CD:A9:2A:6D:6F:F7:2F:7F:A8:F0:8D` |

> **请务必备份** `keystore/` 目录（尤其 `coursewidget.p12`）。一旦丢失，以后所有版本都无法覆盖安装，用户只能卸载重装。
>
> `keystore/coursewidget.p12` 和 `keystore/keystore.properties` 已被 `.gitignore` 排除，**不会**进仓库。

重新生成（如果密钥丢了，可用于之后的新版本）：

```powershell
powershell -ExecutionPolicy Bypass -File gen-keystore.ps1 -OutDir .\keystore
```

## 配置 GitHub Secrets（只做一次）

打开 **https://github.com/Ttwinner56/course-widget-0.1/settings/secrets/actions** → **New repository secret**，添加这 4 个：

| Secret 名称 | 值 |
|---|---|
| `KEYSTORE_BASE64` | `coursewidget.p12` 的 Base64 文本 |
| `KEYSTORE_PASSWORD` | `CourseWidget2026!` |
| `KEY_ALIAS` | `coursewidget` |
| `KEY_PASSWORD` | `CourseWidget2026!` |

生成 Base64 并直接复制到剪贴板：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\coursewidget.p12")) | Set-Clipboard
```

> 仓库里的 `keystore/coursewidget.p12.base64` 也是同一份内容，可以直接打开复制。
>
> 没配这些 Secret 时构建不会失败，只会产出**未签名**的 APK（无法安装），并在日志里给出警告。

## 本机构建

```powershell
powershell -ExecutionPolicy Bypass -File build-local.ps1
```

需要 JDK 17 + Android SDK（`ANDROID_HOME`）+ Gradle 8.7。产物：

```
app\build\outputs\apk\release\CourseWidget-v0.1.0-release.apk
```

## 发布新版本的流程

1. 改 `version.json`：

   ```json
   {
     "versionName": "0.1.1",
     "versionCode": 1010,
     "stage": "beta",
     "notes": "改了什么,写这里,会自动进 Release 说明"
   }
   ```

   `versionCode` 规则：`主*1000000 + 次*1000 + 修*10`（`0.1.0` → `1000`，`0.1.1` → `1010`），必须比上一版大。

2. 在 `CHANGELOG.md` 表格顶部加一行
3. 提交并推送：

   ```powershell
   git add -A
   git commit -m "chore: 发布 v0.1.1-beta"
   git push
   ```

CI 会自动完成：构建 → 打 tag（`v0.1.1-beta`）→ 发布 Release（附 APK）→ 回写 README 的版本信息。

**同一版本重复推送不会重复发版**（tag 已存在时跳过）。想重发，需要先在 GitHub 删除该 tag 和 Release。
