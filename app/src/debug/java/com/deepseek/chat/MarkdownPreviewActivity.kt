package com.deepseek.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.deepseek.chat.ui.MarkdownText
import com.deepseek.chat.ui.theme.DeepSeekTheme

/**
 * 仅 debug 包含的 Markdown 渲染检查页，用来截图核对块级/行内语法。
 * release 包不包含这个 Activity。
 */
class MarkdownPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeepSeekTheme(darkTheme = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        MarkdownText(text = CORPUS)
                    }
                }
            }
        }
    }
}

private val CORPUS = """
# 一级标题 H1
## 二级标题 H2
### 三级标题 H3

普通段落，带 **粗体**、*斜体*、***粗斜体***、`行内代码`、[链接文字](https://example.com)、~~删除线~~ 和裸链接 https://example.com/path?q=1。

**「重点」**中文引号旁的粗体，**「另一个重点」**。

1. 有序列表第一项
2. 有序列表第二项，带 `code` 和 **粗体**
   1. 嵌套有序 2.1
   2. 嵌套有序 2.2
3. 有序列表第三项

- 无序第一项
- 无序第二项
  - 嵌套无序 2.1
  - 嵌套无序 2.2
    - 三级无序 2.2.1
- 无序第三项

> 引用第一行，带 **粗体**
> 引用第二行，带 `代码`
>> 嵌套引用的一行

```kotlin
fun main() {
    val values = listOf(1, 2, 3)
    println(values.sum())
}
```

| 条件 | PCWrite | IF_ID_Write | IF_ID_Flush | ID_EX_Flush | PC source |
|---|---|---|---|---|---|
| Normal | 1 | 1 | 0 | 0 | PC+4 |
| Load-use stall | 0 | 0 | 0 | 0 | PC hold |
| Branch taken | 1 | X | 1 | 1 | branch target |
| Jump taken | 1 | X | 1 | 1 | jump target |

---

| 短表 | 值 |
|---|---|
| 甲 | 1 |
| 乙 | 2 |

#1 这行不是标题（井号后没空格）

> 没有空行结束的引用

## LaTeX 兜底（模型仍输出公式时）

\[
A=\begin{pmatrix}
1 & 2 & 3\\
4 & 5 & 6\\
7 & 8 & 10
\end{pmatrix}
\]

行内 \(x^2\) 与 $\frac{a}{b}$，还有 \text{速度} 和 $$\int_0^1 x dx$$ 的单行写法。

\begin{vmatrix}
a & b\\
c & d
\end{vmatrix}

\begin{aligned}
x &= 1 + 2\\
y &= 3 \times 4
\end{aligned}
""".trimIndent()
