# R8 / ProGuard 规则（release 已开启 isMinifyEnabled / isShrinkResources）

# JLaTeXMath 通过 Class.forName 注册可选的希腊/西里尔字母表，并大量使用内部类；
# 整包 keep，避免 R8 裁掉后公式渲染静默失败（APK 约 +200KB）。
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**
-dontwarn ru.noties.jlatexmath.**
