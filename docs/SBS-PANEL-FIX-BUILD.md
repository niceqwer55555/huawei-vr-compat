# SBS 面板修复：构建与换芯落地（2026-11-26）

> 配套 [`docs/SBS-PANEL-FIX.md`](SBS-PANEL-FIX.md)（问题与代码改动）。本文讲**怎么把修复真正装到设备上**。
> 已在本机完成端到端验证：换 dex → zipalign → 重签 → 验签 → 包名一致，其余 613 个条目逐字节不变。

## 1. 为什么不重编 7 个整包

仓库的 `build/build-module.ps1` 是 Windows 专用（PowerShell + .bat + apktool + aapt2），
且依赖预先生成的 `decode/` 反编译树——沙箱/干净 clone 里没有这棵树，36 个 manifest/smali
补丁也由另一套流程固化在**设备上已安装的 APK**里，无完整可复现的自动化。

但兼容层是作为**独立 dex**注入的（历史固定名 `classes4.dex`，含 `VrPlatformCompat`
等 26 个类），所以我只需要重编这一份 dex 并换进去，就能让修复生效——不动其他任何补丁。
仓库自带的 `fix/patches/vrdesktop/apply_panel_bootstrap.sh` / `inject_compat_dex.py`
早已验证过这条"换 classes4.dex"路线。

## 2. 依赖准备（一次性）

工具链大二进制按仓库惯例不入库，需自备：

| 依赖 | 用途 | 获取 |
|---|---|---|
| `javac` / `java` | 编译 compat 层 | JDK 17（或任何支持 `-source 8 -target 8` 的 JDK） |
| `android.jar` | 编译期 Android API | Android SDK `platforms/android-30/android.jar`，或 `curl -L https://dl.google.com/android/repository/platform-30_r03.zip` 解出 |
| `r8.jar` | D8 把 class 转 dex | `curl -L https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.9.35/r8-8.9.35.jar` |
| `zipalign` / `apksigner` | 对齐 + 重签 | Android build-tools 34：`https://dl.google.com/android/repository/build-tools_r34-linux.zip` |
| `adb` | 拉/装 APK | platform-tools |

放入工程后用环境变量指路径，例如：
```bash
export ANDROID_JAR=/path/to/android.jar
export D8_JAR=/path/to/r8.jar
export APKSIGNER=/path/to/build-tools/apksigner
export ZIPALIGN=/path/to/build-tools/zipalign
export ADB=/path/to/platform-tools/adb
```

## 3. 重新生成 compat dex（源码改动后必做）

```bash
sh tools/build-compat-dex.sh
# 产物：build/compat-dex/compat-sbs-panel-fix.dex（约 101 KB，含 largestDisplay 修复 + 单路 GL 双画）
```
该脚本 = `build-module.ps1` 的"编译 stubs → 编译 shared/java → d8"三步的 Linux 版，
**已随本次修复编译好**，正常情况下无需重跑；只有再次改动 `shared/java` 时才需要。

> **两种 compat dex 的区别（全量编译已验证，别混用）**：
>
> | dex | 源码 | 大小 | 打包进 |
> |---|---|---|---|
> | 通用 | `shared/java`（66 源 → 86 类） | ~101 KB | 除 Runtime 外的包（含 vrservice / vrvirtualscreen / vrlauncherx） |
> | Runtime 专用 | 上面 + `shared/runtime-actor/java`（+4 源 → 97 类） | ~113 KB | **只** `com.huawei.hvrsdkserverapp` |
>
> 本次修复的类（`VrPlatformCompat` / `HvrPanelFinder` 面板按面积；
> `HvrMirrorService` / `HvrMirrorGlView` 单路 GL 双画）都在**通用** dex 里，
> 所以 `build-compat-dex.sh` 只产通用 dex 就够了。Runtime 专用 dex 多出的
> `VrSystemActor` 等系统侧类与本修复无关，不要拿它去换 vrservice。
>
> 全量编译验证（2026-11-26）：stubs 9 类、通用 86 类、Runtime 97 类均编译通过；
> 通用 dex sha256 = `91719256a4ea90713557c0d8c7ee8eeb4de8f8e2dd45d7749832db9784cef4b9`
> （2026-11-26 单路 GL 双画改造后重编；连续两次编译字节一致），与已落盘
> `compat-sbs-panel-fix.dex` 逐字节一致；已用本地底本完成换芯→对齐→签名→验签
> 端到端验证，其余 613 条目逐字节不变（目标 `classes4.dex` 95998→103416 B）。
> 若换芯脚本报告 sha 不同，以设备端校验修复类/方法存在为准（D8 版本差异会让
> 字节不同，不影响修复逻辑）。

## 4. 换芯到设备（核心）

前提：设备上已装**打过 compat 补丁**的 APK（含 `VrPlatformCompat` 类的那份 dex）。
典型是 `com.huawei.vrlauncherx`（桌面创建 Presentation 面板）与
`com.huawei.vrvirtualscreen`（投屏面板查找）。

```bash
# 桌面包（面板 Presentation 的宿主）
sh tools/apply-sbs-panel-fix.sh <serial> com.huawei.vrlauncherx
# 投屏包
sh tools/apply-sbs-panel-fix.sh <serial> com.huawei.vrvirtualscreen
```

脚本流程（幂等，每次从设备拉当前 APK 作底本）：
1. `adb pull` 当前 APK → 备份；
2. 自动定位含 `VrPlatformCompat` 的那份 dex（不写死 classes4）；
3. 校验新 dex 含修复类与方法；
4. `replace_dex.py` 替换，其余条目逐字节保留；
5. `zipalign -p -f 4` + `apksigner`（沿用 hvr-compat 签名，无需卸载）；
6. `pm install -r` 回装。

## 5. 启用与验收

```bash
# 切到 dual 档：小屏满足在位判据，大屏当面板
sh fix/tools/vr-overlay-mode.sh <serial> dual
# 重启 VR 桌面让 panel-bootstrap 重建 Presentation

# 验收：面板应挂到大屏，不再是 100x100
adb -s <serial> shell logcat -d | grep -E 'panel by|panel presentation shown|HvrPanelFinder'
adb -s <serial> shell dumpsys display | grep -i 'HUAWEI VR Display'
```

期望日志关键字：`panel by presentation category: #N ... 3200x1600` 或
`panel presentation shown on display N`，N 对应 3200×1600 大屏 id；`HUAWEI VR Display`
尺寸不再是 100×100；每眼 SurfaceView = 1600×1600（正方形，与物理面板 1:1）。

## 6. 边界

- **不是全部 SBS 问题**：`vr_display` 前缓冲直出（`IVrServiceManager.startFrontBufferDisplay`）
  仍未实现，见 `PATCH_AUDIT.md` §5.7。本修复只保证"一旦有画面，Presentation 挂对面板"。
- **底本必须已打 compat 补丁**：纯原包（只有 classes.dex、无 `VrPlatformCompat`）不适用，
  脚本会在第 2 步明确报错退出。
- **需对实际承载面板的包执行**：不确定哪个包时，对 `vrlauncherx` 和 `vrvirtualscreen`
  都执行一次，无害。
- **真机未验**：端到端流程已在本机用模拟底本跑通，但视觉结果仍需在眼镜真机上按第 5 节确认。
