# VR 桌面能进、但画面不对：几何链诊断与修复（面向移植）

> 2026-11-26　目标：把「3D 桌面能启动，但眼镜里画面变形/竖屏/半幅/偏移」定位到具体一环。
> 本文只讲**桌面自己的画面几何**，不讲「桌面里打开的应用为何不显示」（那是 `vr-display-check.sh`）。

---

## 0. 先分清：桌面画面与应用投屏是两条路

| | VR 桌面自己的画面 | 桌面里 2D 应用投屏 |
|---|---|---|
| 承载 | `HvrVrVirtualDisplay` 建 3200×1600 `HUAWEI VR Display` 虚拟屏 → `HvrVrPanelPresenter` 用 Presentation 铺到眼镜屏 | `HvrMirrorService` 按 overlay 副屏尺寸建每眼画幅 |
| 体检 | **`vr-desktop-view-check.sh`**（本文） | `vr-display-check.sh` / `vr-overlay-mode.sh` |
| 关键尺寸 | 手机逻辑尺寸 3200×1600、面板 mode 3200×1600 | overlay 承载面板 3200×1600 → 每眼 1600×1600 |

两条路都指向同一个 3200×1600，但一个是「桌面整体」，一个是「桌面里某一应用」。
用户说"桌面能进、画面不对"时，先跑对脚本，别用投屏的口径去套桌面。

---

## 1. 画面几何五环（自上而下，任一环错画面就错）

```
[A] 手机方向      user_rotation=1 + accelerometer_rotation=0
       ↓          缺 → 桌面竖屏塞进横屏面板 → 半幅/左右眼错位
[B] 手机逻辑尺寸  wm size 1600x3200（转横屏 = 3200x1600）
       ↓          缺 → 物理 1080x2640 转横 2640x1080，缩放 1.2121 + 139px 偏移 [1]
[C] 面板物理 mode 眼镜外接屏 = 3200x1600@70/90
       ↓          错（1920x1080p60）→ 虚拟屏 3200x1600 被面板拉伸变形 [2]
[D] 虚拟屏        HUAWEI VR Display 已建、3200x1600
       ↓          缺 → 桌面画在默认屏，几何不受控
[E] 层几何        桌面层 bounds=3200x1600、layerstack 对齐眼镜那路
```

[1] `PATCH_AUDIT.md` §11.4：历史真机层 `bounds={139,0,1600,3200}`，即 1600×3200 竖屏 + 139px 偏移。
[2] RK3566 `docs/RK3566-部署记录.md` §7.2 / `2026-11-02-全栈分析汇总.md`：VOP 把 1920×1080
拉伸铺满 3200×1600 双目面板，每眼只看到半幅。高通与 RK 是**同一类几何错误，不同层**。

---

## 2. 一键体检（只读，不改设备状态）

```sh
sh fix/tools/vr-desktop-view-check.sh <serial>
# 或 ADB=/path/to/adb sh fix/tools/vr-desktop-view-check.sh <serial>
```

脚本 A–E 段逐环 dump，结尾按可能性给出 `[1]…[4]` 定位。判据见下表。

| 环 | 命令 | 期望 | 失败含义 |
|---|---|---|---|
| A | `settings get system user_rotation` / `accelerometer_rotation` / `wm get-ignore-orientation-request` | `1` / `0` / `false` | 竖屏或方向被锁死 → 半幅 |
| B | `wm size` | `Override size: 1600x3200` | 无覆盖 = 2640×1080 + 139px 偏移 |
| C | `dumpsys display` 里眼镜外接屏 | `3200 x 1600` | 1920×1080 → 面板端拉伸 |
| D | `dumpsys display \| grep HUAWEI VR Display` | 1 块 3200×1600 | 缺 → 走 `vr-display-check.sh` |
| E | `dumpsys SurfaceFlinger` 桌面层 | `bounds` 3200×1600、无 `tx≈139` | 竖屏/偏移 → 回 A/B |

---

## 3. 修复分工（哪些 compat 层能修，哪些不能）

| 环 | 谁负责 | 当前状态 |
|---|---|---|
| A 方向 | `VrSystemActor.lockLandscape()`（`USER_ROTATION=90`）；KSU `service.sh` 也写一份 | 已实现；还原见 `PATCH_AUDIT.md` §63（未真机复验） |
| B 逻辑尺寸 | **KSU 模块** `ksu/hwvr_compat/service.sh` 的 `set_size()`：`wm size 1600x3200` | 已实现；需 root/KSU，app 层做不了 |
| C 面板 mode | **内核 / HWC / hotdogb-boot 的 DP 4-lane 补丁** | compat 层改不了；这是 7T 移植要先排除的一环 |
| D 虚拟屏 | `HvrVrVirtualDisplay.ensure()`：固定 3200×1600 @320dpi | 已实现 |
| E 层几何 | `HvrDisplayRouter.applyVrDisplay()`：`setLaunchDisplayId` | 已实现；跨进程被拦才需 LSPosed |

**关键结论**：A/B/D/E 都已在 compat 层落地。若体检显示这四环都对、只有 C 错
（面板仍是 1920×1080p60），那不是改 APK 能解决的——要回到 `hotdogb-boot` 的
DP 补丁与内核/HWC mode，确认 4-lane 协商和 3200×1600 时序。

---

## 4. 移植到新设备的最小清单

1. 刷带 HID/DP 补丁的内核（7T 用 `hotdogb-boot`），确认 `dumpsys display` 能枚举到眼镜外接屏。
2. 装 KSU 模块 `build/hwvr_compat.zip`（造 `/dev/hwvr1` + `wm size 1600×3200` + 横屏）。
3. 装换芯后的四个包（统一签名 + compat dex），补授权（`grant-vr-perms.sh`）。
4. 眼镜在位启动桌面，跑 `vr-desktop-view-check.sh`：A/B/D/E 须全 OK。
5. C 若不为 3200×1600 → 停在硬件层排查，不要继续改 APK。
6. 最后才是真机肉眼 3.3 判据（SBS 结构 / 不叠影 / 融合）。

> 一张表记住分工：**"进不进得去"看触发链（`hwvr1-check.sh`），
> "画面几何对不对"看本脚本，"桌面上应用出不出现"看 `vr-display-check.sh`。**

---

## 5. 参考

- `docs/PATCH_AUDIT.md` §11.4/§11.5（几何与强制横屏）、§63（方向还原）
- `docs/RK3566-部署记录.md` §7.2、`docs/2026-11-02-全栈分析汇总.md`（面板拉伸根因）
- `docs/SBS-VERIFY.md`（三级判据）、`docs/evidence/README.md`（EDID 与真实时序）
- `fix/tools/hwvr1-check.sh`（两道软件闸）、`fix/tools/vr-display-check.sh`（应用承载链）
