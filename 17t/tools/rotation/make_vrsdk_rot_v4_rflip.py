#!/usr/bin/env python3
"""v4 按眼分支补丁：修「左眼位置正确、右眼上下颠倒」。

背景（2026-11-13）：
  v3-270 把 20 个 vertex shader 块里的 60 处最终采样 UV 从
      vec2( proj.x * projIZ, proj.y * projIZ )   = (u, v)
  改成
      vec2(proj.y*projIZ,1.0-proj.x*projIZ)      = (v, 1-u)
  戴镜结果：左眼位置正确，右眼上下颠倒 ⇒ 270° 方向对，但左右眼变换
  必须不同：右眼要额外做一次 Y 翻转 (v, 1-u) -> (v, u)。

左右眼判据来自厂商自己的 shader（块 19 @0x100b3e）：
      oEyePosition = aPosition.x;
      if(oEyePosition < 0.0)  oTexCoord.x - 0.08;   // 左眼
      else                    oTexCoord.x + 0.08;   // 右眼
  ⇒ aPosition.x < 0 是左眼，> 0 是右眼。20 个 shader 块全部声明了 aPosition。
  另：aGridPosition.x 用 mix(left, right, ...) 选左右眼投影矩阵，可作交叉印证。

按眼分支（令 u = proj.x*projIZ，es = sign(aPosition.x)）：
      y = 0.5 + (u - 0.5) * es
  左眼 es=-1 → y = 1.0-u  == v3-270（★ 左眼逐点等价，零回退风险）
  右眼 es=+1 → y = u      == 在 v3-270 基础上做 Y 翻转

档位：
  none  字节等于 libvrsdk.v3270.so（对照 / 回滚基准）＝全局 (v,1-u) 270°
  a     ★ 推荐：左眼保持 270、右眼 Y 翻转（es 系数 +）
  b     符号取反：左眼 Y 翻转、右眼保持 270（es 系数 -）——若 a 戴镜发现
        左右眼反了，直接切 b，无需重新分析
  both  双眼都 Y 翻转 = (v,u)，纯诊断档
  180   全局平面内 180° = (1-u,1-v)，WALLE 机型相对 Halliday 多 90° 时转正用
  r180  左眼保持 270、右眼整 180°（双轴翻转），右眼上下颠倒候选
  c     内容块按眼镜像 270°（a 式）、CA/色散块原厂 (u,v) 不旋转
        ——实测仍变色/切割，证明 CA 与内容旋转必须一致
  d     全局一致 (1-v,u) 90° 逆时针旋转，内容块与 CA 块同旋转
        ——保持色散采样与内容朝向严格一致 → 不破坏颜色/切割

★ 等长约束处理：a/b 的完整内联式 59 字节比 v3-270 的 37 字节长 22，
  块 13(0xf6cf3) / 15(0xf9fa5) 只有 1 处余量，会超 5 字节。故：
    - UV 数 >= 3 的块：在 void main(){ 后插入一次
        float es=sign(aPosition.x);
      每处 UV 用短变量 es（省 16 字节/处）
    - UV 数 == 1 的块：直接内联 sign(aPosition.x)（省去声明开销）
  这样 20 个块全部有余量，且不改动左眼的数学结果。

UI 层 oTexCoordUI 一律保持 v3-270 的 vec2(uip.y,1.0-uip.x)：
  用户本轮没有报颜色/UI 错位，最小改动优先。
  widthStep/heightStep 仍是十字对称锐化核，不随旋转改动。

约束：只改 .rodata 里的 shader 字符串；块内等长替换（尾部补空格）；
so 大小 / 代码段 / 块外字节零改动；块边界 NUL 与 #version 首行保持完好。
"""
import sys
import hashlib
import re

SCAN_START, SCAN_END = 0xE8000, 0x108000
UV = 'vec2( proj.x * projIZ, proj.y * projIZ )'

