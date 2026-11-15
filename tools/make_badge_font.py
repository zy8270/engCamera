# -*- coding: utf-8 -*-
"""生成右下角品牌角标的 CJK 粗体字体（noto_sc_badge.ttf）。

为什么需要：
  Android 的 Typeface.DEFAULT_BOLD 对 CJK 在多数 ROM 上没有真 Bold 字重，
  会回退成细体（本项目巡逻模板已踩过：v269）。角标是品牌标识，必须是真粗体。

为什么单独一份而不复用 noto_sc_bold.ttf：
  现有 noto_sc_bold.ttf 是巡逻模板「执勤巡逻」四字专用子集，巡逻模板正在线上使用。
  动它就有回归风险，所以角标单独一份，互不影响。

为什么是 GB2312 全集而不是 10 字子集（2026-11-15 用户打回）：
  角标文字改成**用户可自定义**后，固定 10 字子集就不成立了——用户输入「今日」
  这类不在子集里的字，Android 会回退系统字体，导致同一张图上「今日」和「水印」
  字形明显不一致。改为覆盖 GB2312 全集（6763 汉字 + ASCII + 常用标点），
  体积约 2MB，能覆盖工程/公司/项目名的实际用字。

来源：Windows 自带 NotoSansSC-VF.ttf（可变字体），实例化到 wght=700 再子集化。

用法：
    python -I tools/make_badge_font.py
"""
import io
import os
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

SRC_CANDIDATES = [
    r"C:\Windows\Fonts\NotoSansSC-VF.ttf",
    r"C:\Windows\Fonts\msyhbd.ttc",
]
DST = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "assets", "fonts", "noto_sc_badge.ttf",
)

# 体积目标（超过就警告）
SIZE_WARN = 3 * 1024 * 1024


def gb2312_unicodes() -> list:
    """GB2312 全部汉字（一级 3755 + 二级 3008 = 6763）。"""
    codes = []
    for hi in range(0xB0, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                codes.append(ord(bytes([hi, lo]).decode("gb2312")))
            except UnicodeDecodeError:
                pass
    return codes


def extra_unicodes() -> list:
    """ASCII + 常用标点 + 全角符号（防伪码/日期/公司名里都可能出现）。"""
    codes = list(range(0x20, 0x7F))          # ASCII 可打印
    codes += list(range(0xA0, 0x100))        # Latin-1 补充
    codes += list(range(0x2000, 0x206F))     # 常用标点（— … ‘’ “” 等）
    codes += list(range(0x3000, 0x3040))     # CJK 标点（、。「」等）
    codes += list(range(0xFF00, 0xFFF0))     # 全角 ASCII / 全角标点
    return codes


def find_source() -> str:
    for p in SRC_CANDIDATES:
        if os.path.exists(p):
            return p
    sys.exit("找不到 CJK 源字体，请手动指定 NotoSansSC-VF.ttf 路径")


def main() -> None:
    src = find_source()
    print("源字体:", src)

    font = TTFont(src)
    # 可变字体先实例化到 Bold 700：直接子集可变字体在 Android 上表现不稳，
    # 且角标只有一个字重需求，静态实例更小更可控
    if "fvar" in font:
        print("实例化 wght=700 …")
        font = instancer.instantiateVariableFont(font, {"wght": 700}, inplace=False)

    unicodes = sorted(set(gb2312_unicodes()) | set(extra_unicodes()))
    print(f"子集目标 {len(unicodes)} 个码位（GB2312 汉字 + ASCII + 标点）")

    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.name_legacy = True
    opts.notdef_outline = True
    opts.recalc_bounds = True
    opts.drop_tables += ["DSIG"]

    sub = subset.Subsetter(options=opts)
    sub.populate(unicodes=unicodes)
    sub.subset(font)

    os.makedirs(os.path.dirname(DST), exist_ok=True)
    font.save(DST)

    size = os.path.getsize(DST)
    print(f"已生成 {DST}  ({size / 1024 / 1024:.2f} MB)")
    if size > SIZE_WARN:
        print(f"⚠ 体积超过 {SIZE_WARN / 1024 / 1024:.0f}MB 预警线")

    # 自检：默认文案 + 用户示例文案都必须有字形
    check = TTFont(DST)
    cmap = check.getBestCmap()
    must = "工程水印相机真实可验今日中建八局项目施工0123456789ABCXYZ"
    missing = [c for c in must if ord(c) not in cmap]
    if missing:
        sys.exit(f"子集缺字: {''.join(missing)}")
    print("字形自检通过（含「今日」等自定义示例）")
    print("字重:", check["OS/2"].usWeightClass)


if __name__ == "__main__":
    main()
