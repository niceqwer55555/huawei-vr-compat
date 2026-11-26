"""诊断补丁：在 getCurrentDeviceList() return 前打印返回列表 size 和 getRuntimeInterface 状态。

只加日志，不改逻辑。用于确认 AIDL getCurrentDevices() 到底返回什么。
输入必须是已打 devgate 的 dex（当前 c0283152）。
"""
import sys, os, tempfile, subprocess
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
JAVA = os.environ.get("JAVA_BIN", "java")

def run(cmd):
    r = subprocess.run(cmd, shell=True, capture_output=True)
    if r.returncode != 0:
        raise RuntimeError(f"{cmd}\n{r.stdout!r}\n{r.stderr!r}")

src = Path(sys.argv[1]).resolve()
dst = Path(sys.argv[2]).resolve()
work = Path(tempfile.mkdtemp(prefix="diagdev_"))
sm = work / "smali"
run(f'"{JAVA}" -jar "{ROOT/"tools/baksmali-fat.jar"}" d "{src}" -o "{sm}"')
f = sm / "com/huawei/hwvrexternaldevice/interactive/VrInteractiveDeviceManager.smali"
t = f.read_text(encoding="utf-8")

# 在 :goto_1c 的 return-object v0 前插入日志（该标签唯一）
# 找 getCurrentDeviceList 方法体内唯一的 "return-object v0"（前面带 :goto_XX）
import re
m = re.search(r'(\.method public getCurrentDeviceList\(\)Ljava/util/List;.*?\.end method)', t, re.S)
if not m:
    raise SystemExit("method not found")
method = m.group(1)
# 方法内第一个 return-object v0
ret_pat = re.compile(r'(    :goto_\w+\n    return-object v0)')
rm = ret_pat.search(method)
if not rm:
    raise SystemExit("return label not found in method")
logcode = '''    # diagnostic: log returned device list size
    const-string v1, "DEVGATE"
    invoke-interface {v0}, Ljava/util/List;->size()I
    move-result v2
    invoke-static {v2}, Ljava/lang/String;->valueOf(I)Ljava/lang/String;
    move-result-object v2
    invoke-static {v1, v2}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I
    return-object v0'''
new_method = method[:rm.start()] + logcode + method[rm.end():]
t = t[:m.start(1)] + new_method + t[m.end(1):]
f.write_text(t, encoding="utf-8")
run(f'"{JAVA}" -jar "{ROOT/"tools/smali-fat.jar"}" assemble "{sm}" -o "{dst}"')
print(f"WROTE {dst} ({dst.stat().st_size})")
