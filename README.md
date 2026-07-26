# PasswordNotebook

> 🗝️ **Android 密码备忘录** — AES-256-GCM 加密 · 零权限 · 纯本地  
> 下载 APK 装到手机上，当你的加密密码小本  
> **→ [📥 下载最新 APK](https://github.com/Cuiyi-Srama/PasswordNotebook/releases/latest)**

[![GitHub release](https://img.shields.io/github/v/release/Cuiyi-Srama/PasswordNotebook)](https://github.com/Cuiyi-Srama/PasswordNotebook/releases)
[![License](https://img.shields.io/github/license/Cuiyi-Srama/PasswordNotebook)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-21+-brightgreen)](app/build.gradle)
[![APK大小](https://img.shields.io/badge/APK-152KB-success)](../../releases)

---

## 这是什么？

一款 Android App。装到你手机上，用来记密码。

你记不住的 WiFi 密码、网站登录、应用账号……全存在这个小本里。AES-256-GCM 加密保护，只有你能看。

- 下载 APK → 点击安装 → 直接开始用
- 零权限，不联网，不上传，纯粹本地存储
- 整个 App 才 152KB，比一张照片还小

---

## 特色功能

### 三重密码生成引擎

不只是备忘录——还能帮你生成高强度密码：

| 模式 | 算法 | 适合场景 |
|:--|:--|:--|
| **哈希生成** | 核心词 + 当前周数 → SHA-256 | 定期换的 WiFi 密码、有固定规则的密码 |
| **周期密码** | 自定义盐 + 时间 → SHA-256 | 临时密码、共享密码、定期轮换 |
| **随机密码** | SecureRandom 全配置 | 新注册账号、需要强密码的场景 |

> 哈希生成模式下，只要你知道核心词，在任何 Android 设备上装这个 App 都能算出同一个密码。手机丢了？借别人手机装一个，输核心词就能找回所有密码。

### AES-256-GCM 加密存储

所有存入的记录都经过加密：

- **AES-256-GCM** — 当前最安全的对称加密算法之一
- **12 字节随机 IV** — 每条记录用不同的加密盐值
- **PBKDF2 密钥派生** — 10000 次哈希迭代，暴力破解成本极高
- **加密/明文双导出** — 加密备份只有本 App 能解密

### 符号类型智能拆分

生成密码时，常见符号和扩展符号分开控制，适应不同网站的密码规则：

- 常见 `! @ # $ % ^ & * - _ = + .` → 默认开启，所有网站都兼容
- 扩展 `< > [ ] { } ( ) / \` → 按需开启，适配老旧系统

### 矩阵代码雨 · 赛博朋克 UI

- 全屏动态代码雨背景，片假名 + 字母 + 数字混合
- 800~1600 px/s 高速下落，头部亮绿、尾部渐变淡出
- 暗色主题，玻璃风卡片，全部 UI 纯代码绘制

---

## 使用场景

| 场景 | 说明 |
|:--|:--:|
| 🏠 WiFi 密码管理 | 哈希生成模式，设好核心词，每周自动换密码 |
| 👤 个人账号备忘录 | 保存网站/App 的账号密码，加密存储，随时查阅 |
| 🔄 共享临时密码 | 周期密码模式，设盐值后定期自动轮换 |
| 📦 批量迁移 | 导出加密备份 → 导入新手机，一键搬家 |

---

## 安装

从 [Releases 页面](https://github.com/Cuiyi-Srama/PasswordNotebook/releases/latest) 下载 `PasswordNotebook-v2.1.apk`，在 Android 手机上打开即可安装。

或自己构建：

```bash
git clone https://github.com/Cuiyi-Srama/PasswordNotebook
cd PasswordNotebook
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

---

## 技术栈

| 项目 | 说明 |
|:--|:--:|
| 平台 | Android |
| 语言 | Java |
| 最低版本 | Android 5.0（API 21） |
| 目标版本 | Android 15（API 35） |
| 构建 | Gradle 8.5 + AGP 8.2.0 |
| UI | 纯代码，零 XML 布局 |
| 加密 | AES-256-GCM / PBKDF2WithHmacSHA256 |
| 权限 | **零权限申请** |

---

## 项目结构

```
PasswordNotebook/
└── app/src/main/java/com/cuiyi/passwordnotebook/
    ├── MainActivity.java       # UI 全部代码 + 矩阵雨背景绘制
    ├── PasswordGenerator.java  # 三种密码生成模式
    └── CryptoHelper.java       # AES-256-GCM 加密与解密
```

---

## 许可证

[MIT License](LICENSE) — 随意使用、修改、分发。

---

*PasswordNotebook — 装进口袋的赛博朋克密码守护者* 🗝️