# kotlinx.serialization が生成する serializer を保持する
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class jp.naramed.campusplanpoc.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class jp.naramed.campusplanpoc.model.**$$serializer { *; }

# 注意:
# WebView から呼ばれる @JavascriptInterface メソッドは難読化で失われるため keep が必要になるが、
# 本 PoC では addJavascriptInterface を使わない方針なので、あえて keep ルールを置かない。
