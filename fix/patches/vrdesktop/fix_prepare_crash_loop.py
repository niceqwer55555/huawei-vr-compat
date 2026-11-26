#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
修 VR 桌面「反复启动 / 华为 VR 桌面屡次停止运行」弹窗循环。

根因（2026-11-26 定位）：
  launcherx 的 VRPrepareActivity 声明 android:process="com.huawei.vrservice"，
  它跑在 vrservice 进程里。华为适配流程 w0/b.t() -> w0/b.C() 会启动准备页，
  启动点带一个 launchDisplayId，指向 launcherx 自己反复创建/销毁的
  HUAWEI VR Display 虚拟屏（displayId 递增 26..230+）。
  虚拟屏在启动瞬间已失效 -> ActivityManager 拒 launchDisplayId ->
  SecurityException -> vrservice 子进程崩溃 -> 崩溃记录累积 ->
  系统弹「华为 VR 桌面屡次停止运行」AppErrorDialog -> 重建虚拟屏 -> 循环。

修复：给 w0/b.C()（startPrepareActivity）方法开头加 return-void，
      彻底不启动准备页。3D MainActivity 走独立路径，不受影响。

用法：
  python3 fix_prepare_crash_loop.py <launcherx.apk> <out.apk> [--baksmali DIR]

  依赖同目录的 replace_dex.py 与项目 tools/{baksmali,smali}-fat.jar。
  只改 classes.dex 一个条目，其它条目逐字节保留；签名需之后用 apksigner 重签。
"""
import os
import sys
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]  # fix/patches/vrdesktop -> repo root
BAKSMALI = ROOT / "tools" / "baksmali-fat.jar"
SMALI = ROOT / "tools" / "smali-fat.jar"
REPLACE_DEX = Path(__file__).resolve().parent / "replace_dex.py"

TARGET_CLASS = "w0/b.smali"
NEEDLE = """.method public final C(Landroid/content/Context;)V
    .registers 4

    .line 1
    sget-object v0, Lw0/b;->j:Ljava/lang/String;
"""
INJECT = """.method public final C(Landroid/content/Context;)V
    .registers 4

    # disable VRPrepareActivity launch (runs in vrservice process, started onto
    # stale virtual display ids -> SecurityException launchDisplayId -> crash loop)
    return-void

    .line 1
    sget-object v0, Lw0/b;->j:Ljava/lang/String;
"""


def java_jar(jar, args):
    java = os.environ.get("JAVA", "java")
    return subprocess.run([java, "-jar", str(jar)] + args, check=True)


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    src_apk = Path(sys.argv[1]).resolve()
    out_apk = Path(sys.argv[2]).resolve()
    work = Path(tempfile.mkdtemp(prefix="preparefix_"))
    smali_dir = work / "smali"
    new_dex = work / "classes.new.dex"

    print(f"[1/4] baksmali {src_apk}")
    java_jar(BAKSMALI, ["d", str(src_apk), "-o", str(smali_dir)])

    print(f"[2/4] patch {TARGET_CLASS}.C()")
    f = smali_dir / TARGET_CLASS
    s = f.read_text(encoding="utf-8")
    n = s.count(NEEDLE)
    if n != 1:
        print(f"[FATAL] needle count={n}; 补丁可能已应用或脚本结构变了")
        sys.exit(1)
    s = s.replace(NEEDLE, INJECT)
    f.write_bytes(s.replace("\r\n", "\n").encode("utf-8"))  # 保持 LF，避免噪音

    print("[3/4] smali rebuild dex")
    java_jar(SMALI, ["a", str(smali_dir), "-o", str(new_dex)])

    print("[4/4] replace dex in APK")
    subprocess.run([sys.executable, str(REPLACE_DEX), str(src_apk),
                    "classes.dex", str(new_dex), str(out_apk)], check=True)
    print(f"[OK] {out_apk}  (接下来需 zipalign + apksigner 重签)")
    shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
