#!/usr/bin/env python3
"""
跳过 Unity VR 桌面的 "3dof 手柄等待/教程页"。

背景（2026-11-27 真机定位）：
  进入 VR 桌面后卡在"手柄键位图/教程页，下一步/跳过按钮点不动"。
  日志铁证：
    WalleXSceneManager: Detection of Locator Insertion Before App Startup,
        IsHw6dofAttachedByUsbTraverse : False IsEmuiSupport : False
    WalleXSceneManager: 3dof mode display ready canvas
    WalleXSceneManager: The 3dof resetting screen is displayed.
    ReadyCanvasController: iController is null or unavaliable
  根因：isHw6dofAttached 检查 USB VID=0x12d1 PID=0x3a09（6DoF 定位器），
  我们的眼镜是 12d1:107e / 12d1:109a，恒 false -> Unity 走 3dof 等待页，
  该页在等一个永远不来的控制器 -> 卡死。与 OOBE 开关(needShowOOBE /
  NEED_SHOW_6DOF_OOBE)无关，那些已设 0 仍卡。

修法（Mono IL 二进制补丁，Assembly-CSharp.dll）：
  WalleXSceneManager/'<FirstEnterLauncherCoroutine>c__Iterator3'::MoveNext
  里两处条件跳转（isAttached / isSupport 为 false 就跳到 3dof 分支 IL_01f5）：
    IL_016e: 39 82 00 00 00   (brtrue +0x82)
    IL_0179: 39 77 00 00 00   (brtrue +0x77)
  改成 pop + 4 nop（弹栈保持 IL 栈平衡，顺序落入 6DoF 主场景分支）：
    26 00 00 00 00
  最终走 FirstEnterLauncherChangeScene -> NeedShowOobe()(标志全0) -> HomeScene。
  纯托管代码改动，不触发 native SIGSEGV。

用法：
  python3 patch_unity_skip_3dof_wait.py <in.dll> <out.dll>
然后：把 out.dll 写回 APK 的 assets/bin/Data/Managed/Assembly-CSharp.dll，
zipalign + apksigner 重签（alias hvrcompat / pass android，证书 30094ded）。
"""
import sys, struct

# MoveNext 方法体在 c88bbbe9 版 Assembly-CSharp.dll 的 RVA。
# 脚本自带 PE 解析把 RVA 换成文件偏移，避免硬编码绝对偏移；并对
# 两条目标指令做字节断言，形态不符即中止（不写出半成品）。
METHOD_RVA = 0x2E0B4
HEADER_SIZE = 12  # fat header (0x13 = 3 dwords = 12 bytes)
SITES = [
    (0x16E, bytes.fromhex("39 82 00 00 00")),  # brtrue -> 3dof branch
    (0x179, bytes.fromhex("39 77 00 00 00")),
]
REPLACEMENT = bytes.fromhex("26 00 00 00 00")  # pop + nop*4


def rva_to_offset(d: bytes, rva: int) -> int:
    pe = struct.unpack_from("<I", d, 0x3C)[0]
    nsec = struct.unpack_from("<H", d, pe + 6)[0]
    optsz = struct.unpack_from("<H", d, pe + 20)[0]
    sec_off = pe + 24 + optsz
    for i in range(nsec):
        o = sec_off + i * 40
        vsz, vaddr, rsz, raddr = struct.unpack_from("<IIII", d, o + 8)
        if vaddr <= rva < vaddr + max(vsz, rsz):
            return raddr + (rva - vaddr)
    raise ValueError(f"RVA {hex(rva)} not in any section")


def main(src: str, dst: str) -> int:
    d = bytearray(open(src, "rb").read())
    code = rva_to_offset(bytes(d), METHOD_RVA) + HEADER_SIZE
    # 校验 fat header + code size
    assert d[code - HEADER_SIZE] & 0x3 == 3, "not a fat method header"
    for iloff, expected in SITES:
        off = code + iloff
        got = bytes(d[off:off + 5])
        if got != expected:
            print(f"ABORT: IL_{iloff:04x} at {hex(off)} = {got.hex()}, "
                  f"expected {expected.hex()} (already patched or wrong baseline)")
            return 1
        d[off:off + 5] = REPLACEMENT
        print(f"patched IL_{iloff:04x} @ {hex(off)}: {expected.hex()} -> {REPLACEMENT.hex()}")
    open(dst, "wb").write(d)
    print(f"OK: {dst} ({len(d)} bytes)")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print("usage: patch_unity_skip_3dof_wait.py <in.dll> <out.dll>")
        sys.exit(2)
    sys.exit(main(sys.argv[1], sys.argv[2]))