UV_270 = 'vec2(proj.y*projIZ,1.0-proj.x*projIZ)'
# 另一个 90° 方向（逆时针 90°，= (1-v, u)），与 v4none 的 (v,1-u) 相差 180°。
# 全局一致旋转（含 CA 块），保持色散参数与内容朝向匹配 → 颜色/切割不破坏。
UV_90 = 'vec2(1.0-proj.y*projIZ,proj.x*projIZ)'
UV_BOTH = 'vec2(proj.y*projIZ,proj.x*projIZ)'
# 180° in-plane rotation: (u,v) -> (1-u,1-v).
# WALLE 机型相对 Halliday 多 90° 旋转，故 Halliday 下 upright 的 v4none(270°)
# 在 WALLE 下躺倒 90°；减 90° 得 180° 档，应在 WALLE 下转正。
UV_180 = 'vec2(1.0-proj.x*projIZ,1.0-proj.y*projIZ)'
# 用 0.5 而非 .5：GLSL 允许省略前导 0，但项目既有补丁一律写全，
# 且实测余量足够（最紧的块仍剩 6 字节），避免老编译器挑剔。
UV_EYE = {
    'a': 'vec2(proj.y*projIZ,.5+clamp((proj.x*projIZ-.5)*ES,-.49,.49))',
    'b': 'vec2(proj.y*projIZ,0.5-(proj.x*projIZ-0.5)*ES)',
    # 右眼整 180° 旋转候选（左眼保持 270 不变）：
    #   左眼 es=-1 -> (proj.y*projIZ, 1.0-proj.x*projIZ)   == 270 正确
    #   右眼 es=+1 -> (1.0-proj.y*projIZ, 1.0-proj.x*projIZ) == 180 候选
    # 假设 step a/b（单轴屏Y翻转）均失败，右眼需双轴翻转。实测验证用。
    # 紧凑写法：1.0->1. / 0.5->.5（GLSL 合法浮点字面量），每块省字节以塞进最紧块。
    'r180': 'vec2(0.5-(proj.y*projIZ-.5)*ES,1.-proj.x*projIZ)',
    # rflip: left keeps 270 (v,1-u); right = 90 (1-v,u)
    #   left es=-1 -> (v, 1-u)  == correct
    #   right es=+1 -> (1-v, u)  == 90 opposite rotation
    # 2026-11-26 订正：第二个分量必须是 +ES 才是 左(1-u)/右(u)。
    #   旧式 -ES 会让左眼算成 (v,u)【镜像】、右眼算成 (1-v,1-u)【上下翻】
    #   ——两眼都坏，即用户所见「都上下镜像颠倒」。
    #   推导：es=-1 -> 0.5+(u-.5)*(-1)=1-u; es=+1 -> 0.5+(u-.5)*(+1)=u。
    'rflip': 'vec2(0.5-(proj.y*projIZ-.5)*ES,0.5+(proj.x*projIZ-.5)*ES)',
}
# 眼选择器用 sign(aPosition.x)（顶点屏幕横坐标：左半屏 -1 / 右半屏 +1）。
# 实测 aGridPosition.x 在 CA/色散显示顶点块里未被有效绑定（恒为 0），
# 会导致 es 恒为 -1、两眼都按左眼投影采样 → 变色+切割+不融合；
# sign(aPosition.x) 不依赖该属性，眼分割干净（旧 v4a 只报右眼反、无变色/切割）。
ES_DECL = 'float es=sign(aPosition.x);'
ES_INLINE = 'sign(aPosition.x)'

UI_DECL = 'vec2 uip=vec2(projUI.x*projIZUI,projUI.y*projIZUI)*2.0-0.5;'
UI_270 = 'oTexCoordUI=vec2(uip.y,1.0-uip.x);'
UI_180 = 'oTexCoordUI=vec2(1.0-uip.x,1.0-uip.y);'
# d 档：内容 (1-v,u) 全局一致旋转，UI 同步逆时针 90° 以保持叠层朝向一致
UI_90 = 'oTexCoordUI=vec2(1.0-uip.y,uip.x);'
UI_RE = re.compile(
    r'oTexCoordUI\s*=\s*vec2\(\s*projUI\.x\s*\*\s*projIZUI\s*,\s*'
    r'projUI\.y\s*\*\s*projIZUI\s*\)\s*;\s*\n\s*'
    r'oTexCoordUI\s*=\s*oTexCoordUI\s*\*\s*2\.0\s*-\s*0\.5\s*;'
)

STEPS = ('none', 'a', 'b', 'both', '180', 'r180', 'rflip', 'c', 'd')


