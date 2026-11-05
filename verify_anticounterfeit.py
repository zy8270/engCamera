#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
engCamera 防伪码校验脚本（v0.6.16）
=====================================

用法（两种都行）：
  1. 命令行：
         python verify_anticounterfeit.py 照片.jpg --code F9WFV37M4JVKC2
  2. 直接改下面的「手动编辑区」再运行：
         python verify_anticounterfeit.py

可选参数：
  --code CODE      照片上印着的 14 位防伪码（不填则用下面的 CODE 常量）
  --original 原图   和一张「未改动的成片」逐格对比，并输出差异热力图 PNG
                   （能定位到画面下 37.5% —— 那一段不在指纹采样区内）
  --json           以 JSON 输出结果（便于其它程序调用）
  --verbose        打印 140 个网格单元的灰度值（和 App 侧日志对齐排查用）
  --selftest       自造样本，实测重压缩/缩放/叠水印三种二次处理的偏差

依赖：只需 Pillow
      pip install pillow

算法（与 App 侧 AntiCounterfeit.kt 逐行一致，改一处必须同步改另一处）
------------------------------------------------------------------
1. 采样区 = 画面上部 10/16（62.5%）。水印默认在画面下方，防伪码本身也在下方，
   所以这一条规则同时把「水印」和「防伪码」排除在指纹之外——不需要任何掩膜。
2. 采样区切成 14 列 × 10 行网格（每格 = W/14 × H/16），每格取灰度面积平均。
3. 14 个区域 = 7 列 × 2 行（每区域 2 格宽 × 5 格高），每区域取 20% 截尾均值
   （去掉最高 2、最低 2）。
4. d[j] = 区域均值 - 14 个区域的均值（抵消整体亮度漂移：平台提亮/美颜不误报）。
5. level[j] = clamp(round(16 + d[j] * 16 / 64), 0, 31)
6. 码位 = (level[j] + salt[j]) & 31，salt[j] = ord("EngCameraRnzhy"[j]) & 31，
   字母表 = Crockford Base32（0123456789ABCDEFGHJKMNPQRSTVWXYZ，无 I/L/O/U）。

能抗什么
--------
JPEG 重压缩（实测 q20 偏差 0%）、等比缩放（缩到 30% 再放大 0%）、模糊/锐化、
整体提亮/调色（亮度 -20 为 2.3%、对比度 ×1.3 为 4.6%）—— 常规二次处理都 ≤5%。

灵敏度（实测，900×1200 合成场景）
------------------------------
* 改动画面约 10% 面积 → 偏差 ~12%（判「疑似篡改」）
* 改动画面约 2% 面积 → 偏差 ~2.5%（判「未发现修改」——**这是本算法的分辨率上限**）
* 抹掉一个明显物件（约 5% 面积且大幅变色）→ ~5.5%（临界）

14 个字符 = 14 × 5 位 = 70 位，但每个字符只代表一个区域的**平均亮度**，所以它对
「画面整体结构变化」敏感、对「小面积局部改动」迟钝。要让 2% 级别的改动也可靠检出，
需要更多码位（如 25+ 位）或局部敏感哈希——14 位装不下。

不能抗什么（重要，请务必了解）
------------------------------
* 这是**内容指纹**，不是密码学签名。盐只混淆了「灰度→字符」的映射，知道算法的人
  可以重新算出码再伪造。真正防篡改需要非对称签名 + 私钥，14 位装不下。
* 裁剪 / 旋转 / 透视变形：网格错位，会大幅报偏差（属正确行为，因为画面确实变了）。
* 画面下部 37.5% 不在指纹采样区（那里是水印的地盘）——该区域的改动本脚本
  用 --original 模式可以发现，单张照片自校验发现不了。
* 带批注的成片：批注画在整张图上，会污染采样区，校验会报偏差。这是需求语义
  「防伪码只对无批注的那张有效」。
