-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class com.salvia.salviabrowxer.browser.BrowserHost$DetectorBridge {
    <methods>;
}
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
