# fix/ —— 华为 VR 全栈一键安装包（RK3566 盒子专用）

> 生成于 2026-11-02。目标机：`H96_Max_RK3566`，Android 11（SDK 30），Magisk 26.3，
> 必须**支持 armeabi-v7a**（7 个包里 6 个是 32 位）。
>
> **给后续 AI / 人的最短路径：**
>
> ```sh
> adb connect 192.168.43.133:5555        # 盒子 IP 以实际为准（无线 adb）
> sh install.sh 192.168.43.133:5555      # 完了。脚本幂等，可反复跑
> ```
>
> 只想重装模块/授权不动 APK：`sh install.sh <serial> --skip-install`
>
> **眼镜没亮 / 刷机后单独点亮**：双击 `一键点亮.bat`，或 `sh light-on.sh`
> （自包含，不用先跑完整安装）。只体检：`sh light-on.sh <serial> check`
>
> **开机重启后仍不亮（HWC 开机回落 1080p）**：见手册 6.11。
> `sh boot-light-on.sh <serial> check` 只读体检；确认只接眼镜后可
> `on` → `reboot` 让开机早期注入真实 `22f6` EDID 赢过 HWC probe；
> 接回普通显示器前必须 `off` → `reboot`。**默认不启用，不改开机脚本。**

---

## 一、目录结构

| 路径 | 内容 | 装到设备哪里 |
|---|---|---|
| `install.sh` | **一键安装脚本**（装 APK → 部署模块 → 授权 → 一键点亮 → 验收） | — |
| `light-on.sh` / `一键点亮.bat` | **一键点亮眼镜**（本地入口，自动 push 设备端脚本） | — |
| `一键修复VR框架.sh` / `一键修复VR框架.bat` | **修 VR 桌面链路**（装四个最终补丁 → 开模拟辅助显示 → 启 `.MainActivity`；`check/off/rollback/launcher`） | 手册第 8 章 |
| `patches/vrdesktop/` | 四个最终补丁 APK（launcherx / vrservice / hvrsdkserverapp / **vrhandle-pose**）、回退基线、最小差分构建脚本 | 手册第 8 章 |
| `apks/` | 7 个华为 VR 包，已去 sharedUserId、统一 hvr-compat 签名 | `pm install` |
| `magisk-module/modules/` | hvrglass 模块本体（EDID 按需注入 + hidraw 权限轮询） | `/data/adb/modules/hvrglass/` |
| `magisk-module/runtime/` | EDID 运行时切换脚本 + 1080p EDID + **light-on.sh 点亮脚本** | `/data/adb/hvrglass/` |
| `tools/` | 重签 / EDID 生成 / OTA 验证 / hwext 补丁 / 授权脚本 | PC 侧 |
| `signing/hvr-compat-debug.jks` | 签名密钥（PKCS#12，口令 `android`）——七个包必须同签名 | PC 侧 |
| `分析汇总.md` | 本轮全部分析的汇总（细节文档在仓库 `docs/`） | — |

## 二、install.sh 做了什么（按序）

1. **体检**：连接、ABI（要 armeabi-v7a）、root（`su` 必须可用）
2. **装 7 个 APK**（`install -r -d`；遇 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
   自动卸载重装——签名和旧包不一致时必走这条路，数据会丢）
3. **部署 hvrglass 模块**：`modules/` → `/data/adb/modules/hvrglass/`，
   `runtime/` → `/data/adb/hvrglass/`，权限 755/644
4. **`settings put system PRIVACY_ACCEPTED 1`**（绕过 launcherx 隐私页崩溃）
5. **补授权**（调 `tools/grant-vr-perms.sh`：appops、pm grant、deviceidle 白名单、日志级别）
6. **一键点亮眼镜**（调设备端 `light-on.sh`：白名单 bind mount → resolution 属性 →
   一次 `stop/start surfaceflinger` → 验收 3200x1600p70；`--no-light-on` 可跳过。
   眼镜没激活时脚本自检拒绝，不会强扭）
7. **验收**：包列表、进程列表、`vr_system` 状态
8. **启动应用市场**冒烟（`--no-launch` 可关）

模块的 EDID 逻辑（`post-fs-data.sh`，开机执行）：

| 开机时显示状态 | 行为 |
|---|---|
| 能读到屏幕真实 EDID（普通便携屏/显示器） | **不注入** |
| EDID 为空（VR Glass 眼镜的特征） | 注入 3200×1600@70（`common/edid_only.bin`） |
| 没接屏 | 不注入 |

模式覆盖：`echo auto|force-vr|force-off > /data/adb/hvrglass/mode`（默认 auto）。

## 三、装完怎么验证

```sh
# 1. 七个包都在
adb shell pm list packages | grep com.huawei

# 2. 应用市场能开（唯一 100% 可用的）
adb shell am start -W -n com.huawei.appmarket.vr/com.huawei.hwmarket.vr.MainActivity

# 3. 显示真实时序（不要信 dumpsys display，看内核）
adb shell su -c 'sh /data/adb/hvrglass/edid-mode.sh show'

# 4. HID 权限守护在跑（眼镜 IMU 用）
adb shell su -c 'tail /data/local/tmp/hidraw_perm.log'

# 5. 眼镜点亮链路体检（只读，不改任何东西）
sh light-on.sh 192.168.43.133:5555 check
# 6. 没亮 → 一键点亮（或双击 一键点亮.bat）
sh light-on.sh
```

