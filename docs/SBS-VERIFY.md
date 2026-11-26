# SBS 双目显示验收手册（怎样算成功、怎么验、失败怎么定位）

> 本文把散落在 `SBS-PANEL-FIX.md`、`SBS-PANEL-FIX-BUILD.md`、`PATCH_AUDIT.md` §5.7/§11、
> `2026-11-08-眼镜黑屏排查分析.md` §13.7、`CAST_NOROOT.md` 里的判据收拢成一条操作链。
> **先分清两条路线，别把它们的目标混在一起**——这是过去多轮排障最大的混乱源。

> 状态边界：代码层能修的（面板按面积选大屏、强制横屏、点亮兜底、免root镜像投屏）
> 都已落地并通过离线验证；**最终的"眼镜里看起来对不对"必须真机肉眼验收**，
> 沙箱/截图都给不出结论（`PATCH_AUDIT.md` §11.3：这台机器抓不了眼镜那路屏，抓到的是纯黑）。

---

## 0. 先分清两条路线

| | 路线 A：三段式 SBS（官方渲染链） | 路线 B：免 root 镜像投屏 |
|---|---|---|
| 画面来源 | Unity VR 空间 / 视频，渲染进 `HUAWEI VR Display` 虚拟屏 | `MediaProjection` 抓手机默认屏 |
| 送到眼镜 | ① Presentation + SurfaceView → ② 3200×1600 虚拟屏 → ③ `setLaunchDisplayId` 路由应用 | 一条 VirtualDisplay → 左右各一个 SurfaceView |
| 依赖 | panel-bootstrap 预建屏 + 面积选大屏 + 强制横屏 | 仅 `SYSTEM_ALERT_WINDOW` appop；用户点一次录屏授权 |
| 相关修复 | `SBS-PANEL-FIX.md`（本次面板按面积）+ `SBS-PANEL-FIX-BUILD.md`（换芯） | `CAST_NOROOT.md` |
| 现状 | ⚠️ 三段式已通到"应用落在非默认屏"，但**官方前缓冲直出仍缺**（§5.7），画面几何待真机确认 | ✅ 公开 API 可用；2026-11-26 起单路 GL 双画，左右眼一致（旧 Android 13+ 单眼坑已修，见 `CAST_NOROOT.md`） |

> 你前面问的"投屏从中间被拆成两份"是 **SBS 帧的结构**（左右眼并排），
> 两条路线送到眼镜的那一刻都要满足这个结构，区别只在于画面从哪来。
>
> **单眼规格 = 1600×1600（1:1 正方形）**：眼镜物理面板 3200×1600（2:1 横屏），
> 左右各占一半。因此承载 SBS 的 overlay 面板必须设成横屏 **3200×1600**，
> `HvrMirrorService` 的 `eyeWidth=panelWidth/2, eyeHeight=panelHeight` 才会得到
> 每眼 1600×1600，合成到物理面板时 1:1 对齐。旧口径"每眼 960×540 / 608×1080"
> 是把 1920×1080 的 overlay 副屏误当成物理面板尺寸，已废弃（2026-11-26）。

---

## 1. 前置条件（缺一不可，先逐条打勾）

| # | 条件 | 检查命令 | 通过标准 |
|---|---|---|---|
| 1 | APK 含 2026-11-26 面积选大屏修复 | `grep -a 'largestDisplay' <设备上vrservice.apk抽出classes4.dex>` 或看换芯脚本日志 | 出现 `largestDisplay` / `isCandidatePanel` |
| 2 | overlay 设为 dual 档 | `sh fix/tools/vr-overlay-mode.sh <serial> show` | `overlay_display_devices = 100x100/320;3200x1600/320`，提示"按面积选 3200x1600 → 每眼 1600×1600" |
| 3 | 眼镜在位 | 同上 show | `hidraw>=1`、`/dev/hwvr1=yes`；`hwvr1-check.sh` probe 应答正常 |
| 4 | 强制横屏已开 | `settings get system accelerometer_rotation` / `user_rotation` | `0` / `1`（ROTATION_90），见 `PATCH_AUDIT.md` §11.5 |
| 5 | 三段式第②环建屏 | `sh fix/tools/vr-display-check.sh <serial>` 看 A 段 | `HUAWEI VR Display = 1 块`，3200×1600，owner=vrlauncherx |

