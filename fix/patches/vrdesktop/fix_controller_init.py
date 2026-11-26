#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
打通 hvrframework 控制器子系统初始化（2026-11-26）。

背景：这套新内核线上，VR 会话走 native 渲染路径，Java 层
VrFramework.init() 被调用（设置 mAppActivity）但原厂依赖的组件被裁剪，
从未调用 VrControllerManager.init() → mUsingCtrl/mVRDeviceExt 一直 null →
手柄即使 BLE 连上 (vrhandle connected to gatt server) 也无法控制 VR 桌面。

三处补丁（基于含 Is6DoFApp 修复 + 日志降级的 dex，md5 357eb221）：
1. VrFramework.init(): 在 mAppActivity 设置后调用
   VrControllerManager.getInstanceObject().init(activity)，带 try/catch。
2. VrControllerManager.init(): 强制走 mThirdPartyCtrl.init(activity)
   （原逻辑被 isHW6DoFCtrlAvailable/isNoloAvailable 标志跳过，CF20 是 3DoF BLE
   手柄需走这条）。
3. VrControllerManager.chooseController(): UsingCtrl 配置为空时（本环境常态，
   onVrModeChanged 每次清空），默认选 mThirdPartyCtrl，使 mUsingCtrl 非 null。

实测效果：Thirdparty controller init succeed + mUsingCtrl set to ThirdPartyCtrl，
当前进程 "mUsingCtrl == null" 从 ~6960 降到 0，ART abort=0，桌面 stacks=1 稳定。
仍未通：ThirdPartyController.getValidIndices() 依赖 VrInteractiveDeviceManager
通过华为 AIDL 服务上报的 DeviceInfo 列表；CF20 直连 vrhandle BLE，未进该 AIDL 链，
Unity 侧仍报 "Right controller is not available"。属下一阶段（设备注册链）。

用法：
  python3 fix_controller_init.py <hvrframework.dex> <out.dex>
  需 tools/baksmali-fat.jar、tools/smali-fat.jar。基于 357eb221 基线操作。
"""
import os
import sys
import tempfile
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BAKSMALI = ROOT / "tools" / "baksmali-fat.jar"
SMALI = ROOT / "tools" / "smali-fat.jar"
JAVA = os.environ.get("JAVA", "D:/Tools/AndroidSDK/jdk17tmp/jdk/bin/java.exe")


def patch(path, anchor, inject, name):
    p = path
    s = p.read_text()
    n = s.count(anchor)
    assert n == 1, f"{name}: anchor count={n}"
    p.write_bytes(s.replace(anchor, inject).replace("\r\n", "\n").encode("utf-8"))
    print(f"  patched {name}")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    src = Path(sys.argv[1]).resolve()
    out = Path(sys.argv[2]).resolve()
    work = Path(tempfile.mkdtemp(prefix="ctrlinit_"))
    sm = work / "smali"

    print(f"[1/2] baksmali {src}")
    subprocess.run([JAVA, "-jar", str(BAKSMALI), "d", str(src), "-o", str(sm)], check=True)

    print("[2/2] apply 3 patches")
    # 1) VrFramework.init -> call VrControllerManager.init(activity)
    vf = sm / "com/huawei/vrframework/VrFramework.smali"
    a1 = """    sput-object p0, Lcom/huawei/vrframework/VrFramework;->mAppActivity:Landroid/app/Activity;

    .line 154
    sget-object v2, Lcom/huawei/vrframework/VrFramework;->differentDisplay:Lcom/huawei/vrframework/VrFramework$DifferentDisplay;
"""
    i1 = """    sput-object p0, Lcom/huawei/vrframework/VrFramework;->mAppActivity:Landroid/app/Activity;

    # init controller subsystem (stripped component in this ROM)
    :try_start_ctrlinit
    invoke-static {}, Lcom/huawei/vrframework/VrControllerManager;->getInstanceObject()Ljava/lang/Object;
    move-result-object v1
    check-cast v1, Lcom/huawei/vrframework/VrControllerManager;
    invoke-virtual {v1, p0}, Lcom/huawei/vrframework/VrControllerManager;->init(Landroid/app/Activity;)V
    const-string v1, "CTRLINIT_OK"
    const-string v2, "VrControllerManager.init called"
    invoke-static {v1, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    :try_end_ctrlinit
    .catch Ljava/lang/Throwable; {:try_start_ctrlinit .. :try_end_ctrlinit} :catch_ctrlinit
    goto :continue_init
    :catch_ctrlinit
    move-exception v1
    const-string v1, "CTRLINIT_ERR"
    invoke-static {v1, v1}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;)I
    :continue_init

    .line 154
    sget-object v2, Lcom/huawei/vrframework/VrFramework;->differentDisplay:Lcom/huawei/vrframework/VrFramework$DifferentDisplay;
"""
    patch(vf, a1, i1, "VrFramework.init -> controller init")

    # 2) force ThirdPartyCtrl init
    vcm = sm / "com/huawei/vrframework/VrControllerManager.smali"
    a2 = """    .line 110
    :cond_5b
    invoke-static {}, Lcom/huawei/vrframework/VrFramework;->isHW6DoFCtrlAvailable()Z

    move-result v1

    if-nez v1, :cond_67

    invoke-static {}, Lcom/huawei/vrframework/VrFramework;->isNoloAvailable()Z

    move-result v1

    if-eqz v1, :cond_7a

    :cond_67
"""
    i2 = """    .line 110
    :cond_5b
    # force ThirdPartyCtrl init (CF20 is 3DoF BLE)
    goto :cond_67
    :cond_67
"""
    patch(vcm, a2, i2, "force ThirdPartyCtrl init")

    # 3) chooseController default -> ThirdPartyCtrl
    a3 = """    .line 453
    :cond_1f
    const-string v0, "VrControllerManager"

    const-string v1, "Using ctrl has not set"

    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    goto :goto_c
"""
    i3 = """    .line 453
    :cond_1f
    const-string v0, "VrControllerManager"

    const-string v1, "Using ctrl has not set - default to ThirdPartyCtrl"

    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    sget-object v0, Lcom/huawei/vrframework/VrControllerManager;->mThirdPartyCtrl:Lcom/huawei/vrframework/IVrController;
    if-eqz v0, :goto_c
    iput-object v0, p0, Lcom/huawei/vrframework/VrControllerManager;->mUsingCtrl:Lcom/huawei/vrframework/IVrController;
    const/4 v1, 0x1
    iput-boolean v1, p0, Lcom/huawei/vrframework/VrControllerManager;->mUsingCtrlLocked:Z
    const-string v0, "CTRLINIT_OK"
    const-string v1, "mUsingCtrl set to ThirdPartyCtrl"
    invoke-static {v0, v1}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    goto :goto_c
"""
    patch(vcm, a3, i3, "chooseController -> ThirdPartyCtrl")

    subprocess.run([JAVA, "-jar", str(SMALI), "a", str(sm), "-o", str(out)], check=True)
    print(f"[OK] {out}")


if __name__ == "__main__":
    main()
