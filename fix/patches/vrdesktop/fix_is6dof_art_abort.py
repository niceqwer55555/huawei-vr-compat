#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""修 launcherx 每帧 JNI 调 Is6DoFApp 触发 ART SIGABRT（VR 桌面约 4~38s 后消失）。

背景（2026-11-26，实测 native 栈决定性证据）：
    Thread-18 (Unity 渲染线程)
      libvrunityplugin.so HVR_TimeWarpEvent
      -> libvrsdk.so HvrApiImp::submitFrame -> RenderModule::DrawFence
      -> HvrJniClass::CallStaticBooleanMethod        (native 每帧调 Java 静态方法)
      -> com.huawei.vrframework.VrFramework$VrDisplay.Is6DoFApp (VrFramework.java:1572)
      -> VrInteractiveDeviceManager.getMetaData (line 425)
      -> ART EnsureInitialized -> VerifyClass -> VerifyISFieldAccess -> FindClass
      -> AssertNoPendingException -> Runtime::Abort -> SIGABRT
    进程死后 Force finishing MainActivity -> token died -> HUAWEI VR Display 被回收
    -> 眼镜屏(display 1) stacks=0 -> 用户看到"桌面一会就不见了"。
    注意 top-resumed-state-loss-timeout 是结果，不是根因。

修法：把 Is6DoFApp()Z 改成纯 early-return true。
    原逻辑对本 vr_only app 最终也返回 true（metaData "3dof|6dof" 匹配 ->
    is36DofApp=true -> is6DofApp=true），语义等价，但彻底绕开每帧 getMetaData
    触发的类初始化校验崩溃。

    !! 绝不可跨类 sput private static 字段（hasGotAppType/is6DofApp）：
       ART 会抛 ThrowIllegalAccessErrorField -> AssertNoPendingExceptionForNewException
       另一种 SIGABRT。只能纯返回，或用 access$2902 之类合成 setter。

用法：
    python3 fix_is6dof_art_abort.py <smali_dir>          # 就地打补丁（幂等）
    python3 fix_is6dof_art_abort.py <smali_dir> --check  # 只检查是否已打
  <smali_dir> 是 hvrframework.dex 的 baksmali 输出目录。
  打完需 smali 回编译 dex，并部署到两处：
    /data/local/tmp/orig/hvrframework.dex               (restore 源)
    /data/data/com.huawei.vrlauncherx/app_SdkLibs/hvrframework.dex (运行时)
  参考 docs/VR桌面-2026-11-26-稳定性与手柄修复.md。
"""
import sys
from pathlib import Path

TARGET_REL = 'com/huawei/vrframework/VrFramework$VrDisplay.smali'
MARK = 'fix: avoid native ART abort in Is6DoFApp'
NEEDLE = """.method public static Is6DoFApp()Z
    .registers 3

    .prologue
"""
INJECT = """.method public static Is6DoFApp()Z
    .registers 3

    .prologue
    # 2026-11-26 fix: avoid native ART abort (AssertNoPendingException) in
    # getMetaData -> class init verify, triggered every frame by submitFrame.
    # Original logic resolves to true for our vr_only app; just return true.
    # Do NOT sput private fields of VrFramework: ART throws IllegalAccessError
    const/4 v0, 0x1
    return v0
"""


def main():
    if len(sys.argv) not in (2, 3):
        raise SystemExit(__doc__)
    smali_dir = Path(sys.argv[1])
    check_only = len(sys.argv) == 3 and sys.argv[2] == '--check'
    target = smali_dir / TARGET_REL
    if not target.is_file():
        raise SystemExit(f'[FATAL] 找不到 {target}（baksmali hvrframework.dex 的输出目录？）')
    s = target.read_text(encoding='utf-8')

    if MARK in s:
        print(f'[OK] 已打补丁（幂等跳过）: {target}')
        return
    if check_only:
        raise SystemExit('[FATAL] 尚未打补丁')

    # 确认目标方法存在且唯一
    if s.count(NEEDLE) != 1:
        raise SystemExit(f'[FATAL] Is6DoFApp 开头匹配数={s.count(NEEDLE)}，应为 1')
    s = s.replace(NEEDLE, INJECT, 1)
    target.write_text(s, encoding='utf-8')
    print(f'[OK] 已注入 Is6DoFApp early-return true: {target}')


if __name__ == '__main__':
    main()
