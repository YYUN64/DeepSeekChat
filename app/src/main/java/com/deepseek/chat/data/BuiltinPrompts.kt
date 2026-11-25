package com.deepseek.chat.data

/**
 * 内置系统提示词（不污染用户在设置里填的内容，发送前拼接）。
 */
object BuiltinPrompts {

    /** 用户自己的系统提示词里出现这些词，说明他就是想要 LaTeX，内置规则让位 */
    private val USER_WANTS_LATEX = Regex("(?i)latex|公式")

    const val MATH_RULE = """【数学输出规则】数学公式请用 LaTeX：行内公式用 \(...\)，独立公式用 ${'$'}${'$'}...${'$'}${'$'} 或 \[...\]。矩阵/行列式请用 \begin{pmatrix}...\end{pmatrix} 或 \begin{vmatrix}...\end{vmatrix}，不要用代码块画矩阵。其余文字用正常中文。"""

    /** 把内置数学规则拼到用户系统提示词后面；用户明确要 LaTeX 时原样返回 */
    fun compose(userPrompt: String): String {
        val user = userPrompt.trim()
        if (user.isNotEmpty() && USER_WANTS_LATEX.containsMatchIn(user)) return user
        return if (user.isEmpty()) MATH_RULE else user + "\n\n" + MATH_RULE
    }
}
