# Session Notes — Reverse Engineering S Pen APK

## How to download JADX and decompile any APK (step by step)

These are the exact steps used in our session to decompile `com.samsung.android.service.aircommand`.

---

### Step 1 — Download JADX (Java decompiler)

JADX converts `.apk` / `.dex` files into readable Java source code.

```bash
mkdir -p /tmp/spen_re && cd /tmp/spen_re

# Download jadx release zip from GitHub (v1.5.0)
curl -L -o jadx.zip "https://github.com/skylot/jadx/releases/download/v1.5.0/jadx-1.5.0.zip"

# Extract
unzip -q jadx.zip -d jadx_tool
chmod +x jadx_tool/bin/jadx
```

**Why jadx?**
- Converts `.dex` bytecode → readable Java (better than `apktool` for code analysis)
- `apktool` gives you smali (low-level) + resources — useful for XML/assets
- jadx gives you Java classes → easier to understand and hook

---

### Step 2 — Install Java (required by jadx)

jadx needs a JDK to run. On this Replit environment:

```bash
# Install JDK via nix
nix-env -iA nixpkgs.jdk

# Java won't be in PATH immediately — source the nix profile:
source ~/.nix-profile/etc/profile.d/nix.sh

# Or reference it directly:
~/.nix-profile/bin/java -version
# → openjdk version "16" (NixOS)
```

**Why not just `apt install java`?**
Replit uses NixOS — `apt` doesn't exist. Use `nix-env` instead.

---

### Step 3 — Download the APK

APKMirror blocks automated downloads with Cloudflare. **Use APKPure instead:**

```bash
cd /tmp/spen_re

curl -L -A "Mozilla/5.0 (Linux; Android 13; SM-S918B)" \
  "https://d.apkpure.com/b/APK/com.samsung.android.service.aircommand?version=latest" \
  -o aircommand.apk

# Check it's a real APK (not an HTML error page)
file aircommand.apk
# → should say: Java archive data (JAR)
# → if it says: HTML document → the download was blocked, try a different URL
ls -lh aircommand.apk
# → should be ~20MB for aircommand
```

---

### Step 4 — Decompile with JADX

```bash
cd /tmp/spen_re

~/.nix-profile/bin/java -jar jadx_tool/lib/jadx.jar \
  --output-dir ./decompiled aircommand.apk

# OR use the wrapper script:
./jadx_tool/bin/jadx --output-dir ./decompiled aircommand.apk

# Output: decompiled/sources/ (Java files) + decompiled/resources/
```

Decompile time: ~30 seconds for a 21MB APK.

---

### Step 5 — Explore the decompiled output

```bash
# List all top-level packages
ls /tmp/spen_re/decompiled/sources/

# Count total packages
ls /tmp/spen_re/decompiled/sources/ | wc -l

# Samsung-named classes (not obfuscated)
ls /tmp/spen_re/decompiled/sources/com/samsung/android/service/aircommand/ -R

# Find classes by keyword
grep -rl "BluetoothGatt\|BLE\|bluetooth" /tmp/spen_re/decompiled/sources/ | grep -v "androidx"
grep -rl "startActivity\|Intent"         /tmp/spen_re/decompiled/sources/ | grep -v "androidx"
grep -rl "Settings.System\|SharedPrefs"  /tmp/spen_re/decompiled/sources/ | grep -v "androidx"
```

---

## What we found in `com.samsung.android.service.aircommand` v7.6.34.7

### Obfuscated class map

| Obfuscated class | Original name (from comments) | What it does |
|---|---|---|
| `a5.l` | `AirCommandMainController` | Main controller for Air Command |
| `a5.g0` | `FloatingIconController` | The floating S Pen icon |
| `a5.i0` | `Launcher` | Opens apps from Air Command shortcuts |
| `a6.a` | `BleSpenUuid` | All BLE GATT UUIDs |
| `p7.e` | `RemoteSpenMainController` | BLE remote S Pen controller |
| `u6.a` | `BleSpenGenericDriver` | Low-level BLE GATT driver |
| `v6.l` | `BleSpenButtonEvent` | Button event types (single/double/long) |
| `i6.c` | `BleSpenSensorActionDetector` | Converts BLE events → gestures |
| `i6.d` | `BleSpenButtonActionDetector` | Click timing logic |
| `g7.b` | `PenActionTriggerType` | All trigger types (button + gesture) |
| `r8.c` | `ModelFeatures` | Detects S Pen model + capabilities |
| `z7.j` | `SettingsPreferenceManager` | Reads/writes remote spen settings |
| `t8.b` | `SemFloatingFeatureWrapper` | Samsung feature flag wrapper |

