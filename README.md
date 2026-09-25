# 密码小本 PasswordNotebook

一个不申请任何权限的 Android 密码管理器。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## 它是什么

本地密码库。所有数据用 **AES-256-GCM** 加密后写到应用私有目录，密钥由你的主密码派生，应用不联网、不申请任何权限。

## 安全模型

这是整份文档里最该读的部分。

```
主密码（只在你脑中）
     │
     ▼
PBKDF2-HMAC-SHA256（随机盐 16 字节，迭代次数按设备实测校准）
     │
     ▼
256 位密钥（只存在内存，退出即清除）
     │
     ▼
AES-256-GCM  ──►  vault.pwdnb
```

具体保证：

- **没有硬编码密钥。** 反编译 APK 拿不到任何可用于解密的内容。
- **盐是随机的，且每台设备不同。** 防止彩虹表，也防止一个用户的数据被拿去比对另一个用户的。
- **迭代次数是校准出来的，不是写死的。** 首次创建时实测，让派生耗时落在约 300ms 左右，结果写进文件头。
- **加密失败不会降级成明文。** 旧版本在加密异常时直接返回原文，这是致命的；现在一律抛异常。
- **写入是原子的。** 先写临时文件再重命名，中途崩溃不会毁掉已有数据库。
- **GCM 自带完整性校验。** 文件被改动过会解密失败，而不是解出一堆垃圾。

### 它不作什么保证

- 不防已 root 且已注入调试器的设备
- 不防键盘记录器
- 主密码忘了就没救了（这也意味着没有后门）

## 功能

**生成**
密码生成器，可选大小写、数字、常见/扩展符号，长度可调。字符集选择用拒绝采样以避免模偏差，并保证每种启用的字符类型至少出现一次。

**记录**
搜索、查看、复制、编辑、删除。默认隐藏密码，点击才显示。

**导入**
支持从旧版本导入：
- 旧的加密导出（字段间用 U+2561 分隔）
- 旧的整行加密格式
- 明文 TSV / CSV

导入时自动识别格式，断行不会导致整体失败。

**导出**
- 加密备份（可安全放进网盘）
- 明文 CSV（会弹窗警告，仅用于迁移到别的密码管理器）

## 从旧版本升级

旧版本（v2.x）把 AES 密钥硬编码在 APK 里，任何反编译的人都能读取全部数据。**如果你用过旧版本，请把它当作已经泄露**，建议轮换重要账号的密码。

升级步骤：

1. 安装新版本，设置主密码
2. 设置页 → 导入旧版备份 → 粘贴旧备份内容
3. 确认条数正确后删除旧备份文件
4. 考虑轮换高价值凭据

## 构建

需要 JDK 17+ 和 Android SDK（build-tools 35.0.0）。

```bash
git clone https://github.com/Cuiyi-Srama/PasswordNotebook
cd PasswordNotebook
```

推荐用 GitHub Actions 构建：推一个 `v*` 标签即可，产物会自动附加到 Release。

本地构建也可以，但如果宿主机是 arm64 而 `aapt2` 只有 x86_64 版本，需要 qemu 转译。

## 发布签名

正式包用 `keystore` 签名，密钥不进仓库。CI 从 Secrets 读取：

- `KEYSTORE_BASE64` — keystore 文件的 base64
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

fork 后想自己发包，把这些 Secrets 换成你自己的即可。

## 目录结构

```
app/src/main/java/com/cuiyi/passwordnotebook/
  KeyDerivation.java     PBKDF2 派生、盐、迭代校准
  CryptoHelper.java      AES-256-GCM，fail-closed
  VaultCrypto.java       vault 文件格式的读写
  Vault.java             存储门面：锁定/解锁/读写
  LegacyCrypto.java      旧格式解密（仅导入用）
  LegacyImporter.java    旧格式解析
  PasswordGenerator.java 密码生成
  Entry.java             单条记录
  MainActivity.java      UI
```

## 文件格式

```
PWDNB4
kdf=pbkdf2-hmac-sha256
iter=600000
salt=<base64, 16 bytes>
check=<base64>
---
<base64 ciphertext>
```

头部是明文，因此可以在不知道密码的情况下读出 KDF 参数，也就能区分「密码错了」和「文件坏了」。正文是一个 JSON 数组（`t`/`p`/`n`/`g`/`u`）整体加密。

## 许可

MIT，见 [LICENSE](LICENSE)。

## 免责

这是个人项目。密码管理器属于一旦出错后果严重的软件，正式存储重要凭据前请自行审阅代码并做好备份。
