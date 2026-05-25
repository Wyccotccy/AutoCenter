# AutoCenter - 安卓无障碍自动居中

基于 ORB 特征匹配的屏幕目标检测 + 自动居中工具。

## 功能

- **ORB/AKAZE 特征匹配** — 检测屏幕中的特定目标（图像/Logo/水印），不受旋转/缩放影响
- **多实例检测** — 同一画面中出现多个相同目标时，全部框选
- **多模板** — 最多 15 个模板，同时活跃最多 5 个（可配置）
- **自动居中** — 检测到目标后，通过无障碍手势滑动到屏幕中央
- **自校准滑动** — 自动测试不同位置的滑动反馈，自适应设备灵敏度
- **瞄准框** — 全屏悬浮红框（触控穿透，不干扰操作），独立开关
- **测试模式** — 绿色框预览匹配效果
- **横屏适配** — 原生支持

## 技术栈

| 组件 | 方案 |
|---|---|
| 捕获方案 | MediaProjection + ImageReader 帧流（10fps） |
| 图像匹配 | OpenCV ORB + FLANN + Lowe's Ratio Test + 空间聚类 |
| 居中手势 | AccessibilityService GestureDescription |
| 校准 | 闭环自校准（实测位移 → 修正乘数） |
| 悬浮框 | SYSTEM_ALERT_WINDOW + FLAG_NOT_TOUCHABLE |
| 界面 | Kotlin + Material 3 + ViewModel + ViewBinding |
| 构建 | Gradle + GitHub Actions（自动打包 APK） |

## 导入步骤（Android Studio）

### 方法 1：从 GitHub 克隆

```bash
git clone https://github.com/Wyccotccy/AutoCenter.git
cd AutoCenter
```

用 Android Studio 打开 `AutoCenter` 目录。

### 方法 2：手动导入

1. 下载项目 ZIP 解压
2. Android Studio → File → Open → 选择解压后的目录
3. 等待 Gradle 同步完成

## 首次运行

```
1. 安装 APK 到手机（Android 8.0+）
2. 打开 App → 点击「开启无障碍服务」
3. 在系统设置中开启「自动居中」无障碍服务
4. 回到 App → 点击「上传目标图案」→ 选择你要识别的图片
5. 进入配置页（右下角齿轮）→ 可调整匹配参数
6. 回到主界面 → 系统会弹窗授权屏幕捕获 → 允许
7. 开启「显示瞄准框」和「启用自动居中」开关
```

## OpenCV 集成方法

项目使用 Maven 依赖方式：

```gradle
implementation 'com.quickbirdstudios:opencv:4.8.0'
```

无需手动下载 OpenCV Android SDK。如果遇到依赖问题：

1. 在 `build.gradle`（project 级）中添加 jitpack 仓库：
   ```gradle
   allprojects {
       repositories {
           maven { url 'https://jitpack.io' }
       }
   }
   ```
2. 或删除 `settings.gradle` 中的 `dependencyResolutionManagement` 块

## 真机安装

### 方案 A：从 GitHub Actions 下载预编译 APK

1. 前往 GitHub 仓库 Actions 页面
2. 选择最新的成功 workflow run
3. 下载 **AutoCenter-APK** artifact
4. 解压得到 `app-debug.apk` 或 `app-release.apk`

### 方案 B：本地编译

```bash
git clone https://github.com/Wyccotccy/AutoCenter.git
cd AutoCenter
chmod +x gradlew
./gradlew assembleDebug
# APK 生成在 app/build/outputs/apk/debug/
```

## 无障碍服务开启步骤

```
设置 → 辅助功能 → 已安装的应用 → 自动居中 → 开启
```

某些定制 ROM（MIUI、EMUI 等）路径可能不同，一般在：
- 设置 → 更多设置 → 无障碍 → 自动居中
- 或在系统设置中搜索「无障碍」

## 权限说明

| 权限 | 用途 |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` | 执行滑动居中操作 |
| `SYSTEM_ALERT_WINDOW` | 显示瞄准红框（触控穿透） |
| `FOREGROUND_SERVICE` | 屏幕捕获必须前台运行 |
| `READ_MEDIA_IMAGES` | 上传模板图片 |
| `POST_NOTIFICATIONS` | 前台服务通知 |

## 配置说明

| 参数 | 默认 | 说明 |
|---|---|---|
| 匹配灵敏度 | 75 | 0-100，越高要求匹配越精准 |
| 降采样比例 | 50% | 降低处理分辨率提升速度 |
| 帧率 | 10fps | 每秒检测次数 |
| 最大活跃模板 | 5 | 同时参与匹配的模板数 |
| 匹配算法 | ORB | ORB（更快）或 AKAZE（更抗形变） |

## 校准

App 启动后会通过 5 次不同方向的自动滑动来校准居中的灵敏度系数，适应不同设备的屏幕特性。可在配置页查看和重置校准值。

## 注意事项

1. 某些系统（MIUI/HarmonyOS）可能限制后台屏幕捕获，请确保 App 在前台或加锁运行
2. 降采样比例越低速度越快，但匹配精度也会下降，建议保持 50%
3. 模板图片建议裁剪为只包含目标区域，减少干扰
4. 如果目标在画面中频繁旋转，建议使用 AKAZE 算法

## GitHub 部署

```bash
git init
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/Wyccotccy/AutoCenter.git
git push -u origin main
```

GitHub Actions 会自动构建 APK，可在 Actions 页面下载。

## License

MIT
