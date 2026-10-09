# keystore 目录说明

这个目录存放**安卓签名密钥**，已被 `.gitignore` 排除（只有本说明文件会进仓库）。

## 文件说明

| 文件 | 是否进仓库 | 说明 |
|---|---|---|
| `coursewidget.p12` | ❌ **绝对不要** | 签名密钥（含私钥），用于给 APK 签名 |
| `keystore.properties` | ❌ **绝对不要** | 密钥口令 |
| `cert.pem` | ❌ | 证书，仅用于查看指纹 |
| `coursewidget.p12.base64` | ❌ | 密钥的 Base64（贴到 GitHub Secret 用） |
| `README.md` | ✅ | 本文件 |

## 丢了怎么办

如果 `coursewidget.p12` 丢失：**已经发布的版本无法再被覆盖安装**，用户必须卸载重装。
所以请务必单独备份这个文件（例如存到密码管理器或私有网盘）。

如果只是本地丢了、GitHub Secret 里还在，可以从 Secret 的 Base64 还原：

```powershell
[IO.File]::WriteAllBytes("keystore\coursewidget.p12", [Convert]::FromBase64String($env:KEYSTORE_BASE64))
```

## 重新生成

```powershell
powershell -ExecutionPolicy Bypass -File ..\gen-keystore.ps1 -OutDir .
```

> ⚠️ 重新生成会产生**不同的签名**，导致老版本无法覆盖安装。只在确实需要时这么做。
