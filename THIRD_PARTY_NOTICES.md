# 第三方声明

## jieba 词典

`app/src/main/java/io/github/doubaoa11y/hook/CharWordsData.kt`（单字解释用的“字 → 常见词”表）由 `tools/build_char_words.py` 从 jieba 分词自带的词频表 `dict.txt` 生成。

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
