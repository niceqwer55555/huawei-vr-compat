# ALVR 串流解码器改用高通（QCOM）硬解 —— 2026-11-28

## 结论

把 ALVR（`com.lht.huaweivr.alvr` 20.14.1）实际加载的 `alvr_sdk.fullcompat.dex`
里写死的 **HiSilicon OMX 组件名**换成**高通 QCOM 组件名**，让一加 7T / SM8150
上走高通硬件编解码，而不是依赖 `createDecoderByType` 的系统默认回退。

| 用途 | 原值（麒麟） | 新值（高通 SM8150） |
|---|---|---|
| 视频解码 AVC | `OMX.hisi.video.decoder.avc` | `OMX.qcom.video.decoder.avc` |
| 视频解码 HEVC | `OMX.hisi.video.decoder.hevc` | `OMX.qcom.video.decoder.hevc` |
| 视频编码 AVC | `OMX.hisi.video.encoder.avc` | `OMX.qcom.video.encoder.avc` |
| 视频编码 HEVC | `OMX.hisi.video.encoder.hevc` | `OMX.qcom.video.encoder.hevc` |

组件名经 [AOSP `hardware/qcom/sm8150/media`](https://android.googlesource.com/platform/hardware/qcom/sm8150/media/)
的 `OMX_REGISTRY_ENTRY("OMX.qcom.video.decoder.avc", "libOmxVdec.so", "video_decoder.avc")`
确认在 SM8150 上存在；`encoder.avc/hevc` 同样在 registry 表内。

## 改动位置（精确 4 行）

解码器/编码器选择逻辑在华为 `hvrsdkserverapp` 的类里，随 ALVR 一起被打进
`app_SdkLibs/classes.dex`（我们补类后即 `alvr_sdk.fullcompat.dex`）：

| 文件（baksmali 展开） | 行 | 方法 | 作用 |
|---|---|---|---|
| `com/huawei/hvrsdkserverapp/module/algorithm/wirelessStreaming/b.smali` | 982 | `b()` / `DecoderThread` | AVC `createByCodecName` |
| 同上 | 1162 | 同上 | HEVC `createByCodecName` |
| `com/huawei/hvrsdkserverapp/service/i.smali` | 361 | 编码线程 | AVC `createByCodecName` |
| 同上 | 421 | 同上 | HEVC `createByCodecName` |

只替换 `const-string` 的 OMX 名常量，**不动任何控制流、寄存器或调用顺序**。
严格逐文件 diff 确认：全 dex 614 个 smali 文件里只有这 2 个文件、共 4 行发生变化，
除 OMX 名外无任何其他差异。

### 为什么不改 `Utils.a()` / `checkHisiDecoderExtNative()`

`wirelessStreaming/Utils.a()` 调 native `checkHisiDecoderExtNative()` 判断硬件是否有
hisi 解码扩展（读 `hisi.vrar`）：

- 麒麟设备 → 返回 true → 走 `createByCodecName(hisi 名)`；
- 高通设备 → 返回 false → 走 `createDecoderByType(mime)` 系统默认回退。

高通设备上原本靠回退也能出图，但默认解码器不保证是最优的 qcom 硬解。改 OMX 名后，
即使 native 判断返回 true（或将来被改），`createByCodecName` 拿到的也是高通组件。
native 判断本身是硬件探测，语义正确，保留不动。

## 构建（可复现）

```bash
# 1) 展开当前 dex
java -jar tools/baksmali-fat.jar d <alvr_sdk.fullcompat.dex> -o alvr_smali/
# 2) 改上面 4 行 hisi -> qcom
# 3) 汇编回 dex
java -jar tools/smali-fat.jar assemble alvr_smali/ -o alvr_sdk.fullcompat.dex
```

产物：
- 大小 **805684 B**（与原 dex 完全一致）
- MD5 `8d318e7e7b96145978bb4181b1d2b1c7`
- SHA-256 `160df8a81ab60af80781daf93f32e2bf49c85e08fc8dcd8100918d97c7f0ddd3`

> 注：smali 汇编器对 string pool 排序有固有非确定性，同一份 smali 反复汇编字节不同，
> 但反编译出的 smali 语义一致（已用原始 dex 往返实验证明：原始 dex 重汇编 sha 也变）。
> 因此以「语义 diff + 类数 + 大小」为准，不以字节 sha 判定正确性。

## 部署与升级陷阱（重要）

模块 `17t/modules/vrglass-autostart-new/launch_alvr.sh` 负责把 dex 部署到
`/data/user/0/com.lht.huaweivr.alvr/app_SdkLibs/classes.dex` 并 `chattr +i` 锁死
（防 ALVR 启动从共享源重新解压覆盖）。

**旧版判断只看 `VrPlatformCompat` 是否存在**，而旧 hisi dex 也含这个类——若设备上
已装旧 dex，换新 dex 后启动脚本会误判"已补丁"而**永远不会升级**。已修：

- 新增标记 `/data/local/tmp/.alvr_patched_qcom`；
- 部署条件增加：无 qcom 标记 或 设备端 dex 仍含 `OMX.hisi.video.decoder` → 强制刷新；
- 部署后写入 qcom 标记，日志打 `patched ALVR classes.dex (qcom decoder)`。

刷新后需 `rm -rf $SDK/oat`（脚本已含）让 ART 重新编译，否则可能仍跑旧 oat。

## 验证状态

- [x] 4 处 OMX 名 hisi→qcom，语义 diff 无其他改动
- [x] 新 dex 可完整反编译（614 个 smali 文件，与原始一致）
- [x] 关键类保留（`VrPlatformCompat` / `VRSystemServiceManagerImpl` / native 扩展）
- [x] `createByCodecName` 调用点前已是 qcom 名
- [x] `launch_alvr.sh` 语法通过，升级触发逻辑就位
- [ ] 真机戴镜确认画面（沙箱抓不到眼镜那路屏）

## 回滚

```bash
# 模块：删除后重启
su -c 'rm -rf /data/adb/modules/vrglass-autostart-new'
# 若只想回退 dex：用上一轮 alvr_sdk.fullcompat.dex（hisi 版，git 历史 283e8b0）
```

## 相关

- `docs/ALVR_HUAWEI_VR_GLASS.md` —— ALVR 路线选型与 HiSilicon 硬解背景
- `docs/ALVR-黑屏修复-2026-11-27.md` —— 四层 panic 链修复（本 dex 补类即其一）
- `fix/patches/vrdesktop/patch_alvr_compat.py` —— 补类脚本（本 dex 的来源）