> 第 1 条是最容易漏的：**没换芯就切 dual 档，面板选择还是旧的"第一块小屏"，必然空壳**。

---

## 2. 换芯 + 切档（一次性落地）

```bash
# 1) 拉设备当前已打补丁的 APK，定位 compat dex，替换成修复版，重签回装
sh tools/apply-sbs-panel-fix.sh <serial> com.huawei.vrservice
sh tools/apply-sbs-panel-fix.sh <serial> com.huawei.vrvirtualscreen
#    （若 vrservice 之外的包也含 VrPlatformCompat，按脚本提示逐个处理）
#    注：com.huawei.vrvirtualscreen 换芯后连带拿到 HvrMirrorGlView —— 投屏改为
#        单路 GL 双画、横屏 SBS、左右眼同一帧（解决双目内容不一致/单眼黑屏）。

# 2) 切 dual 档：小屏满足在位判据，大屏承载 SBS
sh fix/tools/vr-overlay-mode.sh <serial> dual

# 3) 改 overlay 会 dismiss 当前 Presentation，必须重启 VR 桌面让它重建屏
adb -s <serial> shell am force-stop com.huawei.vrlauncherx
# 重新插眼镜 / 打开 VR 桌面触发 panel-bootstrap
```

---

## 3. 怎样算成功（三级判据，逐级收紧）

### 3.1 逻辑链通过（脚本可判，不需要眼睛）

跑只读体检：

```bash
sh fix/tools/hwvr1-check.sh <serial>       # 眼镜在位 + /dev/hwvr1 指向正确
sh fix/tools/vr-overlay-mode.sh <serial> show
sh fix/tools/vr-display-check.sh <serial>  # A~G 七段
```

全过标准：

- `hwvr1-check.sh`：`ioGetVendorInfo` 失败 0 次，`vendorId=4817(0x12D1)`；
- `vr-display-check.sh` A 段：`HUAWEI VR Display = 1`（3200×1600）；
- C 段：按面积选 3200×1600、每眼 1600×1600（1:1 正方形，与物理面板对齐）；
- G 段：`HvrVrPanel: panel presentation shown on display N` 且 N = 3200×1600 那块的 id。

> G 段第 4 条是**本次修复的直接验收点**：Presentation 必须挂在大屏 id，不是 100×100 小屏。

### 3.2 几何正确（dumpsys 可判，半客观）

```bash
adb -s <serial> shell dumpsys SurfaceFlinger | grep -A3 'HUAWEIAV03\|HUAWEI VR Display'
```

看 VR 空间那层的 bounds：横屏应为 `3200x1600` 量级、`scale` 接近 1（每眼 1600×1600），
不再是竖屏 `1600x3200` + 139px 偏移（`PATCH_AUDIT.md` §11.4/§11.5）。

> 当前高通适配把手机逻辑尺寸设成 `1600x3200`，转横屏即 3200x1600，与物理面板
> 1:1，`scale = 1`。旧记录里的 `2640×1.2121≈3200` 是 1080x2640 手机逻辑尺寸时代
> 把画面放大铺满面板宽的写法，现已不适用（`PATCH_AUDIT.md` §11.4）。

### 3.3 视觉成功（**必须真机肉眼**）

| 判据 | 通过标准 |
|---|---|
| 眼镜里有画面 | 肉眼看到（不能用 `screencap`，见 §0 边界） |
| SBS 结构 | 左右两幅、中间有缝、内容相同但有水平视差 |
| 不叠影 | 左眼画面不叠到右眼；若叠影回来，说明 dual 大屏当面板的取舍触发，回看 §4 |
| 方向比例 | 横屏、无拉伸；画面占满面板 |
| 3D 融合 | 戴上眼镜用透镜融合成立体；或用支持 SBS 的播放器验证 |