def find_blocks(d):
    """含主 UV 或含 UI 赋值的 shader 块 [(start, end, uv_count, has_ui)]"""
    out = []
    i = SCAN_START
    while i < SCAN_END:
        j = d.find(b'\0', i, SCAN_END)
        if j < 0:
            j = SCAN_END
        if j - i >= 60:
            t = d[i:j]
            if b'void main' in t and (UV.encode() in t or b'projUI.x' in t):
                has_ui = bool(UI_RE.search(t.decode('ascii', 'replace')))
                out.append((i, j, t.count(UV.encode()), has_ui))
        i = j + 1
    return out


def compress(s):
    """去掉每行前导空白；#version 必须留在首行且行首无空白。"""
    lines = s.split('\n')
    return '\n'.join(l if n == 0 else l.lstrip() for n, l in enumerate(lines))


def build(src_path, dst_path, step):
    if step not in STEPS:
        raise SystemExit('step 必须是 ' + '/'.join(STEPS))
    src = open(src_path, 'rb').read()
    d = bytearray(src)
    blocks = find_blocks(src)
    total_uv = sum(c for _, _, c, _ in blocks)
    uv_in_so = len(re.findall(re.escape(UV.encode()), src))
    if total_uv != uv_in_so:
        raise SystemExit(f'枚举 UV {total_uv} != 全 so {uv_in_so}，扫描范围不对')
    ui_blocks = [b for b in blocks if b[3]]
    print(f'发现 {len(blocks)} 个 shader 块 / {total_uv} 处主 UV / '
          f'{len(ui_blocks)} 个含 UI 层坐标')

    total_uv_done = 0
    total_ui_done = 0
    es_decl_blocks = 0
    es_inline_blocks = 0
    for a, b, expect, has_ui in blocks:
        txt = src[a:b].decode('ascii')
        new = compress(txt)
        cnt = new.count(UV)
        if cnt != expect:
            raise SystemExit(f'0x{a:x}: 期望 {expect} 处 UV，实际 {cnt}')

        if step in ('a', 'b', 'r180', 'rflip'):
            if cnt >= 3:
                # 3 处以上 UV：插入一次短变量声明，摊薄长度开销
                marker = '{\n'
                if marker not in new:
                    raise SystemExit(f'0x{a:x}: 找不到 void main() 的 {{')
                # 只查「变量声明」形式的冲突；#version 300 es 里的 es
                # 是版本号不是标识符，用 \bes\b 会误报（2026-11-13 实测）。
                if re.search(r'\b(?:float|int|vec[234]|mat[234])\s+es\b', new):
                    raise SystemExit(f'0x{a:x}: 变量名 es 与现有声明冲突')
                new = new.replace(marker, marker + ES_DECL + '\n', 1)
                rep = UV_EYE[step].replace('ES', 'es')
                es_decl_blocks += 1
            else:
                rep = UV_EYE[step].replace('ES', ES_INLINE)
                es_inline_blocks += 1
        elif step == 'both':
            rep = UV_BOTH
        elif step == '180':
            rep = UV_180
        elif step == 'r180':
            rep = UV_EYE['r180'].replace('ES', 'es')
        elif step == 'd':
            # 全局一致 (1-v,u) 90° 逆时针旋转：内容块与 CA/色散块同旋转，
            # 保持色散采样与内容朝向严格一致 → 不破坏颜色/切割（原厂全部 (u,v)）。
            rep = UV_90
        elif step == 'c':
            # 尊重原厂：CA/色散块（含 oTexCoord1r/g/b）保持 (u,v) 完全不旋转，
            # 避免 sign(aPosition.x) 在 CA 块里把画面沿中线劈开 → 变色/切割；
            # 仅 PACK/内容块做 es 镜像 270°（左 270 / 右 90），es=sign(aPosition.x)
            # 在 PACK 顶点块有效（oEyePosition=aPosition.x）。
            # 真 CA/色散块：同时含独立的 oTexCoord1r / oTexCoord1g / oTexCoord1b
            # 三通道（注意 oTexCoord1rgb 是 SBS 打包块，子串含 oTexCoord1r，
            # 必须排除，否则打包块被误判为 CA 而不旋转）。
            if ('oTexCoord1g' in txt) and ('oTexCoord1b' in txt):
                rep = UV  # CA 块不旋转，保留原厂 (u,v)
            elif cnt >= 3:
                marker = '{\n'
                if marker not in new:
                    raise SystemExit(f'0x{a:x}: 找不到 void main() 的 {{')
                if re.search(r'\b(?:float|int|vec[234]|mat[234])\s+es\b', new):
                    raise SystemExit(f'0x{a:x}: 变量名 es 与现有声明冲突')
                new = new.replace(marker, marker + ES_DECL + '\n', 1)
                rep = UV_EYE['a'].replace('ES', 'es')
                es_decl_blocks += 1
            else:
                rep = UV_EYE['a'].replace('ES', ES_INLINE)
                es_inline_blocks += 1
        else:
            rep = UV_270
        new = new.replace(UV, rep)
        total_uv_done += cnt

        if has_ui:
            ui_rot = UI_180 if step == '180' else (UI_90 if step == 'd' else UI_270)
            ui_repl = UI_DECL + '\n' + ui_rot
            new, n = UI_RE.subn(ui_repl, new, count=1)
            if n != 1:
                raise SystemExit(f'0x{a:x}: UI 赋值匹配失败')
            total_ui_done += 1

        import re as _re
        new = _re.sub(r'[ \t]{2,}', ' ', new)
        new = _re.sub(r' ([=*,+\-()]) ', r'\1', new)
        new = _re.sub(r'([=*,+\-()]) ', r'\1', new)
        new = _re.sub(r' ([=*,+\-()])', r'\1', new)
        new = _re.sub(r'\n[ \t]+', '\n', new)
        nb = new.encode('ascii')
        if len(nb) > (b - a):
            raise SystemExit(f'0x{a:x}: 替换后超长 {len(nb)} > {b-a}')
        if nb.count(b'{') != nb.count(b'}') or nb.count(b'(') != nb.count(b')'):
            raise SystemExit(f'0x{a:x}: GLSL 括号不配对')
        d[a:b] = nb + b' ' * ((b - a) - len(nb))

    out = bytes(d)
    assert len(out) == len(src), 'so 大小变了'

    # 硬断言（v3 时代踩过占位符残留的坑）
    if b'PLACEHOLDER' in out:
        raise SystemExit('占位符残留')
    if step != 'c' and out.count(UV.encode()) != 0:
        raise SystemExit(f'仍有 {out.count(UV.encode())} 处旧主 UV 未替换')
    if total_ui_done != len(ui_blocks):
        raise SystemExit(f'UI 替换数 {total_ui_done} != 含 UI 块数 {len(ui_blocks)}')
    if out.count(b'vec2 uip=') != len(ui_blocks):
        raise SystemExit('UI 临时变量数异常')
    if step in ('a', 'b', 'r180', 'rflip'):
        want_decl = len([x for x in blocks if x[2] >= 3])
        if es_decl_blocks != want_decl:
            raise SystemExit(f'es 声明块数 {es_decl_blocks} != {want_decl}')
        if out.count(ES_DECL.encode()) != want_decl:
            raise SystemExit('es 声明总次数异常')
    diff = [i for i in range(len(src)) if src[i] != out[i]]
    outside = [i for i in diff if not any(a <= i < b for a, b, _, _ in blocks)]
    if outside:
        raise SystemExit(f'块外出现 {len(outside)} 处改动: {[hex(x) for x in outside[:8]]}')
    for a, b, _, _ in blocks:
        if out[b] != 0:
            raise SystemExit(f'0x{a:x}: 块边界 NUL 被破坏')
        if src[a:a + 8] == b'#version' and out[a:a + 8] != b'#version':
            raise SystemExit(f'0x{a:x}: #version 首行被破坏')

    open(dst_path, 'wb').write(out)
    print(f'{dst_path}  step={step}  md5={hashlib.md5(out).hexdigest()}  '
          f'主UV {total_uv_done} 处 / UI {total_ui_done} 处, '
          f'es声明 {es_decl_blocks} 块 / 内联 {es_inline_blocks} 块, '
          f'改动 {len(diff)} 字节, 块外 0 字节')


if __name__ == '__main__':
    if len(sys.argv) != 4:
        raise SystemExit('用法: make_vrsdk_rot_v4.py <base.so> <out.so> '
                         '<none|a|b|both|180|r180|c|d>')
    build(sys.argv[1], sys.argv[2], sys.argv[3])
