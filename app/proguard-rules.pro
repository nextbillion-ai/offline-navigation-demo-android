# Navigation SDK JNI/reflection keep rules are bundled as AAR consumer rules.
# Do not add a blanket SDK keep rule: r8Smoke validates normal consumer minification.
-keep class ai.nextbillion.offlinedemo.OfflineNavigationApplication { public <init>(); }
