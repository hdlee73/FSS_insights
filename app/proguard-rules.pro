-keepattributes SourceFile,LineNumberTable

# pdfbox-android: 선택 사항인 JPEG2000 디코더가 없어도 빌드되게 한다.
-dontwarn com.gemalto.jp2.**
-dontwarn com.tom_roush.pdfbox.**
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }

# 법령 기능(Retrofit): suspend 함수와 제네릭 시그니처 보존
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep interface com.hdlee73.everylaw.core.data.remote.LawApiService { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
