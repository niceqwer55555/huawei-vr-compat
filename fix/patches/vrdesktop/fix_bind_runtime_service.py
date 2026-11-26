"""让 launcherx 在 VrFramework.init() 时绑定 ArvrRuntimeService（Internal 接口）。

背景（2026-11-26 21:xx+ 诊断）：
- 手柄数据进 VR SDK 的链路 = VrInteractiveDeviceManager.getCurrentDevices()
  -> VrFramework.getRuntimeInterface() -> VrParamsRequester.mRuntimeInterface
  (ArvrRuntimeInternalInterface)。
- 该接口由 VrParamsRequester.bindRuntimeService(context) 绑定（ACTION_GET_INTERNAL_INTERFACE）；
  服务端 hvrframework 外的 hvrsdkserverapp 的 onBind INTERNAL 分支已经被 patch 放行（返回
  service/d = Internal stub）。
- 但 bindRuntimeService() 在原厂 ROM 里只在 VrFramework$TemperatureReceiver 的一个冷门路径
  被调用，VR 会话里从未触发 => mRuntimeInterface 永远 null => 每帧
  "ArvrRuntimeInterface is not bind yet" + "mRuntimeInterface == null" + GetFenceData NPE
  + getCurrentDevices 空 => Unity "Right controller is not available"。

补丁：在 VrFramework.init(Activity) 既有 ControllerManager.init 补丁块之后，追加
  VrParamsRequester.getInstance().bindRuntimeService(p0)，带 try/catch（复用同一异常标签，
  避免新增 catch 块破坏 .registers）。

输入：设备上 pull 下来的 hvrframework.dex（含 6dof+日志降级+ctrlinit 补丁，md5 91d493ed...）
用法：python3 fix_bind_runtime_service.py <in.dex> <out.dex>
"""

import sys
import tempfile
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BAKSMALI = ROOT / "tools" / "baksmali-fat.jar"
SMALI = ROOT / "tools" / "smali-fat.jar"
import os
JAVA = os.environ.get("JAVA_BIN", "java")  # 可用 JAVA_BIN 指定绝对路径


def run(cmd):
    import subprocess
    r = subprocess.run(cmd, shell=True, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(f"cmd failed: {cmd}\n{r.stdout!r}\n{r.stderr!r}")


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(2)
    src = Path(sys.argv[1]).resolve()
    dst = Path(sys.argv[2]).resolve()
    work = Path(tempfile.mkdtemp(prefix="bindrt_"))
    sm = work / "smali"
    run(f'"{JAVA}" -jar "{BAKSMALI}" d "{src}" -o "{sm}"')

    vf = sm / "com/huawei/vrframework/VrFramework.smali"
    text = vf.read_text(encoding="utf-8")

    # 锚点：ctrlinit 补丁的日志块 + 它的 try/catch 结束 + goto :goto_30
    anchor = '''    const-string v1, "CTRLINIT_OK"

    const-string v2, "VrControllerManager.init called"

    invoke-static {v1, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    :try_end_29
    .catch Ljava/lang/Throwable; {:try_start_19 .. :try_end_29} :catch_2a
    .catchall {:try_start_19 .. :try_end_29} :catchall_43

    :try_start_29
    goto :goto_30
'''
    insert = '''    const-string v1, "CTRLINIT_OK"

    const-string v2, "VrControllerManager.init called"

    invoke-static {v1, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I

    # bind ArvrRuntimeService (Internal interface) for controller/device data
    invoke-static {}, Lcom/huawei/vrframework/VrParamsRequester;->getInstance()Lcom/huawei/vrframework/VrParamsRequester;

    move-result-object v1

    invoke-virtual {v1, p0}, Lcom/huawei/vrframework/VrParamsRequester;->bindRuntimeService(Landroid/content/Context;)V

    const-string v1, "BINDRT_OK"

    const-string v2, "bindRuntimeService called"

    invoke-static {v1, v2}, Landroid/util/Log;->i(Ljava/lang/String;Ljava/lang/String;)I
    :try_end_29
    .catch Ljava/lang/Throwable; {:try_start_19 .. :try_end_29} :catch_2a
    .catchall {:try_start_19 .. :try_end_29} :catchall_43

    :try_start_29
    goto :goto_30
'''
    if anchor not in text:
        # 尝试更宽松：只匹配 log i 之后到 goto
        raise SystemExit("ANCHOR_NOT_FOUND: VrFramework.init ctrlinit block not matched")

    text2 = text.replace(anchor, insert, 1)
    if text2 == text:
        raise SystemExit("REPLACE_FAILED")
    vf.write_text(text2, encoding="utf-8")

    run(f'"{JAVA}" -jar "{SMALI}" assemble "{sm}" -o "{dst}"')
    print(f"WROTE {dst} ({dst.stat().st_size} bytes)")
    print(f"work={work}")


if __name__ == "__main__":
    main()
