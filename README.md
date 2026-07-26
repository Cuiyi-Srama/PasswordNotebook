# 🗝️ 密码の小本 (PasswordNotebook)

[![GitHub release](https://img.shields.io/github/v/release/Cuiyi-Srama/PasswordNotebook)](https://github.com/Cuiyi-Srama/PasswordNotebook/releases)
[![License](https://img.shields.io/github/license/Cuiyi-Srama/PasswordNotebook)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-21%2B-brightgreen)](app/build.gradle)
[![Downloads](https://img.shields.io/github/downloads/Cuiyi-Srama/PasswordNotebook/total)](../../releases)

> **从 WiFi 密码生成器进化而来的全功能密码管理器**  
> AES-256-GCM 加密存储 · 矩阵代码雨 · 赛博朋克风格  
> **→ [下载最新 APK](https://github.com/Cuiyi-Srama/PasswordNotebook/releases/latest)**

---

## ✨ 功能一览

### 🔐 密码生成

| 模式 | 算法 | 说明 |
|:--|:--|:--|
| **哈希生成** | SHA-256 → Base64 | 核心词 + 年份-周数，确定性强，可重复 |
| **周期密码** | SHA-256 → Base64 | 自定义盐 + 时间，定期轮换 |
| **随机密码** | SecureRandom | 可配置长度/字符集，含强度评估 |

### 🛡️ 加密存储

- **AES-256-GCM** 加密所有密码记录
- 12 字节随机 IV，128 位 GCM 认证标签
- 密钥基于 **PBKDF2WithHmacSHA256**（10000 次迭代）派生

### 🔣 符号类型拆分

| 类别 | 字符集 | 适用场景 |
|:--|:--|:--|
| **常见符号** | `! @ # $ % ^ & * - _ = + .` | 所有网站/设备都接受 |
| **扩展符号** | `[ ] { } ( ) / \ ` | 老旧系统可能拒绝，按需开启 |

两个开关独立控制，适应不同网站的密码规则限制。

### 📤 批量导入/导出

- **明文导出**：Tab 分隔 TXT，可直接编辑后重新导入
- **加密导出**：保持内部加密格式
- **自动识别加密格式**：粘贴加密备份内容自动走加密通道
- **重复名称自动跳过**，安全防覆盖

### 🎨 界面

- **矩阵代码雨背景**（日文片假名 + 字母 + 数字混合）
  - 800~1600 px/s 高速下落，头部高亮、尾部渐变淡出
- 暗色赛博朋克主题，玻璃风卡片
- 纯代码 UI，无 XML 布局依赖

### 🔒 隐私安全

- **零权限申请** — 不请求任何敏感权限
- 无网络访问权限，数据完全本地存储

---

## 📦 下载与安装

从 [Releases](https://github.com/Cuiyi-Srama/PasswordNotebook/releases/latest) 下载 `PasswordNotebook-v2.1.apk` 直接安装。

或自行构建：

```bash
git clone https://github.com/Cuiyi-Srama/PasswordNotebook
cd PasswordNotebook
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

---

## 🛠️ 技术栈

| 技术 | 版本 |
|:--|:--:|
| 语言 | Java |
| 最小 SDK | 21 (Android 5.0) |
| 目标 SDK | 35 (Android 15) |
| 构建系统 | Gradle 8.5 + AGP 8.2.0 |
| UI | 纯代码（无 XML） |
| 加密 | AES-256-GCM / PBKDF2 |

---

## 🧬 项目结构

```
app/src/main/java/com/cuiyi/passwordnotebook/
├── MainActivity.java       # UI + 矩阵雨背景
├── PasswordGenerator.java  # 密码生成引擎
└── CryptoHelper.java       # AES-256-GCM 加密模块
```

---

## 📜 许可证

[MIT License](LICENSE) — 自由使用、修改和分发。

---

*密码の小本 — 赛博朋克风格的密码守护者* 🗝️