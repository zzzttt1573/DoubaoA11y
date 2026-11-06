"""
生成单字解释表：给每个常用汉字挑一个最常见的词，朗读时读成“高，高兴的高”。

数据来源：jieba 分词自带的词频表 dict.txt（MIT 许可，开放数据）。
用法（需要 pip install jieba）：
    python build_char_words.py <输出的 Kotlin 文件>
"""
import os
import sys
from collections import defaultdict

import jieba

MAX_CHARS = 9000      # 收录的字数（按字频）
CHUNK = 6000          # Kotlin 单个字符串常量不能太长，分段


def is_cjk(c):
    return "\u4e00" <= c <= "\u9fff"


def main():
    out = sys.argv[1]
    path = os.path.join(os.path.dirname(jieba.__file__), "dict.txt")
    char_freq = defaultdict(int)
    best = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if len(parts) < 2:
                continue
            w, freq = parts[0], int(parts[1])
            if not all(is_cjk(c) for c in w):
                continue
            for c in set(w):
                char_freq[c] += freq
            if len(w) not in (2, 3):
                continue
            for c in set(w):
                if w.count(c) != 1:
                    continue
                # 两字词优先，同长度取词频高的
                score = (1 if len(w) == 2 else 0, freq)
                if c not in best or score > best[c][0]:
                    best[c] = (score, w)
    chars = sorted((c for c in best), key=lambda c: -char_freq[c])[:MAX_CHARS]
    lines = [c + best[c][1] for c in chars]
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
        f.write("/** 自动生成（tools/build_char_words.py，数据来自 jieba 词频表，MIT 许可），不要手改 */\n")
        f.write("internal object CharWordsData {\n    val parts = arrayOf(\n")
        for c in fixed:
            f.write('        "' + c.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n").replace("$", "\\$") + '",\n')
        f.write("    )\n}\n")
    print(f"{len(lines)} 个字 -> {out}")


if __name__ == "__main__":
    main()
