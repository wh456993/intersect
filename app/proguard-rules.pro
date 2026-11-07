# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# 保留行号与源文件名:release 开了 R8,不保留的话线上堆栈全是无行号
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 本工程没有反射/序列化的 keep 需求:持久化全部手写 org.json,枚举按自己的 key 字符串存取。
# 将来引入 Gson/Moshi/kotlinx.serialization 的反射路径,必须在这里补对应的 keep 规则。

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile