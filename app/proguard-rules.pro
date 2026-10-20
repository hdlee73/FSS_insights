-keepattributes SourceFile,LineNumberTable

# pdfbox-android: 선택 사항인 JPEG2000 디코더가 없어도 빌드되게 한다.
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
