# 前缓冲直出可行性评估（保帧去卡顿的路能不能走）

> 背景：用户要求**帧数不降**，问能否去掉 `swapBuffer 5~13ms` 的合成开销、让渲染更流畅。
> 卡顿根因见 `VR桌面-2026-11-26-稳定性与手柄修复.md` §5/§7。
> 本文只做可行性评估，**不改 Runtime dex、不改代码**。结论先行：**原厂前缓冲直出在高通上走不通，
> 但有折中路径。**

## 一、结论

| 路径 | 能否去掉合成拷贝 | 可行性 |
|---|---|---|
| 原厂 `startFrontBufferDisplay()` 前缓冲直出 | 能（硬件直接扫前缓冲，零合成） | ❌ **高通上不可行**：依赖麒麟 EGL 扩展，Adreno 没有 |
| 虚拟屏 + Presentation（当前方案） | 不能（每帧一次 SurfaceFlinger 合成） | ✅ 已落地，是高通上唯一可行的路 |
| 降低合成分辨率（保帧） | 部分（每帧搬的像素少了） | ✅ 见 `ALVR-保帧配置清单.md`，配置层可解 |

**一句话**：高通 7T 上"去掉合成拷贝"这条路被 GPU 驱动层封死，不是软件补丁能补的；
能做的只剩"减少每帧要合成的像素"，这已经落在配置清单里。

## 二、为什么原厂前缓冲直出走不通

### 2.1 语义：它不是"把画面交给服务"，是硬件扫前缓冲

`PATCH_AUDIT.md` §11.1 已查清：`android.vr.IVrServiceManager`（服务名 `vr_display`）
的全部方法是：

```
startFrontBufferDisplay()Z   stopFrontBufferDisplay()Z   getVsync()D
setSchedFifo(II)I            setVrPlatPerf(II)I          releaseVrPlatPerf()I
getAvailableFreqLevels()[I
```

**没有任何传纹理/Surface 的方法**。EMUI 的机制是"框架打开前缓冲显示模式后，由显示硬件
把应用的前缓冲扫出到眼镜面板"——所以手机屏在 VR 模式下是暗的（应用只有一个前缓冲，
被路由到眼镜）。这天然没有 SurfaceFlinger 合成那一步，所以在麒麟上流畅。

### 2.2 硬性障碍：依赖麒麟 EGL 扩展，Adreno 不存在

真机日志实证（`PATCH_AUDIT.md` §3398）：

```
E/ScreenControl: Not found eglDirectModeHISI
E/ScreenControl: eglGetProcAddress ShareBufferMode fail
E/ScreenControl: stopFrontBufferDisplay return failure
```

`eglDirectModeHISI` / `ShareBufferMode` 是**华为在麒麟 GPU 驱动里私有的 EGL 扩展**，
Adreno 640 驱动里没有这两个扩展。也就是说：

- 即使把 `vr_display` 服务的 7 个方法全部补齐（动态代理返回 true），
  native 层调用 `eglGetProcAddress("eglDirectModeHISI")` 仍然拿到 null，
  前缓冲扫出根本不会发生；
- `getVsync()` 我们已有等价物 `VrVsyncCompat`（用真实刷新率推算帧边界），
  但 vsync 只是时序，**解决不了前缓冲扫出这个硬件动作**；
- 这不是 Android framework 层能绕过的——扩展在 GPU 用户态驱动（libEGL）里，
  除非高通驱动实现了等价的 direct-mode 扩展（目前日志证明没有）。

### 2.3 项目既定决策：只保接口

`PATCH_AUDIT.md` §5.7 / §39.8 早已明确：`vr_display` 按既定决策**只留接口、返回不可用**，
调用方走失败分支。当前画面靠"虚拟屏 + Presentation"兜底（`HvrVrVirtualDisplay` 建
3200×1600 虚拟屏，`HvrVrPanelPresenter` 在眼镜 display 上起 Presentation 铺满 SurfaceView），
这条链就是原版"异步前缓冲 + 面板合成"的**等价物**——但它有合成拷贝，所以卡。

## 三、折中路径：在不降帧前提下减合成开销

既然"零合成"不可行，退而求其次减少每帧合成量：

1. **降虚拟屏分辨率**（`HvrVrVirtualDisplay.WIDTH/HEIGHT` 从 3200×1600 下调）：
   - 帧数不变，SurfaceFlinger 每帧合成的像素减少，`swapBuffer` 时间下降；
   - 代价：画面上采样、稍软；**但不降帧、不改 SBS 左右结构**（Presentation 仍铺满面板）。
   - 注意：这与"物理面板 3200×1600 不能动"不矛盾——动的是虚拟屏渲染分辨率，
     面板输出尺寸不变。
2. **关掉手机屏镜像**（官方是异步、手机不镜像，§39.8 未做）：
   - 当前手机屏也在镜像双目画面，等于多合成一路；若能只送眼镜不送手机，省一份开销；
   - 但眼镜 display `canHostTasks=false`、又抓不到屏（§11.3），改动风险高，需真机逐项验。
3. **ALVR 串流线**：见 `ALVR-保帧配置清单.md`，把编码分辨率降到 896×896、码率 8~10Mbps，
   硬解用 qcom——这条线不走华为 VR 桌面的合成链，是独立的优化空间。

## 四、不建议做的

- **GPU 锁频/超频**：项目无先例；瓶颈在合成带宽不在 GPU 核心频率，锁频未必改善
  `swapBuffer`，发热触发温控降频反而更卡。7T 只有一颗 Adreno 640，无"多 GPU"可调度。
- **强行补 `vr_display` 代理返回 true**：会骗过调用方以为前缓冲已开，但 native 仍拿不到
  麒麟 EGL 扩展，结果是黑屏或退回原路径，白费功夫还可能引入新 bug。

## 五、如果要真正根治

只有两条路，都不在当前软件补丁能力内：

1. **换麒麟设备**（有 `eglDirectModeHISI` 扩展）——原厂前缓冲直出天然可用，这是华为 VR Glass
   的原生平台；
2. **高通 EGL direct-mode 等价实现**——需要高通驱动提供类似扩展，或自己用 GPU 直接扫描
   前缓冲到 DP 面板（涉及内核 DRM/KMS + 用户态 EGL，属全新驱动开发，工作量远超本项目范围）。

## 六、交付边界

- 本文是**评估结论**，未改任何代码；配置层能做的已收进 `ALVR-保帧配置清单.md`。
- 若你选择试"降虚拟屏分辨率"（折中路径 1），我可以改 `HvrVrVirtualDisplay` 的常量并重编
  compat dex——但要先明确：这是**降画质保帧**，不是提性能上限，且需真机戴镜确认画面
  仍正确、帧数确实没降。
- 沙箱抓不到眼镜那路屏，所有视觉/帧数结论必须真机验收。