点亮成功的硬指标：`Find best mode-id=4 : 3200x1600p70` +
`dri/0/summary` 里 `Display mode: 3200x1600p70 / clk[412180]`。
细节与踩坑见 `刷机后问题分析与恢复手册.md` 6.6。

预期状态：应用市场 2D 商城完全可用；launcherx / vrvirtualscreen 到「请连接华为 VR 眼镜」
门禁页；himovie 到协议页；vrhandle 手柄管理界面可用；vrservice / hvrsdkserverapp
进程常驻但空转。

## 四、已知问题与排障

### 1. 便携屏黑屏（最常见的翻车点）

不是线坏，是 EDID 被喂成 3200×1600@70 了。修：

```sh
adb shell su -c 'sh /data/adb/hvrglass/edid-mode.sh 1080p'
# 若仍未恢复：清掉强制分辨率属性再重启盒子（别用 ctl.restart surfaceflinger，
# 手册 4.2 实测它会让整机重启/卡死）
sh light-on.sh <serial> off
```

接回眼镜则直接一键点亮：`sh light-on.sh` 或双击 `一键点亮.bat`。
注意 `edid_override` 写入即常驻、写 0 清不掉。

**mode 的坑（实测）**：模块默认 `mode=auto` —— 只要能读到屏幕真实 EDID 就不注入，
这是**对的**。眼镜醒着时交出的就是 HWV 真实 EDID（厂商 `22f6`，自带
3200x1600@70/90 两条 DTD），别再注入覆盖它；眼镜没醒时读到的是转换器默认
EDID（`22d2`），这时按手册 4.3 先用 PC/手机点亮一次再插回盒子。

接眼镜的可靠做法（2026-11-05 实测固化，详见手册 6.6）：

```sh
sh light-on.sh          # 或双击 一键点亮.bat
```

⚠️ 别再用 `echo force-vr > /data/adb/hvrglass/mode && reboot`：开机阶段注入
EDID 会卡 logo（手册 4.1 两次实测），`force-vr` 绝不能固化。
白名单 + resolution 属性 + 一次 `stop/start surfaceflinger` 才是成功路径。

### 2. 眼镜里画面"超出范围"（图超大，每眼只有半幅）——不是时序问题

VOP 把 1920x1080 桌面**拉伸铺满 3200x1600 双目面板**，每眼只看到桌面一半。
切 @70/@90 无用（两模式总光栅完全相同 3308x1780）。渲染端必须出 SBS（左右格式）：
himovie.vr 播放模式立即可用；桌面级 SBS 要走 vrvirtualscreen / ALVR / 自写镜像，
均被 `vr_system` 缺失挡住（见问题 3）。

### 3. `vr_system` 未注册（**已在应用层绕过，见第 3.1 节**）

`service list | grep vr_system` 仍为空。根因：`vrservice` 原靠
`sharedUserId="android.uid.system"`（平台签名）由 system_server 注册服务，
改为普通应用后这条路断了。而 `vr_system` 是桌面进 VR 空间、虚拟屏投屏、SDK
运行时握手的总闸。

系统服务这条路并没有恢复，但 `一键修复VR框架` 已用**应用层注册表 + 动态代理**
把它绕过去：`com.huawei.vrlauncherx.registry` ContentProvider 托管 binder，
`VrSystemRegistryProvider` / `HvrStatePublisher` 分别发布 `vr_system` 与
`vr_system_state`；`HvrSdkBridge` 在 native `SetClass` 实际持有的 ClassLoader
上生成 `IVRSystemServiceManager` 代理，让 `HVRActivity.getSystemService("vr_system")`
能通过 `check-cast`，`isVRMode()` 从真实 `SystemServiceBinder` 读 `getVRModeState`。
所以这一项**不再是桌面进 VR 空间的阻塞**。

### 3.1 一键修复 VR 框架：四包与当前验收状态

```sh
sh fix/一键修复VR框架.sh                       # 或双击 一键修复VR框架.bat
sh fix/一键修复VR框架.sh <serial> check        # 只读验收
sh fix/一键修复VR框架.sh <serial> rollback     # 装回四个原版基线
```

四个最终包（统一 `hvr-compat` 签名；`vrhandle` 基线是华为原签，
脚本会自动检测签名冲突并「卸载→重装」，回退时同理）：

| 包 | 解决什么 |
|---|---|
| `com.huawei.vrlauncherx-full.apk` | compat dex + 补 ctx1 + 删 6 个空壳桩 + 恢复 `LibUpdateClient.runUpdate()` + `HvrSdkBridge`（统一 SetClass 类副本、`vr_system` 动态代理、真实 VRMode） |
| `com.huawei.vrservice-full.apk` | ctx0 归位 + 定义 signature 级 `com.huawei.android.permission.VR` |
| `com.huawei.hvrsdkserverapp-full.apk` | 5 组件归位 + 27 个 64 位库 + `HvrStatePublisher` 发布 `vr_system_state` |
| `com.huawei.vrhandle-pose.apk` | 4 个 ELF32 姿态库 + `extractNativeLibs=true` + `HvrPoseLibPath`，修 `helmetPoseStart()` 无 native 实现 |

