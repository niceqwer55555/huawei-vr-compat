#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
降低 hvrframework.dex 里两条高频控制器日志的级别（Error/Info -> Verbose）。

背景（2026-11-26）：
  桌面运行时 `VrControllerManager: mUsingCtrl == null` 每秒刷 ~140 条、
  `VrParamsRequester: Huawei 6Dof left controller is false` 每秒 ~60 条。
  这些是 null/未注册状态下的观测日志，逻辑上有兜底、无害，但 Error 级
  高频写 logcat 有 CPU 开销，且会累积系统"应用屡次停止运行"计数误判。

修复：把这两条日志的 Log.e/Log.i 改成 Log.v（Verbose 默认不输出、开销最小）。
      不改任何逻辑/寄存器，只改 invoke-static 的方法名。

重要：必须在**已打 Is6DoFApp 补丁的 dex**（md5 769bd170 / 6doffix2）基础上改，
      不要用原始 dex（3120a637），否则会丢失桌面消失崩溃修复。

用法：
  python3 fix_controller_log_flood.py <hvrframework.dex> <out.dex>
"""
import sys
import tempfile
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BAKSMALI = ROOT / "tools" / "baksmali-fat.jar"
SMALI = ROOT / "tools" / "smali-fat.jar"

PATCHES = [
    ("com/huawei/vrframework/VrControllerManager.smali",
     '    invoke-static {v1, v2}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;)I\n',
     '    invoke-static {v1, v2}, Landroid/util/Log;->v(Ljava/lang/String;Ljava/lang/String;)I\n',
     'mUsingCtrl == null'),
    ("com/huawei/vrframework/VrParamsRequester.smali",
     None, None, "Huawei 6Dof left controller is "),  # handled specially
]


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    src = Path(sys.argv[1]).resolve()
    out = Path(sys.argv[2]).resolve()
    work = Path(tempfile.mkdtemp(prefix="ctrllog_"))
    smali_dir = work / "smali"
    java = "java"

    print(f"[1/3] baksmali {src}")
    subprocess.run([java, "-jar", str(BAKSMALI), "d", str(src), "-o", str(smali_dir)], check=True)

    # patch 1: mUsingCtrl == null  Log.e -> Log.v
    f = smali_dir / PATCHES[0][0]
    s = f.read_text()
    idx = s.index(PATCHES[0][3])
    seg = s[idx:idx + 200]
    old, new = PATCHES[0][1], PATCHES[0][2]
    assert old in seg, "mUsingCtrl Log.e not found near string"
    seg2 = seg.replace(old, new, 1)
    f.write_bytes((s[:idx] + seg2 + s[idx + 200:]).replace("\r\n", "\n").encode())
    print("[2/3a] mUsingCtrl == null: e -> v")

    # patch 2: 6Dof controller log (find Log.{e,i,w} after string)
    import re
    g = smali_dir / PATCHES[1][0]
    t = g.read_text()
    idx = t.index(PATCHES[1][3])
    seg = t[idx:idx + 900]
    m = re.search(r"invoke-static \{v3, v4\}, Landroid/util/Log;->(e|i|w)\(Ljava/lang/String;Ljava/lang/String;\)I", seg)
    assert m, "6Dof log call not found"
    seg2 = seg.replace(m.group(0), m.group(0).replace("->" + m.group(1) + "(", "->v("), 1)
    g.write_bytes((t[:idx] + seg2 + t[idx + 900:]).replace("\r\n", "\n").encode())
    print(f"[2/3b] 6Dof controller log: {m.group(1)} -> v")

    print("[3/3] smali rebuild dex")
    subprocess.run([java, "-jar", str(SMALI), "a", str(smali_dir), "-o", str(out)], check=True)
    print(f"[OK] {out}")


if __name__ == "__main__":
    main()
