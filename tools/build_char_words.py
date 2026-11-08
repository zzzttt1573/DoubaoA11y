"""
生成单字解释表：给每个常用汉字挑一个最常见的词，朗读时读成“高，高兴的高”。

数据来源：jieba 分词自带的词频表 dict.txt（MIT 许可，开放数据）。
同音词：如果把这个字换成同音字也是常见词（他们/她们/它们），听的人分不出是哪个字，这个词就不用，换下一个词。
读音来自 pypinyin（MIT 许可）。他、她、它用固定读法，见 OVERRIDES。
主要数据来自 NVDA 简体中文字符描述 characterDescriptions.dic（NVDA 简体中文志愿者团队，GPL 第 2 版或更高版本），
NVDA 的解释用词有同音歧义、格式不对或没有收录的字，再用上面 jieba 挑的词。
NVDA 文件下载：https://raw.githubusercontent.com/nvaccess/nvda/master/source/locale/zh_CN/characterDescriptions.dic
用法（需要 pip install jieba pypinyin）：
    python build_char_words.py <输出的 Kotlin 文件> [NVDA 的 characterDescriptions.dic]
"""
import os
import sys
from collections import defaultdict

import jieba
from pypinyin import Style, pinyin

MAX_CHARS = 9000      # 收录的字数（按字频）
CHUNK = 6000          # Kotlin 单个字符串常量不能太长，分段
AMBIG_RATIO = 0.2     # 同音替换后的词词频达到原词的这个比例，就算听不出来
MIN_PICK_RATIO = 0.05 # 换上的词至少要有原词词频的这个比例，太生僻就不换

# 固定读法（照 NVDA 用户习惯的说法，不做同音检查）：读成“她，女她的她”
# NVDA 原文“它”写的是“其他的他”，这里改成同音的“其他”，读出来一样
OVERRIDES = {
    "他": "他们",
    "她": "女她",
    "它": "其他",
}


def is_cjk(c):
    return "\u4e00" <= c <= "\u9fff"


def load_nvda(path):
    """NVDA 条目：字<TAB>(词的字)<TAB>字义。只要“X的x”格式、词里含本字的单字条目"""
    import re
    res = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or "\t" not in line:
                continue
            parts = line.rstrip("\n").split("\t")
            c = parts[0]
            if len(c) != 1 or not is_cjk(c) or len(parts) < 2:
                continue
            m = re.fullmatch(r"\((.+)的(.)\)", parts[1].strip())
            if not m or m.group(2) != c:
                continue
            w = m.group(1)
            if c not in w or not all(is_cjk(x) for x in w) or len(w) > 4:
                continue
            res[c] = w
    return res


def main():
    out = sys.argv[1]
    nvda = load_nvda(sys.argv[2]) if len(sys.argv) > 2 else {}
    path = os.path.join(os.path.dirname(jieba.__file__), "dict.txt")
    char_freq = defaultdict(int)
    freq_of = {}
    cands = defaultdict(list)   # 字 -> [(分数, 词, 位置)]
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) < 2:
                continue
            w, freq = parts[0], int(parts[1])
            if not all(is_cjk(c) for c in w):
                continue
            freq_of[w] = freq
            for c in set(w):
                char_freq[c] += freq
            if len(w) not in (2, 3):
                continue
            for i, c in enumerate(w):
                if w.count(c) != 1:
                    continue
                # 两字词优先，同长度取词频高的
                cands[c].append(((1 if len(w) == 2 else 0, freq), w, i))
    chars = sorted(cands, key=lambda c: -char_freq[c])[:MAX_CHARS]
    # NVDA 收录、jieba 里没有词的字（多是生僻字）也收进来
    chars += sorted(c for c in nvda if c not in set(chars))

    # 读音 -> 常用字（只看收录的字）
    homo = defaultdict(set)
    for c in chars:
        for r in pinyin(c, style=Style.TONE3, heteronym=True)[0]:
            homo[r].add(c)

    def ambiguous(w, i):
        if w not in freq_of:
            return None
        r = pinyin(w, style=Style.TONE3)[i][0]
        limit = max(1, freq_of[w] * AMBIG_RATIO)
        for c2 in homo.get(r, ()):
            if c2 == w[i]:
                continue
            w2 = w[:i] + c2 + w[i + 1:]
            # 换上的字在那个词里也要读这个音（只是多音字的冷门读音相同不算）
            if freq_of.get(w2, 0) >= limit and pinyin(w2, style=Style.TONE3)[i][0] == r:
                return w2
        return None

    best = {}
    changed = []
    from_nvda = 0
    for c in chars:
        if c in OVERRIDES:
            best[c] = OVERRIDES[c]
            continue
        w = nvda.get(c)
        if w and not ambiguous(w, w.index(c)):
            best[c] = w
            from_nvda += 1
            continue
        if w:
            changed.append(f"{c}: NVDA {w} 有同音词，改用 jieba")
        if not cands[c]:
            if w:
                best[c] = w
                from_nvda += 1
            continue
        ranked = sorted(cands[c], reverse=True)[:40]
        pick = None
        top = ranked[0][1]
        for (_, fq), w, i in ranked:
            if fq < freq_of[top] * MIN_PICK_RATIO:
                break
            # 换上的只用两字词（三字词多是人名地名）
            if len(w) != 2:
                continue
            if not ambiguous(w, i):
                pick = w
                break
        best[c] = pick or top
        if best[c] != top:
            changed.append(f"{c}: {top} -> {best[c]}")
    with open(out + ".changes.txt", "w", encoding="utf-8") as f:
        f.write(chr(10).join(changed))
    print(f"用 NVDA 的 {from_nvda} 个字；{len(changed)} 条换词记录，见 {out}.changes.txt")
    lines = [c + best[c] for c in chars if c in best]
    body = "\n".join(lines)
    chunks = [body[i:i + CHUNK] for i in range(0, len(body), CHUNK)]
    # 保证切分点在行尾
    fixed, buf = [], ""
    for ch in chunks:
        buf += ch
        cut = buf.rfind("\n")
        if cut < 0:
            continue
        fixed.append(buf[:cut])
        buf = buf[cut + 1:]
    if buf:
        fixed.append(buf)
    with open(out, "w", encoding="utf-8") as f:
        f.write("package io.github.doubaoa11y.hook\n\n")
        f.write("/** 自动生成（tools/build_char_words.py，数据来自 NVDA 简体中文字符描述（GPL-2.0 或更高）、jieba 词频表和 pypinyin 读音（MIT），不要手改 */\n")
        f.write("internal object CharWordsData {\n    val parts = arrayOf(\n")
        for c in fixed:
            f.write('        "' + c.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("$", "\\$") + '",\n')
        f.write("    )\n}\n")
    print(f"{len(lines)} 个字 -> {out}")


if __name__ == "__main__":
    main()