2026-11-05 真机 45 秒冷启动验收：`load libMobileSensor/WalleAlg/WalleDev/vrpose success`
各 1、`helmetPoseStart` 执行、`getHelmetPose` 四元数回传 launcherx 的
`VrControllerManager`（`SetHelmetPoseOffset`）、`RenderModule Init VR end` /
`mRenderInited 1`；`UnsatisfiedLinkError` / `FATAL` / `ApplicationQuit` /
`App activity is null` / `VRPromptActivity` 全 0；栈顶保持
`com.huawei.vrlauncherx/.MainActivity`。

**剩余阻塞（未解，手册第 8 章）**：约 2 分钟后 `ArvrRuntimeService` ANR →
launcherx 重启 → vrservice 弹 `ServiceTermActivity` 因 compat 桩类缺
`WindowManagerEx$LayoutParamsEx.setDisplaySideMode(I)V` 崩溃 → `onDestroy` 空指针
→ 级联回落；另有 `VRRenderWalleImpl: No valid frame to display`（窗口 surface 未就绪）。
`E VrHelmetExt: mVRDeviceExt is null` 经 PATCH_AUDIT 16.3 定性为 3DoF 分支噪声，不阻塞。

### 4. appops `SYSTEM_ALERT_WINDOW` 对 4 个包不生效

`vrhandle`/`vrservice`/`hvrsdkserverapp` 的 manifest 没声明该权限，
`appops set` 读回仍是 default。要修需给这 3 个包的 manifest 补
`<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW"/>`
再用 `tools/apk-repack-sign.py` 重签重装（权限逐包表见仓库
`patches/patch_platform_permissions.py`）。

### 5. 装包报 `No signature found in package of version 2 or newer`

某个包 targetSdk≥30 又只有 v1 签名。本包内 APK 已全部处理好；
若以后自己重签，v1 手搓的用 `tools/apk-repack-sign.py --target-sdk 29`，
或用 JDK + apksigner（v1+v2）。

### 6. 启动闪退排查顺序

```sh
adb logcat -d | grep -E "FATAL|AndroidRuntime|NoClassDefFoundError|InflateException"
```

- `SystemPropertiesEx` 找不到 → 装的不是本包 `apks/` 里的版本（兼容层没打进去）
- `0x020100c8` / `androidhwext` → 同上，要用打过 hwext 补丁的重建版

### 7. 改了 EDID 但内核时序不变（CRTC 咬死旧模式）

实测：`edid_override` 写入 + 重启 SurfaceFlinger + 甚至重启 composer HAL
（`kill $(pidof android.hardware.graphics.composer@2.1-service)`，init 会自动拉起）
**都不改 VOP 输出**。composer 把 HDMI 当固定 1080p 内置屏，supportedModes 写死。
唯一可靠路径：**重启整机**，让模块 post-fs-data 在显示栈起来之前注入。
验证时序别信 `dumpsys display`，直接看内核：

```sh
adb shell su -c 'head -8 /sys/kernel/debug/dri/0/summary'   # 看 p60/p70/p90
adb shell su -c 'cat /sys/kernel/debug/dri/0/summary' | grep -A3 VOP   # 看 src/dst 几何
```

## 五、以后怎么改 APK 重出包

本机已有 JDK：`D:\Tools\AndroidSDK\jdk17tmp\jdk`（Temurin 17）。
PowerShell 里三个环境变量缺一不可：

```powershell
$env:HVR_ANDROID_SDK = 'D:\Tools\AndroidSDK'
$env:JAVA_HOME       = 'D:\Tools\AndroidSDK\jdk17tmp\jdk'
$env:PATH = 'C:\Users\555\.workbuddy\binaries\python\versions\3.13.12;' + $env:PATH
& .\build\build-module.ps1 -Package com.huawei.vrvirtualscreen -Revision r1
```

产物在 `build/out/`，覆盖回 `fix/apks/` 即可。无 JDK 时用
`python tools/apk-repack-sign.py 原包.apk 输出.apk [--target-sdk 29]`（密钥 `signing/`，口令 `android`）。

## 六、本轮分析文档索引（仓库 `docs/`）

| 文档 | 内容 |
|---|---|
| `分析汇总.md`（本包内）/ `docs/2026-11-02-全栈分析汇总.md` | **本轮全部分析的汇总** |
| `RK3566-部署记录.md` | 七包部署全过程、三处硬卡点 |
| `JDK解锁与七包重建-2026-11-02.md` | 构建链四坑、逐包处置 |
| `VR应用测试-2026-11-02.md` | 七包实机测试逐个结论 |
| `HDMI黑屏-EDID修复.md` | 黑屏根因与修复 |
| `线缆固件OTA-能不能骗.md` | OTA 协议逆向，结论：服务端已空 |
| `6DoF-为什么没起来.md` | 6DoF 软件栈分析 |
