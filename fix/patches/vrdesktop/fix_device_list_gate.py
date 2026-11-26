"""强制 VrInteractiveDeviceManager.getCurrentDeviceList() 走 AIDL 设备列表分支。

背景（2026-11-26 23:xx）：
- Unity "Right controller is not available" 的判定链：
  ThirdPartyController.getValidIndices() -> VrInteractiveDeviceManager.getCurrentDeviceList()
- getCurrentDeviceList() 有两个门槛：
    mStatus == 3  且  hw6DofHeaderAvailable == true
  才调用 ArvrRuntimeInternalInterface.getCurrentDevices()（本轮已打通的 AIDL）；
  否则返回空列表。
- 这两个字段由华为 AIDL deviceStateChanged("HUAWEI USB HELMET") 回调设置，
  本 ROM 环境从未触发 -> 恒不满足 -> 设备列表永远空。

补丁：在 getCurrentDeviceList() 开头强制
  mStatus = 3
  hw6DofHeaderAvailable = true
让原有代码自然走 AIDL 分支，从而能真正调用服务端 getCurrentDevices()，
暴露服务端返回的真实设备列表（验证 state 是否为 1）。

这是**验证性补丁**：打通列表门槛，但不注入任何手柄数据。
若服务端设备 state=0，Unity 仍拿不到有效控制器 —— 那时再决定是否改服务端。

用法：python3 fix_device_list_gate.py <in.dex> <out.dex>
输入 hvrframework.dex（当前设备 91d493ed 系列，含 ctrlinit+bindrt 补丁）。
"""

import sys
import os
import tempfile
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BAKSMALI = ROOT / "tools" / "baksmali-fat.jar"
SMALI = ROOT / "tools" / "smali-fat.jar"
JAVA = os.environ.get("JAVA_BIN", "java")


def run(cmd):
    r = subprocess.run(cmd, shell=True, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(f"cmd failed: {cmd}\n{r.stdout!r}\n{r.stderr!r}")


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    src = Path(sys.argv[1]).resolve()
    dst = Path(sys.argv[2]).resolve()
    work = Path(tempfile.mkdtemp(prefix="devgate_"))
    sm = work / "smali"
    run(f'"{JAVA}" -jar "{BAKSMALI}" d "{src}" -o "{sm}"')

    f = sm / "com/huawei/hwvrexternaldevice/interactive/VrInteractiveDeviceManager.smali"
    text = f.read_text(encoding="utf-8")

    # 锚点: getCurrentDeviceList() 里的 ".line 579"（全文件唯一）
    anchor = '    .line 579\n'
    insert = '''    .line 579
    # force device-list gate open (no real HUAWEI USB HELMET callback in this ROM)
    const/4 v1, 0x3
    iput v1, p0, Lcom/huawei/hwvrexternaldevice/interactive/VrInteractiveDeviceManager;->mStatus:I
    const/4 v1, 0x1
    iput-boolean v1, p0, Lcom/huawei/hwvrexternaldevice/interactive/VrInteractiveDeviceManager;->hw6DofHeaderAvailable:Z
'''
    if text.count(anchor) != 1:
        raise SystemExit(f"ANCHOR count={text.count(anchor)} (expect 1), abort")

    text2 = text.replace(anchor, insert, 1)
    if text2 == text:
        raise SystemExit("REPLACE_FAILED")
    f.write_text(text2, encoding="utf-8")

    run(f'"{JAVA}" -jar "{SMALI}" assemble "{sm}" -o "{dst}"')
    print(f"WROTE {dst} ({dst.stat().st_size} bytes)")
    print(f"work={work}")


if __name__ == "__main__":
    main()
