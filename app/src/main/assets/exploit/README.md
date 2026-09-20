# IonStack exploit for Meta Quest 3

Root exploit for Meta Quest 3, adapted from IonStack (CVE-2026-43499) in [CyberMeowfia](https://github.com/NebuSec/CyberMeowfia).

# Use at your own risk!!!


## Device Info

| Item | Value |
|------|-------|
| Device | Meta Quest 3 |
| Architecture | aarch64 |
| Kernel | `Linux localhost 5.10.240-g69827d40d782 #1 SMP PREEMPT Mon Jun 1 13:01:51 PDT 2026 aarch64 Toybox` |
| Incremental | `52168470043600520` |
| mm_struct | order-2 |

Kernels of similar versions are likely to work without re-adaptation.

## Usage

### 1. Obtain ionstack.conf

#### Pre-adapted version

If your kernel version matches the device info above, skip ionstack.conf.

#### Unadapted version (generate via GitHub Actions)

If your firmware version differs, you can auto-generate the config via GitHub Actions:

1. **Fork this repository.**
2. **Get your device's incremental number** via adb:
   ```sh
   adb shell getprop ro.build.version.incremental
   ```
3. **Download the matching firmware.** If you don't know the download URL, use the following (replace `{incremental}` with the value from the previous step):
   ```
   https://files.cocaine.trade/firmware/meta/Quest%203/q3_{incremental}.zip
   ```
4. **Run the Action:** In your forked repo, run the `generate-ionstack-config` workflow, fill in the firmware download URL, wait for completion, and download the generated `ionstack.conf`.

### 2. Obtain preload

#### Option A: Download from Releases

Download the precompiled `preload` binary from the [Releases](../../releases) page.

#### Option B: Build from source

Requires Android NDK. The recommended version is:
```
https://dl.google.com/android/repository/android-ndk-r29-linux.zip
```

After installing the NDK, build from the project directory:
```sh
make
```

### 3. Deploy and run

Push files to the device and execute:

```sh
# Push preload
adb push preload /data/local/tmp/

# Push ionstack.conf if your incremental differs from 52168470043600520
# (skip this step if your device matches the default incremental above)
adb push ionstack.conf /data/local/tmp/

# Make executable and run
adb shell chmod +x /data/local/tmp/preload
adb shell /data/local/tmp/preload
```

If everything works, you should get a root shell.

## Notes

- Do NOT modify any system partition, especially do not run any manager install commands. This can brick your device.
- Running the exploit may cause the Quest to hang. If this happens, long-press the power button to force reboot.
- The exploit has the highest success rate right after boot. A fresh reboot is recommended before running.



## Credits

- [CyberMeowfia](https://github.com/NebuSec/CyberMeowfia) — original IonStack (CVE-2026-43499) exploit
- [@zhuowei/cheese](https://github.com/zhuowei/cheese) — key adaptation info
- [kernelsnitch](https://github.com/lukasmaar/kernelsnitch) — kernel module