### BLE UUIDs (from `a6/a.java` = BleSpenUuid.java)

| UUID name | UUID value |
|---|---|
| `UUID_BUTTON_EVENT` ⭐ | `6c290d2e-1c03-aca1-ab48-a9b908bae79e` |
| `UUID_BATTERY_LEVEL` | `5a87b4ef-3bfa-76a8-e642-92933c31434f` |
| `UUID_BATTERY_LEVEL_RAW` | `5b87b4ef-3bfa-76a8-e642-92933c31435f` |
| `UUID_RAW_SENSOR_DATA` | `DDB42396-CA00-4DB3-B87D-2EE458279360` |
| `UUID_PEN_TIP_APPROACH` | `47cd9300-cf99-4899-be73-59c7f1c0557c` |
| `UUID_LED_STATE` | `2287a97c-17d7-4845-ace9-1f42f4bd034a` |
| `UUID_MODE` | `aca1ab48-08ba-e79e-ab48-a9b9e6429293` |
| `UUID_FW_VER` | `a1a6932a-2f2e-1c03-2e1c-03ac2e1c03ac` |
| `UUID_CHARGE_STATUS` | `92933c31-41d8-bda6-3c31-434fab48a9b9` |
| `UUID_PEN_FREQUENCY` | `740052ae-ac12-47f1-89c8-9f2e23588bca` |
| `UUID_OBFUSCATION_TABLE` | `ade287e3-256c-4e7d-b0c6-03c7e5bfc4c1` |
| `UUID_PEN_SYNC_CLOCK` | `f641f992-3d9a-495f-9e07-19fe2418b867` |
| `UUID_EASY_CONNECT_ID` | `9659309c-0a26-48c9-b182-3161bda237cb` |
| `UUID_CHARACTERISTIC_CONFIG` | `00002902-0000-1000-8000-00805f9b34fb` |

### Button event types (from `v6/l.java` = BleSpenButtonEvent.java)

```java
enum ButtonEventType {
    BUTTON_DOWN, BUTTON_UP,
    SINGLE_CLICKED, DOUBLE_CLICKED,
    LONG_CLICK_STARTED, LONG_CLICK_FINISHED,
    DOUBLE_CLICK_HOLD_STARTED, DOUBLE_CLICK_HOLD_FINISHED
}
enum ButtonType { PRIMARY, SECONDARY }
```

### Gesture types (from `g7/b.java` = PenActionTriggerType.java)

```
SINGLE_CLICK, DOUBLE_CLICK, LONG_CLICK,
SECONDARY_SINGLE_CLICK, SECONDARY_DOUBLE_CLICK, SECONDARY_LONG_CLICK,
GESTURE_UP, GESTURE_DOWN, GESTURE_LEFT, GESTURE_RIGHT,
GESTURE_SHAKE, GESTURE_CIRCLE_CW, GESTURE_CIRCLE_CCW,
GESTURE_POINTY_UP, GESTURE_POINTY_DOWN, GESTURE_POINTY_LEFT
```

---

## BLE Remote S Pen — why it fails on custom ROMs

`RemoteSpenService.j(Context)` checks THREE gates before starting:

```
Gate 1: SemFloatingFeature.getBoolean("SEC_FLOATING_FEATURE_COMMON_SUPPORT_BLE_SPEN")
        → on custom ROM: floating_feature.xml missing this key → false → STOP

Gate 2: Settings.System.getInt(resolver, "spen_air_action") == 1
        → Settings UI toggle hidden (because Gate 1 failed) → key stays 0 → STOP

Gate 3: Paired pen count check
        → if pen was paired once this passes
```

Fix: `BleSpenEnableHook.java` in this repo hooks all three gates to always return true.

---

## Why we had trouble at first — lessons learned

| Problem | What happened | Fix |
|---|---|---|
| jadx script not executable | `jadx_tool/bin/jadx` needs `chmod +x` | Add `chmod +x` after unzip |
| Java not in PATH after nix install | nix doesn't auto-update PATH | `source ~/.nix-profile/etc/profile.d/nix.sh` first |
| APKMirror download → HTML | Cloudflare blocks server-side curl | Use APKPure URL format instead |
| APK shows as 5.6K | Cloudflare challenge page, not real APK | Check `file apk_name.apk` — must say "Java archive data" |
| `/nix/store` find times out | Nix store has thousands of paths | Use `ls ~/.nix-profile/bin/java` instead of `find /nix` |