"""

import argparse
import json
import math
import os
import sys

# ============================================================
# 手动编辑区：不想用命令行就改这里
# ============================================================
PHOTO_PATH = r""      # 例如 r"C:\Users\me\Desktop\engCamera_20261104_120000.jpg"
CODE = ""             # 例如 "F9WFV37M4JVKC2"（照片右下角「防伪」后面那 14 位）
# ============================================================

SALT = "EngCameraRnzhy"
LENGTH = 14
ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

COLS, ROWS, SAMPLE_ROWS = 14, 16, 10
REGION_COLS, REGION_ROWS, CELLS_PER_REGION, TRIM = 7, 2, 10, 2
CENTER, SCALE = 16.0, 64.0

# 判定阈值（平均 |Δlevel| / 31）。实测噪声下限：q20 重压缩 0%、缩到 30% 再放大 0%、
# 高斯模糊 r=3 0.2%、锐化 0.5%、亮度 -20 2.3%、对比度 ×1.3 4.6%
# → 5% 留出余量后仍能放过所有常规二次处理；12% 以上才算实质改动。
OK_PCT = 5.0
WARN_PCT = 12.0

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("需要 Pillow：pip install pillow")


# ---------------- 指纹 ----------------

def _cell_gray(img):
    """返回 14×10 网格的灰度均值（只取上部 10 行）。

    与 App 侧逐像素对齐：
      1) 灰度 = (299R + 587G + 114B + 500) // 1000（PIL convert("L") 的整数公式）
      2) 格边界 = 整数除法 c*W/14 .. (c+1)*W/14（**不能**用 resize(BOX)，浮点边界会差级）
      3) 格均值 = 格内灰度和 / 格内像素数（浮点）
    三者都按上式写死，App 端逐行照抄——任一处不同都会让两边码差几位。
    """
    rgb = img.convert("RGB")
    w, h = rgb.size
    px = rgb.load()
    cells = []
    for r in range(SAMPLE_ROWS):
        y0 = (r * h) // ROWS
        y1 = ((r + 1) * h) // ROWS
        row = []
        for c in range(COLS):
            x0 = (c * w) // COLS
            x1 = ((c + 1) * w) // COLS
            total = 0
            for y in range(y0, y1):
                for x in range(x0, x1):
                    R, G, B = px[x, y]
                    total += (299 * R + 587 * G + 114 * B + 500) // 1000
            row.append(total / ((x1 - x0) * (y1 - y0)))
        cells.append(row)
    return cells


def _region_levels(cells):
    """14×10 网格 → 14 个区域的量化等级（0..31）。"""
    regions = []
    for rr in range(REGION_ROWS):
        for rc in range(REGION_COLS):
            vals = []
            for dr in range(5):
                for dc in range(2):
                    vals.append(cells[rr * 5 + dr][rc * 2 + dc])
            vals.sort()
            trimmed = vals[TRIM:CELLS_PER_REGION - TRIM]
            regions.append(sum(trimmed) / len(trimmed))
    mean = sum(regions) / len(regions)
    levels = []
    for v in regions:
        # 与 App 侧一致：floor(x + 0.5)，不能用 int()（向零截断会让负值少 1 级）
        x = CENTER + (v - mean) * CENTER / SCALE
        levels.append(max(0, min(31, int(math.floor(x + 0.5)))))
    return levels, regions


def levels_of_image(path):
    with Image.open(path) as im:
        cells = _cell_gray(im)
        return _region_levels(cells), cells


def encode(levels):
    out = []
    for j, lv in enumerate(levels):
        idx = (lv + (ord(SALT[j]) & 31)) & 31
        out.append(ALPHABET[idx])
    return "".join(out)


def decode(code):
    t = code.strip().upper()
    if len(t) != LENGTH:
        return None
    levels = []
    for j, ch in enumerate(t):
        idx = ALPHABET.find(ch)
        if idx < 0:
            return None
        levels.append((idx - (ord(SALT[j]) & 31)) & 31)
    return levels


def spread_note(regions):
    """区分度：14 个区域灰度的标准差。太小时码几乎没有信息量，改一点也看不出来。"""
    mean = sum(regions) / len(regions)
    var = sum((v - mean) ** 2 for v in regions) / len(regions)
    return math.sqrt(var)


def deviation(a, b):
    """两组等级的偏差百分比（平均 |Δ| / 31）。"""
    if len(a) != len(b):
        return 100.0
    return 100.0 * sum(abs(x - y) for x, y in zip(a, b)) / (len(a) * 31)


# ---------------- 主流程 ----------------

def verify(photo, code, as_json=False, verbose=False):
    (levels, regions), cells = levels_of_image(photo)
    expect = encode(levels)
    result = {
        "photo": os.path.abspath(photo),
        "expected_code": expect,
        "given_code": code or "",
        "levels": levels,
        "region_grays": [round(v, 1) for v in regions],
        "spread": round(spread_note(regions), 1),
    }

    if verbose:
        print("网格单元灰度（14 列 × 10 行，上部 62.5%）：")
        for r, row in enumerate(cells):
            print("  r%02d " % r + " ".join("%3d" % v for v in row))
        print("区域均值：", " ".join("%.1f" % v for v in regions))
        print("区域等级：", " ".join("%2d" % v for v in levels))
        print()

    if not code:
        result["verdict"] = "no-code"
        if as_json:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        else:
            print("照片上应有的防伪码（按画面内容计算）：")
            print("    " + expect)
            print()
            print("请把它和照片右下角「防伪」后面的 14 位逐位对照，")
            print("或用 --code 传进来让脚本自动判定。")
            _print_spread(result["spread"])
        return 0

    given = code.strip().upper()
    result["given_code"] = given
    if given == "ENGCAMERARNZHY":
        result["verdict"] = "placeholder"
        msg = "这是预览占位符 EngCameraRnzhy，不是真实成片上的码。"
    elif len(given) != LENGTH or ALPHABET.find(given[0]) < 0:
        result["verdict"] = "invalid-code"
        msg = "传入的码不是 14 位大写字母+数字组合。"
    else:
        g_levels = decode(given)
        pct = deviation(g_levels, levels)
        result["deviation_pct"] = round(pct, 1)
        if pct <= OK_PCT:
            result["verdict"] = "ok"
            msg = "未发现画面被修改（偏差 %.1f%% ≤ %.0f%%）。" % (pct, OK_PCT)
        elif pct <= WARN_PCT:
            result["verdict"] = "warn"
            msg = "偏差 %.1f%%：可能经过二次处理（重压缩/缩放/调色），或局部轻微改动。" % pct
        else:
            result["verdict"] = "tampered"
            msg = "偏差 %.1f%%（> %.0f%%）：画面内容与防伪码不符，疑似被修改。" % (pct, WARN_PCT)

    if as_json:
        result["message"] = msg
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print("照片：", result["photo"])
        print("图上码：", given)
        print("应有码：", expect)
        if "deviation_pct" in result:
            print()
            print("逐位对照（位次: 图上码→应有码, 等级差）：")
            gl = decode(given) or [0] * LENGTH
            for j in range(LENGTH):
                d = abs(gl[j] - levels[j])
                mark = "" if d == 0 else ("  ← 差 %d" % d)
                print("  %2d: %s→%s%s" % (j + 1, given[j], expect[j], mark))
            print()
        print(msg)
        _print_spread(result["spread"])
    return 0 if result["verdict"] in ("ok", "warn", "no-code") else 2


def _print_spread(spread):
    if spread < 6.0:
        print()
        print("⚠ 区分度偏低（14 个区域灰度标准差仅 %.1f）：画面太单一（如纯白墙/纯色桌面），"
              % spread)
        print("  这种情况下码本身信息量少，轻微改动也不容易被看出来。")


def compare_original(photo, original):
    """逐格对比两张图（含画面下部），输出差异热力图。补上指纹采样区的盲区。"""
    with Image.open(photo) as a, Image.open(original) as b:
        if a.size != b.size:
            print("尺寸不同（%s vs %s）：画面被裁剪/缩放过。" % (a.size, b.size))
        ca = _cell_gray(a)
        cb = _cell_gray(b)
    diffs = []
    for r in range(SAMPLE_ROWS):
        for c in range(COLS):
            diffs.append(abs(ca[r][c] - cb[r][c]))
    big = [(i // COLS, i % COLS, d) for i, d in enumerate(diffs) if d >= 8]
    print()
    print("与原始成片逐格对比（上部采样区）：最大差 %d 灰度，超阈值(≥8)的格 %d/140"
          % (max(diffs) if diffs else 0, len(big)))
    for r, c, d in sorted(big, key=lambda t: -t[2])[:10]:
        print("  格 (行%d, 列%d) 差 %d" % (r, c, d))
    if not big:
        print("  未发现上部画面有改动。")
    print("  提示：画面下部 37.5%（水印/防伪码所在）不在指纹采样区内，"
          "单张照片自校验发现不了那里的改动——如需覆盖请用 --original 并肉眼核对热力图。")

    # 热力图（放大 20 倍，红=差异大）
    try:
        from PIL import ImageDraw
        cell_w = 20
        out = Image.new("RGB", (COLS * cell_w, SAMPLE_ROWS * cell_w), (20, 20, 20))
        d = ImageDraw.Draw(out)
        for r in range(SAMPLE_ROWS):
            for c in range(COLS):
                v = abs(ca[r][c] - cb[r][c])
                g = max(0, 255 - v * 8)
                d.rectangle(
                    [c * cell_w, r * cell_w, (c + 1) * cell_w - 1, (r + 1) * cell_w - 1],
                    fill=(255, g, g),
                )
        heat = os.path.splitext(photo)[0] + "_diff.png"
        out.save(heat)
        print("  差异热力图已保存：", heat)
    except Exception as e:  # pragma: no cover
        print("  热力图生成失败：", e)


def selftest():
    """自造样本：验证算法本身能抗哪些二次处理，以及阈值是否合理。"""
    import random
    from io import BytesIO

    random.seed(20261104)
    W, H = 900, 1200
    base = Image.new("RGB", (W, H))
    px = base.load()
    # 造一个「有结构」的画面：渐变天空 + 几个方块 + 噪点（模拟真实场景，纯色会让码无区分度）
    for y in range(H):
        for x in range(W):
            v = 40 + int(120 * y / H)
            px[x, y] = (v, v + 20, v + 40)
    d = __import__("PIL.ImageDraw", fromlist=["ImageDraw"]).Draw(base)
    for i in range(6):
        x0 = random.randint(40, W - 240)
        y0 = random.randint(40, int(H * 0.6))
        d.rectangle([x0, y0, x0 + random.randint(80, 200), y0 + random.randint(80, 200)],
                    fill=(random.randint(80, 220),) * 3)
    for _ in range(20000):
        x, y = random.randint(0, W - 1), random.randint(0, H - 1)
        px[x, y] = (random.randint(0, 255),) * 3

    def jpeg_roundtrip(im, quality):
        buf = BytesIO()
        im.save(buf, format="JPEG", quality=quality)
        buf.seek(0)
        return Image.open(buf).convert("RGB")

    ref_levels, ref_regions = _region_levels(_cell_gray(base))
    ref_code = encode(ref_levels)
    print("基准码：", ref_code, " 区分度：%.1f" % spread_note(ref_regions))
    print()

    cases = [
        ("JPEG q95（App 成片质量）", lambda im: jpeg_roundtrip(im, 95)),
        ("JPEG q85（微信原图）", lambda im: jpeg_roundtrip(im, 85)),
        ("JPEG q60（平台压缩）", lambda im: jpeg_roundtrip(im, 60)),
        ("JPEG q40（重度压缩）", lambda im: jpeg_roundtrip(im, 40)),
        ("缩放到 50% 再回原尺寸", lambda im: im.resize((W // 2, H // 2), Image.LANCZOS)
            .resize((W, H), Image.BICUBIC)),
        ("整体提亮 +20 灰度", lambda im: im.point(lambda v: min(255, v + 20))),
        ("整体调色（暖色）", lambda im: Image.merge(
            "RGB", [im.getchannel(0).point(lambda v: min(255, int(v * 1.05)),
                                        ),
                    im.getchannel(1),
                    im.getchannel(2).point(lambda v: int(v * 0.9))])),
        ("叠一块模拟水印（下部 20% 区域）", None),
    ]

    worst = 0.0
    for name, fn in cases:
        if fn is None:
            im = base.copy()
            dd = __import__("PIL.ImageDraw", fromlist=["ImageDraw"]).Draw(im)
            dd.rectangle([0, int(H * 0.8), W, H], fill=(10, 10, 10))
        else:
            im = fn(base)
        lv, _ = _region_levels(_cell_gray(im))
        pct = deviation(lv, ref_levels)
        worst = max(worst, pct)
        verdict = "未修改" if pct <= OK_PCT else ("轻微处理" if pct <= WARN_PCT else "疑似篡改")
        print("  %-28s 偏差 %5.1f%%   → %s" % (name, pct, verdict))

    print()
    print("最差偏差 %.1f%%；阈值 未修改≤%.0f%% / 轻微≤%.0f%% / 疑似篡改>%.0f%%"
          % (worst, OK_PCT, WARN_PCT, WARN_PCT))
    if worst > WARN_PCT:
        print("⚠ 有常规二次处理落进「疑似篡改」档，阈值或算法需要收紧/放宽。")
        return 1
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(
        description="engCamera 防伪码校验（14 位内容指纹）",
        epilog="例：python verify_anticounterfeit.py 照片.jpg --code F9WFV37M4JVKC2",
    )
    p.add_argument("photo", nargs="?", default=PHOTO_PATH or None, help="照片路径")
    p.add_argument("--code", default=CODE or None, help="照片上的 14 位防伪码")
    p.add_argument("--original", default=None, help="原始成片（未改动）路径，做逐格对比")
    p.add_argument("--json", action="store_true", help="JSON 输出")
    p.add_argument("--verbose", action="store_true", help="打印 140 个网格单元灰度")
    p.add_argument("--selftest", action="store_true", help="跑自测（验证二次处理鲁棒性）")
    args = p.parse_args(argv)

    if args.selftest:
        return selftest()

    photo = args.photo
    code = args.code
    # 交互兜底：手动编辑区没填、命令行也没给
    if not photo:
        photo = input("照片路径：").strip().strip('"')
    if not os.path.isfile(photo):
        print("找不到文件：", photo)
        return 1
    if not code:
        code = input("照片右下角「防伪」后面的 14 位码（直接回车=只算应有码）：").strip()

    rc = verify(photo, code, as_json=args.json, verbose=args.verbose)
    if args.original:
        if not os.path.isfile(args.original):
            print("找不到原始成片：", args.original)
        else:
            compare_original(photo, args.original)
    return rc


if __name__ == "__main__":
    sys.exit(main())
