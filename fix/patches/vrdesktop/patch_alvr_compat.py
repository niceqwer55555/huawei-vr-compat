#!/usr/bin/env python3
"""
给原版 ALVR (com.lht.huaweivr.alvr 20.14.1) 的 app_SdkLibs/classes.dex
补齐华为 VR 框架 compat 类，让它不再 NoClassDefFoundError 崩溃。

背景（2026-11-27 真机）：
  原版 ALVR 的 classes.dex 里，VrMonitor / VrSystemClient / VrTouchService
  调用这些类但 dex 里没有定义：
    - com.huawei.vrframework.compat.VrPlatformCompat.isVrGlassPresent()Z
    - android.vrsystem.VRSystemServiceManagerImpl (+ IVRSystemService 系列)
  启动即崩：NoClassDefFoundError。自研 ALVR APK 签名不同不能覆盖装，
  所以选择给原版 dex 补类（不动 APK、不换签名、不丢数据）。

用法：
  1) 设备 pull 原 dex:
     adb shell 'su -c "cp /data/user/0/com.lht.huaweivr.alvr/app_SdkLibs/classes.dex /sdcard/a.dex"'
     adb pull /sdcard/a.dex alvr_classes.dex
  2) baksmali 反编译:
     java -jar tools/baksmali-fat.jar d alvr_classes.dex -o alvr_smali/
  3) 本脚本把补齐用的 smali 模板拷进 alvr_smali/（或手动按下面模板创建）:
     - com/huawei/vrframework/compat/VrPlatformCompat.smali  (isVrGlassPresent 恒 true)
     - android/vrsystem/VRSystemServiceManagerImpl.smali     (从 launcherx classes4)
     - android/vrsystem/IVRSystemService*.smali              (从 combo_final)
  4) smali 汇编回 dex:
     java -jar tools/smali-fat.jar assemble alvr_smali/ -o alvr_fullcompat.dex
  5) 部署 + 锁防止被覆盖:
     adb push alvr_fullcompat.dex /data/local/tmp/
     adb shell 'su -c "
       D=/data/user/0/com.lht.huaweivr.alvr/app_SdkLibs/classes.dex
       cp \$D \$D.orig; cp /data/local/tmp/alvr_fullcompat.dex \$D
       chown u0_a225:u0_a225 \$D; chmod 600 \$D
       rm -rf /data/user/0/com.lht.huaweivr.alvr/app_SdkLibs/oat
       chattr +i \$D   # 关键: ALVR 启动会从共享源重新解压覆盖 dex
     "'

另需修 native loader：ALVR native 要 dlopen("libopenxr_loader.so")，
但 APK 里只有 libxr_loader.so。在 /data/app/.../lib/arm/ 复制一份改名：
  cp libxr_loader.so libopenxr_loader.so; chown system:system; chmod 755
（软链不行，Android classloader 不认；launch_alvr.sh 已内置此步骤。）

现成产物（本轮生成）：
  build/tmp/alvr_sdk.fullcompat.dex  = 补好的 classes.dex（md5 5bc0b8f1）
  模块里副本: /data/adb/modules/vrglass-autostart-new/alvr_sdk.fullcompat.dex

回滚：
  adb shell 'su -c "
    D=/data/user/0/com.lht.huaweivr.alvr/app_SdkLibs/classes.dex
    chattr -i \$D; cp \$D.orig \$D
  "'
"""
import sys
from pathlib import Path

# 三个补齐类的模板来源（相对项目根）
SOURCES = {
    "com/huawei/vrframework/compat/VrPlatformCompat.smali": None,  # 需在 alvr_smali 里已存在(手工最小桩)
    "android/vrsystem/VRSystemServiceManagerImpl.smali": "build/tmp/arvr_analysis/smali_classes4/android/vrsystem/VRSystemServiceManagerImpl.smali",
    "android/vrsystem/IVRSystemService.smali": "build/tmp/combo_final/smali/android/vrsystem/IVRSystemService.smali",
}

VR_PLATFORM_COMPAT_STUB = """.class public Lcom/huawei/vrframework/compat/VrPlatformCompat;
.super Ljava/lang/Object;
.method public constructor <init>()V
    .registers 1
    invoke-direct {p0}, Ljava/lang/Object;-><init>()V
    return-void
.end method
.method public static isVrGlassPresent()Z
    .registers 1
    const/4 v0, 0x1
    return v0
.end method
"""


def main(smali_dir: str) -> int:
    root = Path(smali_dir)
    # 1) VrPlatformCompat 最小桩
    p = root / "com/huawei/vrframework/compat/VrPlatformCompat.smali"
    if not p.exists():
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(VR_PLATFORM_COMPAT_STUB, encoding="utf-8")
        print("created", p)
    # 2) 其余类从项目现成模板拷
    for rel, src in SOURCES.items():
        if src is None:
            continue
        dst = root / rel
        if dst.exists():
            print("exists", rel)
            continue
        sp = Path(src)
        if not sp.exists():
            print("WARN: source missing", src)
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_bytes(sp.read_bytes())
        print("copied", rel, "<-", src)
    print("OK: 再用 smali-fat.jar assemble", smali_dir, "-o <out.dex>")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("usage: patch_alvr_compat.py <alvr_smali_dir>")
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
