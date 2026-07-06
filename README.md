# RD App

AI 对话助手 + 双摄录像 + 离线 OCR 文字识别的多功能 Android 应用。

## 功能

| 功能 | 描述 |
|---|---|
| **AI 聊天** | 对接 OpenAI 兼容 API（DeepSeek 等），支持多轮对话，历史本地持久化 |
| **离线 OCR** | 拍照/相册/实时扫描 三种模式，完全离线，支持中英文识别 |
| **双摄录像** | 前后摄像头同时录制，实时时间戳叠加，自动分段（5分钟） |
| **拍照** | 自动叠加时间戳水印，一键保存到系统相册 |
| **配置管理** | 自定义 API 地址、密钥、模型名称 |

## 技术栈

- **语言**: Kotlin 2.1.0
- **UI**: Jetpack Compose + Material 3 (BOM 2025.06.01)
- **架构**: MVVM (ViewModel + StateFlow)
- **构建**: Gradle 9.3.0 / AGP 8.9.0
- **最低支持**: Android 7.0 (API 24)
- **目标版本**: Android 15 (API 36)

## 核心依赖

| 依赖 | 用途 |
|---|---|
| `androidx.compose.material3` | Material 3 UI 组件 |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | MVVM ViewModel |
| `com.google.mlkit:text-recognition-chinese:16.0.1` | **离线 OCR 引擎** |

## 构建

```bash
# Debug
./gradlew assembleDebug

# Release（需要签名配置）
./gradlew assembleRelease
# APK 输出: app/build/outputs/apk/release/rd_app_v1.0_release.apk
```

签名配置在 `local.properties` 中：

```properties
KEYSTORE_PASSWORD=xxx
KEY_ALIAS=xxx
KEY_PASSWORD=xxx
```

---

## OCR 模型说明

### 使用的模型

本应用集成了 **Google ML Kit Text Recognition V2** 的中文识别模型：

```
Maven 坐标: com.google.mlkit:text-recognition-chinese:16.0.1
```

该模型基于深度神经网络，支持 **简体中文、繁体中文、日文、韩文、拉丁字母** 的混合识别，涵盖**印刷体**和**手写体**。

### 模型获取方式

模型文件随 Gradle 依赖**自动打包到 APK 中**，无需手动下载。

**Maven 仓库地址**（Gradle 构建时自动拉取）：

```
https://maven.google.com/web/index.html#com.google.mlkit:text-recognition-chinese
```

直接下载 AAR 的镜像站点：

| 站点 | 地址 |
|---|---|
| **Google Maven 官方** | `https://dl.google.com/dl/android/maven2/com/google/mlkit/text-recognition-chinese/16.0.1/` |
| **Maven Central 镜像** | `https://repo1.maven.org/maven2/com/google/mlkit/text-recognition-chinese/16.0.1/` |
| **阿里云 Maven 镜像**（国内推荐） | `https://maven.aliyun.com/repository/google/com/google/mlkit/text-recognition-chinese/16.0.1/` |

### AAR 中包含的模型文件

`text-recognition-chinese` AAR 内包含以下 native 模型：

```
AAR 内部结构:
├── jni/
│   ├── arm64-v8a/libmlkit_google_ocr_pipeline.so   (~8 MB)
│   ├── armeabi-v7a/libmlkit_google_ocr_pipeline.so  (~6 MB)
│   └── x86_64/libmlkit_google_ocr_pipeline.so       (~10 MB)
├── assets/
│   └── mlkit_ocr_models/                            (识别模型参数)
└── classes.jar                                      (Java/Kotlin API)
```

模型总大小约 **8-16 MB**（取决于最终 APK 包含的 CPU 架构）。

### 模型加载时机

- **首次使用** OCR 功能时，ML Kit 自动从 APK 中提取模型文件到应用内部存储
- **后续使用**直接加载已提取的模型，无额外开销
- **全程离线**，不需要网络连接

### 与 Play Services 版本的区别

> ⚠️ **不要使用** `com.google.android.gms:play-services-mlkit-text-recognition`

| | 本应用使用（Bundled） | Play Services 版 |
|---|---|---|
| 依赖 | `text-recognition-chinese` | `play-services-mlkit-text-recognition` |
| 模型位置 | APK 内打包 | 运行时从 Google Play 下载 |
| 离线能力 | ✅ 完全离线 | ❌ 首次需联网下载 |
| APK 体积 | +8-16 MB | 不增加 |
| 可靠性 | 高（无外部依赖） | 取决于 Google Play Services 可用性 |

### 识别性能参考

| 指标 | 数值 |
|---|---|
| 单帧识别耗时 | 300-500ms |
| 中文印刷体准确率 | 90-95% |
| 中文手写体准确率 | 80-88% |
| 内存占用 | 15-45 MB |
| 硬件加速 | NNAPI（Android 8.1+ 自动启用） |

### 模型版本更新

Google 会不定期更新识别模型，升级方式：

```toml
# gradle/libs.versions.toml
[versions]
mlkitTextRecognition = "16.0.1"  # 修改此版本号

# 然后同步 Gradle 即可
```

查看最新版本：[Google Maven - ML Kit](https://maven.google.com/web/index.html#com.google.mlkit:text-recognition-chinese)

---

## 项目结构

```
app/src/main/java/com/rd/rd_app/
├── MainActivity.kt              # 单 Activity 入口 + 导航状态机
├── ConfigManager.kt             # SharedPreferences 持久化单例
├── VideoRecorder.kt             # Camera2 双摄录像
├── GlVideoRenderer.kt           # OpenGL ES 时间戳渲染
└── ui/
    ├── theme/
    │   ├── Color.kt             # Material3 调色板（深色/浅色）
    │   ├── Type.kt              # 字体排版
    │   └── Theme.kt             # RdAppTheme
    └── screen/
        ├── chat/                # AI 聊天
        ├── config/              # 设置页面
        ├── login/               # 登录页面
        ├── profile/             # 个人中心 + 快捷操作
        └── ocr/                 # 离线 OCR（新增）
            ├── OcrViewModel.kt  # OCR 状态管理 + ML Kit 调用
            └── OcrScreen.kt     # OCR 界面（拍照/相册/实时扫描）
```

## 权限说明

| 权限 | 用途 |
|---|---|
| `INTERNET` | AI 聊天 API 调用 |
| `CAMERA` | 拍照、录像、OCR 实时扫描 |
| `RECORD_AUDIO` | 双摄录像音频采集 |
