package com.deepseek.chat.data

/**
 * 内置系统提示词（不污染用户在设置里填的内容，发送前拼接）。
 */
object BuiltinPrompts {

    /** 用户自己的系统提示词里出现这些词，说明他就是想要 LaTeX，内置规则让位 */
    private val USER_WANTS_LATEX = Regex("(?i)latex|公式")

    const val MATH_RULE = """【数学输出规则】回答数学内容时不要使用 LaTeX：不要输出 ${'$'}${'$'}...${'$'}${'$'}、\[...\]、\(...\)、\begin{...} 等标记。请用纯文本 + Unicode 表达：× ÷ ≈ √ ∑ ∫ π，上标用 ^ 或 Unicode（x²），下标用普通字符（a1 或 a₁）。矩阵/行列式请放进等宽代码块，每行一个向量、用空格对齐，例如：
[1 2 3]
[4 5 6]
[7 8 10]
行内公式直接写普通文字，例如 det(A) = 1×(5×10−6×8) − 2×(4×10−6×7) + 3×(4×8−5×7)。"""

    /** 把内置数学规则拼到用户系统提示词后面；用户明确要 LaTeX 时原样返回 */
    fun compose(userPrompt: String): String {
        val user = userPrompt.trim()
        if (user.isNotEmpty() && USER_WANTS_LATEX.containsMatchIn(user)) return user
        return if (user.isEmpty()) MATH_RULE else user + "\n\n" + MATH_RULE
    }
}
