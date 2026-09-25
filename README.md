# 密码小本 PasswordNotebook

一个不申请任何权限的 Android 密码管理器。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## 它是什么

本地密码库。数据用 **AES-256-GCM** 加密后写在应用私有目录，密钥由你的主密码派生。不联网，不申请权限。

界面走赛博科技风格：下落字形背景、玻璃质感卡片、按压缩放反馈、密码逐字显示。

## 安全模型

这是最该读的一节。

```
主密码（只在你脑中）
     │
     ▼
PBKDF2-HMAC-SHA256（随机盐 16 字节，迭代次数按本机实测校准）
     │
     ▼
256 位密钥（只存内存，退出即擦除）
     │
     ▼
AES-256-GCM ──► vault.pwdnb
```

具体保证：

- **没有硬编码密钥。** 反编译 APK 拿不到能解密的内容。
- **盐随机且逐库不同。** 防彩虹表，也防跨用户比对。
- **迭代次数是校准的，不是写死的。** 首次创建时实测，让派生耗时落在 300ms 左右，结果写进文件头。
- **加密失败不会降级成明文。** 失败一律抛异常。
- **写入是原子的。** 先写临时文件再重命名，中途崩溃不会毁掉数据库。
- **GCM 自带完整性校验。** 文件被改动会解密失败。

### 不作保证

- 不防已 root 且已注入调试器的设备
- 不防键盘记录器
- 主密码忘了就没救（这也意味着没有后门）

## 功能

**生成**

两种模式：

- **随机模式**：每次全新生成，字符集与长度可调，拒绝采样避免模偏差，保证每种启用的字符类型都出现。
- **核心词模式**：从核心词 + 站点标识 + 周期算出密码。相同核心词在不同站点得到不相关的密码。核心词一旦泄露，这一模式下生成的全部密码都会泄露。

**记录**

搜索（带防抖）、查看、点击或长按显示密码、复制、编辑、删除。每条带时间戳。

**安全**

可选「解锁前要求系统验证」：启用时会在解锁前请系统确认锁屏。它只是一道门，真正解密数据库的仍是主密码。

**导入**

支持旧版本的数据：

- 旧版加密导出（字段间用 U+2561 分隔）
- 旧版整行加密
- 明文 Tab / 英文逗号 / 中文逗号分隔
- 名称一行、密码一行的双行格式

重复条目自动跳过。

**导出**

- 加密备份（可安全放进网盘）
- 明文 CSV（弹窗警告，仅用于迁移）

## 从旧版本升级

旧版（2.x）把 AES 密钥硬编码在 APK 里，任何反编译的人都能读取全部数据。**用过旧版本的话，请把它当作已经泄露**，建议轮换重要账号。

步骤：

1. 安装新版本，设置主密码
2. 设置 → 导入旧版备份 → 粘贴旧备份内容
3. 确认条数正确后删除旧备份
4. 轮换高价值凭据

## 目录结构

```
app/src/main/java/com/cuiyi/passwordnotebook/
  crypto/
    KeyDerivation.java   PBKDF2 派生、盐、迭代校准
    SecretCipher.java    AES-256-GCM，fail-closed
    VaultFile.java       PWDNB5 格式读写，原子写入
    CryptoException.java 加解密失败
  data/
    Entry.java           记录模型
    VaultJson.java       自研极简 JSON（零依赖）
    Vault.java           存储门面：锁定/解锁/读写
    LegacyReader.java    旧格式导入（只用于解密）
  gen/
    PasswordFactory.java 随机与核心词生成
  ui/
    Theme.java           配色与尺寸常量
    CyberRainView.java   下落字形背景
    Animations.java      按压反馈、逐字显示、防抖
  MainActivity.java      三个标签页
```

界面代码不接触密钥：所有存储访问都经过 `Vault`，`onPause` 时自动 `lock()`。

## 文件格式

```
PWDNB5
kdf=pbkdf2-hmac-sha256
iter=600000
salt=<base64, 16 bytes>
check=<base64>
---
<base64 ciphertext>
```

头部明文，因此可在不知道密码时读出 KDF 参数，也就能区分「密码错」和「文件坏」。正文是整体加密的 JSON。

## 构建

需要 JDK 17+ 与 Android SDK（build-tools 35.0.0）。

推 `v*` 标签即可让 GitHub Actions 自动构建并发布；签名密钥从仓库 Secrets 读取：

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

fork 后换成你自己的即可；没有这些 Secrets 时会退回构建 debug 包。

## 许可

MIT，见 [LICENSE](LICENSE)。

## 免责

个人项目。密码管理器一旦出错后果严重，正式存放重要凭据前请自行审阅代码并做好备份。
