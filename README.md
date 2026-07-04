# SPen Popup View — LSPosed Module

فتح اختصارات S Pen Air Command كـ **نافذة منبثقة** (Popup View) بدل ملء الشاشة.

## كيف يعمل

من خلال تحليل ملف APK الفعلي لـ Air Command (com.samsung.android.service.aircommand 7.6.34.7):

```
كلاس Launcher المبهم:  a5.i0
مسار الإطلاق الرئيسي: a5.i0.A(Context, shortcut, Intent)
                           └→ context.startActivity(intent)          [بدون ActivityOptions]
                        a5.i0.z(Context, Intent, UserHandle)
                           └→ startActivityAsUser via reflection
```

الموديول يعترض الإطلاق في **ثلاث طبقات** حتى لا يفوته أي مسار:

| الطبقة | Hook | الهدف |
|--------|------|--------|
| 1 | `a5.i0.*` كل دالة تحتوي Intent | الكلاس المبهم مباشرة |
| 2 | `ContextWrapper.startActivity(Intent, Bundle)` | المسار العادي |
| 3 | `Instrumentation.execStartActivity(...)` | أعمق نقطة قبل النظام |

في كل طبقة يُضاف:
```java
ActivityOptions.makeBasic().setLaunchWindowingMode(5) // WINDOWING_MODE_FREEFORM
// + مفاتيح Samsung الخاصة:
bundle.putBoolean("sem.activity.popupWindow", true)
```

## التركيب

1. ثبّت LSPosed على جهازك
2. ثبّت APK الموديول
3. فعّله في LSPosed واختر النطاق: `com.samsung.android.service.aircommand`
4. أعد تشغيل الجهاز

## البناء

```bash
./gradlew assembleRelease
```
APK الناتج: `app/build/outputs/apk/release/app-release-unsigned.apk`

## ملاحظات

- **الإصدار المحلَّل:** 7.6.34.7 (763407000)
- **الكلاس المبهم:** `a5.i0` — قد يتغير في إصدارات جديدة، الطبقات 2 و3 ستعمل دائماً
- `minSdk 28` مطلوب لـ `setLaunchWindowingMode()`
- اختُبر البنية على Samsung One UI (S Pen attached devices)