**只有 3.1 + 3.2 + 3.3 全过，才算"成功"。**

---

## 4. 失败怎么定位（症状 → 根因 → 去处）

| 症状 | 最可能原因 | 查哪里 |
|---|---|---|
| 切 dual 后仍是 100×100 小屏/空壳 | APK 没换芯（第 1 条没满足） | 回 §1.1、`SBS-PANEL-FIX-BUILD.md` |
| 每眼画幅是 16:9 而非 1600×1600 正方形 | overlay 大屏档用了旧值 1920x1080 | 用 `vr-overlay-mode.sh panel|dual` 切到 3200x1600 |
| `panel presentation shown on display` 挂到小屏 id | 面板选择补丁没进这份 dex | `SBS-PANEL-FIX.md` §2，重编 dex |
| G 段 `HvrVrPanel = 0`，① 从未挂 | panel-bootstrap 没预建屏 | `fix/README.md` §11.13 |
| 应用仍落在 `displayId=0` 主屏 | `setLaunchDisplayId` 路由失败 / 跨进程被拦 | `vr-display-check.sh` D 段、`fix/README.md` §11.12 |
| 几何是竖屏 1600×3200 + 偏移 | 强制横屏没写生效 / `wm size` 没覆盖 | `vr-desktop-view-check.sh` A/B 段、`PATCH_AUDIT.md` §11.5 |
| **VR 桌面**能进但画面变形/半幅/拉伸（本脚本查"应用没显示"，那个查"桌面自己画面不对"） | 面板 mode 非 3200×1600 / 方向或逻辑尺寸没锁 | `fix/tools/vr-desktop-view-check.sh`，专题见 `docs/VR-DESKTOP-VIEW-FIX.md` |
| 眼镜有画面但两眼内容叠在一起 | overlay 用了 `panel` 档（主屏递归叠影） | `vr-overlay-mode.sh small` 或 `dual` |
| 镜像投屏只有一只眼 / 左右眼内容不一致 | 一个 MediaProjection 建多 VirtualDisplay 受限（旧两路写法） | 已改单路 GL 双画：`HvrMirrorGlView`，经 `apply-sbs-panel-fix.sh` 换芯；见 `CAST_NOROOT.md` |
| 眼镜黑屏且 `ioGetVendorInfo failed` | `/dev/hwvr1` 指错 hidraw 口 | `hwvr1-check.sh`、`2026-11-08...` §7 |
| 面板亮但无画面、Unity 自杀 | SurfaceView 缓冲被 light-on 打废 | `2026-11-08...` 层 1，force-stop 重启 launcherx |

---

## 5. 仍未完成（不是这次能靠改仓库修好的）

1. **`vr_display` 前缓冲直出**（`IVrServiceManager.startFrontBufferDisplay`）：官方渲染结果送 DP 面板那条路仍返回不可用，见 `PATCH_AUDIT.md` §5.7。路线 A 目前靠镜像/Presentation 兜底，不是原厂那条路。
2. **90Hz 重投影**：未完成，见 `SM8150-ADAPTATION.md` §5。
3. **CF20 长期配对稳定**：手柄每约 11 秒掉线，靠常驻重连兜底，见 `PATCH_AUDIT.md` §9.5。
4. **最终视觉验收**：必须真机肉眼，见 §3.3。

> 如果你想彻底绕开这套华为栈，作者的建议是走
> [`LHT02/HuaweiVRGlass-ALVR`](https://github.com/LHT02/HuaweiVRGlass-ALVR)（已完成双目输出 + 6DoF），
> 见 `ALVR_HUAWEI_VR_GLASS.md`。那与本文的"修华为栈"是两条路，别混。
