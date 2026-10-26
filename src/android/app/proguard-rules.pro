# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# [T-android-vad-jni-keep / GH#250] RealTimeCutVAD's C++ layer calls back into
# VADWrapper BY NAME through JNI (GetMethodID "onVoiceStart" / "onVoiceEnd" /
# "onVoiceDidContinue"). Those three are PRIVATE Java methods with no Java-side
# caller, so R8 sees them as dead and renames them — verified in the shipped
# 1.12 release dex, where they had become b()V / c()V / d()V and the `callback`
# field was removed outright. The native lookup then fails with
#
#   java.lang.NoSuchMethodError: no non-static method
#   "Lio/codeconcept/realtimecutvadlibrary/VADWrapper;.onVoiceStart()V"
#
# thrown from processAudio (a native method), which is exactly the reported
# crash: every release user who tapped the mic got "Voice detection failed"
# before any STT provider was even contacted.
#
# proguard-android-optimize.txt keeps `native` method DECLARATIONS and @Keep,
# but nothing protects an ordinary Java method that only native code invokes.
#
# Kept whole rather than member-scoped: the library is five classes, so the
# size cost is negligible, whereas a narrow rule that missed a member the
# native side also touches (the `vadInstance` handle, for one) would degrade
# into a subtler runtime failure.
#
# NOTE FOR VERIFICATION: debug builds don't minify, so this bug is invisible
# there. Any change here must be checked against an assembleRelease APK.
-keep class io.codeconcept.realtimecutvadlibrary.** { *; }

# [T-build-rclone-aar] The rclone aar is rebuilt on-device from the official
# release dex, which R8 had already minified — so the classes it references
# include that build's synthesised outlining holders, which do not exist here.
# `go.Seq$Ref` reaches them only while building its "Ref instantiated with a
# Go refnum" RuntimeException, so no working path needs them.
#
# THESE NAMES CHANGE WITH EVERY OFFICIAL RELEASE: 1.14 was T.a + b3.g,
# 1.15 is S.a. When R8 fails with "Missing class X (referenced from: void
# go.Seq$Ref.<init>…)", add that name — the AGP also writes it to
# app/build/outputs/mapping/<variant>/missing_rules.txt.
-dontwarn T.a
-dontwarn b3.g
-dontwarn S.a
