# 第三方声明

## NVDA 简体中文字符描述

单字解释的词（“高，高低的高”）主要取自 NVDA 的简体中文字符描述文件 `source/locale/zh_CN/characterDescriptions.dic`，由 `tools/build_char_words.py` 转换并做了同音词筛选（有同音歧义的词换成 jieba 挑的词，他、她、它用固定读法）。

- 项目：https://github.com/nvaccess/nvda （NonVisual Desktop Access）
- 版权：Copyright (C) 2010-2025 NVDA Simplified Chinese team All volunteers
- 许可：GNU General Public License 第 2 版或更高版本。本项目按 GPL-3.0 使用。

## pypinyin

`tools/build_char_words.py` 用 pypinyin 判断同音词（只在生成词表时用，不打包进应用）。

- 项目：https://github.com/mozillazg/python-pinyin
- 许可：MIT

## jieba 词典

`app/src/main/java/io/github/doubaoa11y/hook/CharWordsData.kt`（单字解释用的“字 → 常见词”表）里，NVDA 没有收录或有同音歧义的字，由 `tools/build_char_words.py` 从 jieba 分词自带的词频表 `dict.txt` 挑词。

- 项目：https://github.com/fxsjy/jieba
- 许可：MIT

```
The MIT License (MIT)

Copyright (c) 2013 Sun Junyi

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## Gradle Wrapper

`gradlew`、`gradlew.bat`、`gradle/wrapper/` 来自 Gradle，Apache License 2.0。

## 豆包输入法

本项目不包含豆包输入法的任何代码、图片或资源。代码里出现的类名、方法名、资源 id 只用于定位要 hook 的位置。“豆包”“豆包输入法”是其权利人的商标。
