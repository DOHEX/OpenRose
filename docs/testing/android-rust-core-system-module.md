# 小米 14 系统模块加载 Rust 核心的试验步骤

**门槛已于 2026-11-27 实测通过（见文末[实测结果](#实测结果)）；热重载拒绝核对尚未实测，未验证。** 本页是 [ADR 0038](../adr/0038-rust-core-and-nsh-desktop.md) 的 Android 切换门槛（#167）的操作步骤与判据：系统模块在 `com.android.bluetooth` 内把 `libjnidispatch.so` 与 `libopenrose_ffi.so` 从模块 APK 加载进来，用 Rust 控制入口完成一次 i5 连接与读数，且 SELinux 与 linker 没有拒绝。离线测试只覆盖开关与控制方式判定、仅选择 `I5_ZT` 目标及热重载拒绝，不证明库能在系统蓝牙进程里加载。

门槛失败时（ADR 0038）：控制会话移到应用进程，模块只转发 GATT／SPP 原语，并重开 [ADR 0011](../adr/0011-dual-host-single-control-session.md)。

## 试验对象与前提

| 项 | 要求 |
| --- | --- |
| 手机 | 小米 14，HyperOS，已 root；LSPosed 已启用 OpenRose 模块，作用域勾选系统蓝牙（`com.android.bluetooth`）。 |
| 耳机 | EarFree i5（`I5_ZT`），已与手机经典蓝牙配对；Linux 桌面、另一部手机等没有持有它的 LE 链路（参见 [android-i5-control-regressions.md](android-i5-control-regressions.md) 中 Linux 回连 LE 的复现）。试验只会挑选已识别为 `I5_ZT` 的耳机，已配对的其他型号（例如 i7）或未识别设备会被忽略。 |
| 控制方式 | 应用控制（默认），后台控制关闭。试验只在未选择模块控制时启动；若选择模块控制，试验会跳过加载与启动（`trial_skipped reason=module_control_selected`），运行中切到模块控制会停止试验会话（`trial_stopped reason=module_control_selected`），确保 `com.android.bluetooth` 内只有一个控制会话（[ADR 0011](../adr/0011-dual-host-single-control-session.md)）。 |
| 电脑 | `adb`；按 [CONTRIBUTING.md](../../CONTRIBUTING.md) 配好 Rust 与 `cargo-ndk`，`./gradlew :androidApp:assembleDebug` 可构建。 |
| 开关 | `androidIntegration.native_core_trial`，默认 `false`。它与其他集成设置同属 `HostSettings`，由应用经 `module_config` 远程偏好发布给模块；界面没有入口，下面用 `run-as` 写应用的偏好文件。 |

试验自己的输出只用日志标签 `OpenRoseNativeTrial`，热重载拒绝的一行除外，它用模块的标签 `OpenRoseSystemModule`；JNA 的加载调试输出在 `System.err`。

## 步骤

| 步 | 操作 | 预期观察 |
| --- | --- | --- |
| 1 | `./gradlew :androidApp:assembleDebug`，记下 `sha256sum androidApp/build/outputs/apk/debug/androidApp-debug.apk`。`adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`。 | 安装成功。`adb shell pm path io.github.dohex.openrose` 给出的 APK 与本地哈希一致（`adb shell sha256sum <路径>`）。 |
| 2 | 开一个终端抓日志，整个试验期间保持：`adb logcat -c && adb logcat -b all -v threadtime \| tee trial.log`。 | 开始滚动。 |
| 3 | 在应用状态页点右上角的「重启作用域」，让新模块代码加载（[ADR 0025](../adr/0025-integration-scope-root-restart.md)）。没有界面时的 Root 替代：`adb shell su -c 'kill -TERM $(pidof com.android.bluetooth)'`。 | 系统蓝牙进程换了新 pid，蓝牙短暂中断后恢复。 |
| 4 | 过滤 `OpenRoseNativeTrial`，等用户解锁后的第一次配置读取。 | `switch value=false`。这一行证明新代码已在 `com.android.bluetooth` 内运行并读到了配置。其后没有 `trial_start`。 |
| 5 | 强停应用并打开开关：`adb shell am force-stop io.github.dohex.openrose`，再执行 `adb shell run-as io.github.dohex.openrose sh -c "grep -v native_core_trial shared_prefs/openrose.xml \| grep -v '</map>' > shared_prefs/openrose.xml.new; echo '    <string name=\"androidIntegration.native_core_trial\">true</string>' >> shared_prefs/openrose.xml.new; echo '</map>' >> shared_prefs/openrose.xml.new; mv shared_prefs/openrose.xml.new shared_prefs/openrose.xml"`。先用 `adb shell run-as io.github.dohex.openrose cat shared_prefs/openrose.xml` 确认文件存在并以 `<map>` 开头；不存在时先打开一次应用并改动任一设置。 | `cat` 的结果含 `androidIntegration.native_core_trial` 的值 `true`，其余条目不变。 |
| 6 | `adb shell am start -n io.github.dohex.openrose/.MainActivity` 打开应用，让它把设置发布到 `module_config`，看到右列的日志后立刻 `adb shell am force-stop io.github.dohex.openrose`，避免应用的应用控制抢占 i5。 | `switch value=true`。若此时仍选着模块控制，会看到 `trial_skipped reason=module_control_selected` 而不启动试验；若已选应用控制，试验可能已在这个进程里启动，这一段的结果也计入失败证据，但判定以第 8 步为准。 |
| 7 | 再次「重启作用域」（或第 3 步的 Root 命令）。应用保持强停状态。 | 系统蓝牙进程换新 pid。 |
| 8 | 在新 pid 内观察 `OpenRoseNativeTrial`（`adb logcat -b all -v threadtime --pid=$(adb shell pidof com.android.bluetooth)` 或 `-s OpenRoseNativeTrial:V System.err:W`），最长等 2 分钟。 | 见下一节的行序。 |
| 9 | 附加核对：保持试验在运行，`adb install -r` 重装同一 APK。 | 模块不热重载：系统蓝牙 pid 不变，没有第二个 `trial_start`；在 LSPosed 管理器里手动请求重载时，模块日志出现 `hot_reload_refused`（标签 `OpenRoseSystemModule`，经 Xposed 日志写出，LSPosed 管理器的日志页也能看到）。再「重启作用域」后新代码才生效。 |
| 10 | 收尾：把偏好里的值改回 `false`（同第 5 步，把 `true` 换成 `false`），打开应用发布，再「重启作用域」。 | 新 pid 的第一行是 `switch value=false`，没有 `trial_start`。 |

关闭开关在运行中不生效：已加载的库不能卸载，试验会继续，日志出现 `switch_off_ignored_until_scope_restart`，重启作用域后才真正关闭。若在试验运行中切到模块控制，试验会立即停止其控制会话并记录 `trial_stopped reason=module_control_selected`，但已加载的库仍要等重启作用域才会卸载。

## 预期日志

第 8 步的新 pid 内，`OpenRoseNativeTrial` 应按下面的顺序出现。方括号内是可变部分。

| 行（节选） | 含义与判读 |
| --- | --- |
| `switch value=true` | 配置读到了开关。 |
| `trial_start` | 开始加载。 |
| `environment module_source=[…/base.apk] source_readable=true module_native_dir=[…] extract_native_libs=false abis=arm64-v8a,… sdk=[…] uid=1002 pid=[…] selinux=[u:r:…] classloader=[…]` | 加载环境。`source_readable=false` 说明系统蓝牙进程读不到模块 APK；`extract_native_libs=false` 与 `useLegacyPackaging = false` 的打包一致，为 `true` 说明库被解包到了 `module_native_dir`；`selinux=` 是进程自己的域，用来对照 `avc` 行的 `scontext`。 |
| `native_library_resolve library=jnidispatch path=[…/base.apk!/lib/arm64-v8a/libjnidispatch.so]` | 模块类加载器给出的库路径。`path=null` 说明类加载器的库搜索路径里没有它，随后多半会 `not found`。 |
| `native_library_loaded library=jnidispatch` | `System.loadLibrary` 成功。 |
| `native_library_resolve library=openrose_ffi path=[…/libopenrose_ffi.so]` 与 `native_library_loaded library=openrose_ffi` | 同上。 |
| `bonded_headsets ids=[已配对耳机地址列表]` | Rust 核心经 `AndroidAudioProfiles` 读到已配对耳机，试验仅从中挑选 `I5_ZT` 作为连接目标。蓝牙未开或列表中没有 `I5_ZT` 时出现 `bonded_i5_missing attempt=n/12 transport=[…] bonded=[…]`，每 5 秒一次，12 次后 `trial_gave_up reason=no_bonded_i5`。 |
| `connect_start headset=[…] name=[…] profile=I5_ZT` | 开始连接。 |
| `state revision=[…] connection=connecting … phase=[CONNECTING、DISCOVERING、SUBSCRIBING、INITIALIZING]` | 经 `AndroidGattDriver` 走完 GATT 各阶段。 |
| `state revision=[…] connection=connected headset=[…] session=[…] profile=I5_ZT` | 控制会话就绪。 |
| `reading field=LEFT_BATTERY value=Known(…)`，另有 `RIGHT_BATTERY`、`NOISE_MODE`、`FIRMWARE` 等 | 耳机读回的数据；值含 `Known(` 才是读到了。 |
| `connect_result headset=[…] result=success` | `connect` 返回成功。失败时为 `result=failure code=[…] message=[…]`。 |

JNA 自己的加载过程在 `System.err`（W 级）：试验打开了 `jna.debug_load`，并设了 `jna.nounpack=true`，所以 JNA 不会把库解包到临时目录，`jnidispatch` 由试验先行加载。

## 读 `avc: denied` 与 linker 行

收集：

| 目的 | 命令 |
| --- | --- |
| 全部缓冲区里与试验相关的行 | `adb logcat -b all -d \| grep -E 'OpenRoseNativeTrial\|dlopen failed\|UnsatisfiedLinkError\|libopenrose_ffi\|libjnidispatch'` |
| SELinux 拒绝 | `adb logcat -b all -d \| grep -E 'avc: +denied'`；内核环形缓冲区另用 `adb shell su -c 'dmesg \| grep -E "avc: +denied"'`。 |
| 进程崩溃与终止 | `adb logcat -b crash -d`；`adb logcat -b events -d \| grep -E 'am_crash\|am_proc_died'`；`adb shell su -c 'ls -lt /data/tombstones \| head'`。 |
| 当前系统蓝牙 pid | `adb shell pidof com.android.bluetooth`。试验期间换了 pid 就是进程被终止过。 |

`avc: denied { map } for path="…" dev="…" ino=… scontext=u:r:[域]:s0 tcontext=u:object_r:[标签]:s0 tclass=file permissive=0` 各字段：

| 字段 | 判读 |
| --- | --- |
| `{ … }` | 被拒绝的操作。`map`、`execute`、`open`、`read` 都与加载共享库有关。 |
| `path=` 或 `name=` | 被访问的文件。落在模块 APK（`…/io.github.dohex.openrose-…/base.apk`）、`libopenrose_ffi.so` 或 `libjnidispatch.so` 上就是本试验的拒绝。 |
| `scontext=` | 发起者的域。与 `environment` 行的 `selinux=` 相同才是系统蓝牙进程；其他域的拒绝与本试验无关。 |
| `tcontext=`、`tclass=` | 目标的标签与类别，写进 #167 的记录，不要自行放宽策略。 |
| `permissive=` | `0` 是强制拒绝，判失败；`1` 只记录，仍要写进记录。 |

linker 报错出现在 `UnsatisfiedLinkError` 的消息里（`native_library_load_failed library=[名]` 之后紧跟异常栈）：

| 消息 | 通常说明 |
| --- | --- |
| `dlopen failed: library "[名].so" not found` | 类加载器的库搜索路径里没有，或 APK 中没有这个 ABI 的库。核对 `native_library_resolve` 的 `path=` 与 `unzip -v` 里的 `lib/arm64-v8a`。 |
| `dlopen failed: couldn't map "…!/lib/arm64-v8a/…so" segment N: Operation not permitted` 或 `Permission denied` | 从 APK 内映射被拒绝。立刻查同一时间的 `avc: denied { map }`；没有 `avc` 时再核对库是否未压缩（`Stored`）且按页对齐。 |
| `library "…" is not accessible for the namespace "…"` | 链接器命名空间不允许这个路径或依赖库。记下命名空间名与路径。 |
| `has bad ELF magic`、`is 32-bit instead of 64-bit`、`cannot locate symbol` | 库与进程 ABI 或系统库不匹配。 |
| `program alignment (…) cannot be smaller than system page size (…)` | 页对齐不满足。记下手机页大小（`adb shell getconf PAGE_SIZE`）。 |
| `Unable to load library 'openrose_ffi'`、`UniFFI … mismatch` | JNA 或绑定层失败，`native_library_load_failed` 不会出现，而是 `trial_control_start_failed` 带异常栈。 |

## 通过与失败

按 ADR 0038 的门槛，在第 8 步的同一个系统蓝牙 pid 内，下面四条全部满足才算通过：

| 判据 | 通过 | 失败 |
| --- | --- | --- |
| 加载 | 两条 `native_library_loaded`（`jnidispatch`、`openrose_ffi`）都出现。 | 任一条出现 `native_library_load_failed`、`trial_control_start_failed`，或没有 `trial_start`。 |
| 连接与读数 | 出现 `connection=connected … profile=I5_ZT`，且至少一条 `reading field=… value=Known(…)`（含电量），在 `connect_start` 后 2 分钟内。 | `connect_result … result=failure`，或超时仍停在 `connecting`，或只有 `Loading` 没有 `Known`。 |
| SELinux 与 linker | 从第 7 步到连接完成，没有属于系统蓝牙域、涉及模块 APK 或这两个库的 `avc: denied`，也没有 `dlopen failed`。 | 出现任何一条，不论 `permissive`。 |
| 进程 | 系统蓝牙 pid 在这一段内不变，`crash` 缓冲区与 `am_crash` 没有新条目，蓝牙开关与已有的耳机音频连接保持正常。 | pid 改变、崩溃或蓝牙不可用。 |

第 9 步的热重载拒绝是附加核对，不属于门槛，但它是 ADR 0038 对系统模块的要求，需要一并记录。

## 记录

实测后在本页补一个「实测结果」小节，不改上面的步骤。按 [CONTRIBUTING.md](../../CONTRIBUTING.md) 的要求写明：耳机型号与固件、手机型号与系统版本、LSPosed 版本、OpenRose 版本与 APK 的 SHA-256、每一步的实际观察，再把 `trial.log` 里的关键行、`avc`／linker 的原文和 pid 变化附上。没有做的步骤写“未验证”。结论为失败时，在 #167 记下失败的判据与原文，由 ADR 0038 的失败处理重开 ADR 0011。

## 实测结果

### 测试环境

| 项 | 实际值 |
| --- | --- |
| 日期 | 2026-11-27 |
| 手机与系统 | 小米 14（`23127PN0CC` / `houji`），Android 17（SDK 37），HyperOS `OS4.0.0.27.XNCCNXM`（`Xiaomi/houji/houji:17/CP2A.260605.016/OS4.0.0.27.XNCCNXM:user/release-keys`） |
| 耳机与固件 | ROSE EARFREE i5（`I5_ZT`），固件 `125`（`[1, 2, 5]`） |
| OpenRose 版本 | `0.1.1`（合入构建期修补后的 debug APK SHA-256：`359bcf9ba7afe667d054dbf655e2fa3d5539d740988632462dc310138c4df661`） |
| LSPosed / 宿主上下文 | `LspModuleClassLoader`，`com.android.bluetooth`，`uid=1002`，SELinux `u:r:bluetooth:s0`，`extract_native_libs=false` |

### 发现与绕路（#179）

首次使用 `net.java.dev.jna:jna:5.19.1@aar` 原版 `lib/arm64-v8a/libjnidispatch.so` 实测时，两个原生库均可经 `LspModuleClassLoader` 从 `base.apk!/lib/arm64-v8a/` 正常 `dlopen`，无 SELinux / linker 拒绝；但在进入 JNA / `libffi` 回调路径时，`com.android.bluetooth` 发生 `SIGSEGV`。反汇编确认上游 `libjnidispatch.so` 中的 `ffi_closure_trampoline_table_page` 使用了 Android arm64 保留给 Shadow Call Stack 的 `x18` 寄存器（根因为 `native/libffi/src/aarch64/ffitarget.h` 对 Android 未禁用 `FFI_EXEC_STATIC_TRAMP`）。

以 JNA `5.19.1` 源码对 `ffitarget.h` 的 `#elif !defined(_WIN32)` 补上 `&& !defined(__ANDROID__)`，并配合 NDK `28.2.13676358`、`--disable-exec-static-tramp`、`-ffixed-x18`、`-Wl,-z,max-page-size=16384` 重新编译 `lib/arm64-v8a/libjnidispatch.so`（`llvm-objdump` 确认 0 条 `x18`/`w18` 引用，`llvm-readelf` 确认全部 `LOAD` 段按 `0x4000` 对齐）后复测通过。该构建期绕路已合入 `build-logic/src/main/kotlin/PatchedJna.kt` 并在 #179 跟踪移除。

### 门槛判据与关键日志

| 判据 | 结果 | 观察摘要 |
| --- | --- | --- |
| 加载 | **通过** | `native_library_loaded library=jnidispatch` 与 `native_library_loaded library=openrose_ffi` 均出现，均直接从 `base.apk!/lib/arm64-v8a/` 加载。 |
| 连接与读数 | **通过** | `CONNECTING` → `DISCOVERING` → `SUBSCRIBING` → `INITIALIZING` → `connected (headset=D1:02:03:04:14:87, profile=I5_ZT)`，约 2 秒内完成全量读数（`LEFT_BATTERY=93`、`RIGHT_BATTERY=92`、`CASE_BATTERY=NotProvided(ff)`、`NOISE_MODE=wind`、`EQ=hifi`、`AUDIO_MODE=ldac`、`FIRMWARE=125` 等）并返回 `connect_result ... result=success`。 |
| SELinux 与 linker | **通过** | `u:r:bluetooth:s0` 下无针对模块 APK 或两库的 `avc: denied`，无 `dlopen failed` / `UnsatisfiedLinkError`。 |
| 进程稳定性 | **通过** | `com.android.bluetooth` PID 全程保持 `29915`，无 `SIGSEGV` / `FATAL EXCEPTION`；还原 `control_way=module` 后触发 `trial_stopped reason=module_control_selected` 干净退出。 |
| 第 9 步热重载拒绝 | **未验证** | 本次试验未执行第 9 步 LSPosed 模块热重载测试。 |

关键日志摘录（PID `29915`）：

```text
11-27 17:41:32.786 I/OpenRoseNativeTrial(29915): switch value=true
11-27 17:41:32.786 I/OpenRoseNativeTrial(29915): trial_start
11-27 17:41:32.788 I/OpenRoseNativeTrial(29915): native_library_resolve library=jnidispatch path=/data/app/.../base.apk!/lib/arm64-v8a/libjnidispatch.so
11-27 17:41:32.790 I/OpenRoseNativeTrial(29915): native_library_loaded library=jnidispatch
11-27 17:41:32.791 I/OpenRoseNativeTrial(29915): native_library_resolve library=openrose_ffi path=/data/app/.../base.apk!/lib/arm64-v8a/libopenrose_ffi.so
11-27 17:41:32.792 I/OpenRoseNativeTrial(29915): native_library_loaded library=openrose_ffi
11-27 17:41:32.954 I/OpenRoseNativeTrial(29915): reading field=LEFT_BATTERY value=Known(value=Number(v1=93), rawHex=5d)
11-27 17:41:32.954 I/OpenRoseNativeTrial(29915): reading field=RIGHT_BATTERY value=Known(value=Number(v1=92), rawHex=5c)
11-27 17:41:33.666 I/OpenRoseNativeTrial(29915): reading field=FIRMWARE value=Known(value=Firmware(v1=FirmwareVersion(parts=[1, 2, 5], displayText=125)), rawHex=010205)
11-27 17:41:34.663 I/OpenRoseNativeTrial(29915): state revision=36 connection=connected headset=D1:02:03:04:14:87 session=2 profile=I5_ZT
11-27 17:41:37.882 I/OpenRoseNativeTrial(29915): connect_result headset=D1:02:03:04:14:87 result=success
11-27 17:41:54.746 I/OpenRoseNativeTrial(29915): trial_stopped reason=module_control_selected
```
