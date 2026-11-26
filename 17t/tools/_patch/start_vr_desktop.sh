#!/system/bin/sh
# Rebuild the Huawei VR 3D desktop session on the glasses display.
#
# 2026-11-18 rev (aligned with verified working state):
#  - lib manifest fixed to the proven set: libvrsdk offset (fdeae5c2)
#    + libWalleAlg walle (b644310f) + libWalleDev orig (53e1ce65)
#    + hvrframework.dex orig (9fc23387). The old list restored vd/wallex
#    variants and would overwrite the verified working libs.
#  - setprop hvr.radius 0.032544 before every launch: SDK reads it at
#    VrGlassParams init to zero the per-eye toe-in (diff1/diff2) that
#    caused the 150~158px double image. Must be set BEFORE any am start.
#  - glasses displayId is DYNAMIC (was 4, re-enumerated as 55 after a
#    chain reset): re-probe before every am start; abort rather than fall
#    back to the phone display (display 0). Old fallback DID=1 removed.
#  - vr.running broadcast REMOVED: verified side effect -- it makes
#    VRService rebuild the display chain (glasses display dies, panel
#    re-enumerates under a new id, VRPrepareActivity respawns). Never send.
#  - VRPrepareActivity stays on the phone display; it does not affect the
#    glasses output, so we only log its presence.
#  - 2026-11-26: skip the 6DoF controller-tutorial OOBE page. It waits for a
#    real controller button (BothController.IsAvailable=false in our setup)
#    and stayed stuck forever. Unity's Vr6DofBridge.isShow6DofOobe() reads
#    Settings.System NEED_SHOW_6DOF_OOBE / NEED_SHOW_6DOF_DETAILS_PAGE (default 1).
#    Setting them to 0 makes IsShow6DofOobe=False -> desktop goes straight to
#    HomeScene. Idempotent here so a factory reset of the setting cannot stick.
settings put system NEED_SHOW_6DOF_OOBE 0
settings put system NEED_SHOW_6DOF_DETAILS_PAGE 0
LOG=/data/local/tmp/vr_desktop.log
PKG=com.huawei.vrlauncherx
log() { echo "$(date '+%H:%M:%S') $*" >> "$LOG"; }
dump_disp() {
  echo "$(date '+%H:%M:%S') [DISP $1]" >> "$LOG"
  dumpsys window displays 2>/dev/null | grep -E "Display: mDisplayId|mFocusedApp=" >> "$LOG"
}

probe_did() {
  DID=$(dumpsys window displays 2>/dev/null \
        | grep -o 'DisplayInfo{"HDMI[^"]*", displayId [0-9]*' \
        | head -1 | grep -o '[0-9]*$')
  if [ -z "$DID" ]; then
    DID=$(dumpsys display 2>/dev/null | grep -B8 'mPrimaryDisplayDevice=HDMI' \
          | grep -o 'mDisplayId=[0-9]*' | tail -1 | cut -d= -f2)
  fi
  echo "$DID"
}

SDK=/data/data/com.huawei.vrlauncherx/app_SdkLibs
restore_libs() {
  [ -d "$SDK" ] || { log "WARN: sdk dir missing"; return 1; }
  uid=$(stat -c %u "$SDK" 2>/dev/null)
  gid=$(stat -c %g "$SDK" 2>/dev/null)
  # 2026-11-17/18 verified set (SBS output, diff=0, no GPU faults):
  #   libWalleAlg walle b644310f + libWalleDev orig 53e1ce65
  #   + hvrframework.dex orig 9fc23387
  # 2026-11-19: libvrsdk switched to a11desktop v4a (aa45b3e3) =
  #   62089b63 base + 270deg per-eye rotation patch. 62089b63 (rot0,
  #   unpatched shaders) shows the desktop rotated 90deg CCW through
  #   the lenses (user-verified "text head to the left"). v4a pre-
  #   rotates 270 to cancel the panel scanout rotation. Revert path:
  #   /data/local/tmp/libvrsdk.offset.so (= 62089b63).
  # 2026-11-20: FINAL = none_e9 (efaa9820) = 62089b63 + 270deg transpose
  #   + clamp((proj.x*projIZ-.5)*ES,-.49,.49) + NO per-eye flip.
  #   Two fixes vs v4a/inset2:
  #   1) Bar: proj.x*projIZ overshoots [0,1] at eye inner edges -> V
  #      wraps (GL_REPEAT) showing mirrored/squeezed card content strip.
  #      clamp bounds V to [.01,.99]; E8 discriminator (V pinned to
  #      [.4,.6]) confirmed wrap mechanism; inset/zoom approaches failed.
  #   2) Fusion: es=sign(aPosition.x) mirrored the RIGHT eye (user saw
  #      unfusable double image / "颜色不正常"). a11desktop scans both
  #      eyes the same way -> 'none' step (no flip). Old-phone-line v4a
  #      flip does NOT apply here.
  #   Revert chain: e9 81f8f679 (clamp+flip) -> inset2 1d3a2125 ->
  #   v4a aa45b3e3 -> bare base /data/local/tmp/libvrsdk.offset.so
  #   (62089b63, rot0, lies sideways).
  for pair in \
      "libWalleDev.so:/data/local/tmp/orig/libWalleDev.so" \
      "hvrframework.dex:/data/local/tmp/orig/hvrframework.dex" \
      "libvrsdk.so:/data/local/tmp/libvrsdk.a11.none9.so" \
      "libWalleAlg.so:/data/local/tmp/libWalleAlg.walle.so"; do
    dst=${pair%%:*}
    src=${pair#*:}
    [ -f "$src" ] || { log "WARN: missing $src"; continue; }
    cp -f "$src" "$SDK/$dst"
    chown "${uid:-10319}:${gid:-10319}" "$SDK/$dst" 2>/dev/null
    chmod 600 "$SDK/$dst"
    log "restored $dst: $(md5sum "$SDK/$dst" | cut -c1-8)"
  done
}

DID=$(probe_did)
if [ -z "$DID" ]; then
  log "ERROR: no HDMI/glasses display found; aborting (never launch on phone display 0)"
  exit 1
fi
log "=== start: glasses displayId=$DID ==="

# A previous crashed session leaves VrSystemRegistryProvider detached
# (vr_system_state returns null). Reset the whole VR chain first.
am force-stop com.huawei.vrlauncherx >/dev/null 2>&1
am force-stop com.huawei.vrvirtualscreen >/dev/null 2>&1
am force-stop com.huawei.vrservice >/dev/null 2>&1
am force-stop com.huawei.hvrsdkserverapp >/dev/null 2>&1
sleep 2
log "VR service chain reset"

restore_libs

# 2026-11-20: glasses HID IMU stream is dead until SET report5 (activate)
# is sent (verified: without it WallexHid logs "invalid imu data (0)/(59)";
# with it the same app starts consuming IMU at ~1kHz). hwvrctl activate
# leaves the device activated (no auto-deactivate on plain activate).
# Idempotent: re-activating is harmless. Tool = /data/local/tmp/hwvrctl.
if [ -x /data/local/tmp/hwvrctl ]; then
  /data/local/tmp/hwvrctl activate /dev/hwvr1 >/dev/null 2>&1 \
    && log "glasses IMU activated (hwvrctl SET report5)"
fi

# HvrVrPanelPresenter.start() skips when the external panel is STATE_OFF
# (phone asleep). Keep the screen awake like the proven daemon does.
# Only power/brightness settings - never touch charging nodes.
input keyevent 224 >/dev/null 2>&1
svc power stayon true >/dev/null 2>&1
settings put system screen_off_timeout 2147483647 >/dev/null 2>&1
log "screen woken + stay-on (external panel STATE_ON)"

# ro.vr.surport was provided by the module's system.prop on the old ROM;
# without it Vr2dStartUpActivity shows a "please connect the glasses" dialog.
resetprop ro.vr.surport true 2>/dev/null
# Android 11/12 CachedAppsOptimizer freezes the vr_system_state binder host
# (hvrsdkserverapp / ArvrRuntimeService) -> Unity render thread never starts.
device_config put activity_manager_native_boot use_freezer false >/dev/null 2>&1
if [ -w /dev/freezer/thaw/cgroup.procs ]; then
  for p in $(ps -A -o PID,NAME 2>/dev/null | grep -iE 'hvrsdk|vrservice|vrlauncher' | awk '{print $1}'); do
    echo "$p" > /dev/freezer/thaw/cgroup.procs 2>/dev/null
  done
fi
log "ro.vr.surport=true + freezer disabled + VR procs thawed"

# 1) skip the first-run privacy page forever (q2/e.smali: Settings.System key)
settings put system PRIVACY_ACCEPTED 1
log "PRIVACY_ACCEPTED=$(settings get system PRIVACY_ACCEPTED)"

# 1.5) the phone lock screen locks EVERY display (glasses included) and keeps
#      MainActivity from resuming (verified 2026-11-18: glasses showed the
#      keyguard clock, SDK never initialized, prepare re-spawn loop).
#      Dismiss it -- works on password-less lock; with a PIN the user must
#      unlock manually.
wm dismiss-keyguard >/dev/null 2>&1
log "keyguard: $(dumpsys window policy 2>/dev/null | grep -o 'mKeyguardOccluded=[a-z]*' | head -1)"

# 2) layer-1 model: ALP-AL00 decides helmet shift; hvr.radius zeroes the
#    per-eye toe-in (SDK reads it at init -- must be set BEFORE any launch)
resetprop ro.product.name ALP-AL00 2>/dev/null
setprop hvr.radius 0.032544
# 2.1) distortion coefficients (channel verified 2026-11-18: props ->
#      glassInfo.distortionParam[0..5] -> mesh vertex UV curvature, logs show
#      "get system property successfully" + screenshot shows barrel bending).
#      Without these the mesh is linear (straight lines -> fusion fails).
#      key0 = primary radial term; 0.05 = current calibration probe, user-judged
#      fine-tuning pending. R/B kept identical until chromatic aberration pass.
for _k in 0 1 2 3 4 5; do
  setprop hvr.distortion.key$_k 0.0
  setprop hvr.distortionR.key$_k 0.0
  setprop hvr.distortionB.key$_k 0.0
done
setprop hvr.distortion.key0 0.05
setprop hvr.distortionR.key0 0.05
setprop hvr.distortionB.key0 0.05
log "ro.product.name=$(getprop ro.product.name) hvr.radius=$(getprop hvr.radius) dist.key0=$(getprop hvr.distortion.key0)"

# 3) SDK watch service publishes vr_system_state
# 2026-11-18: plain `am startservice` from shell is rejected on A11
# (Background start not allowed: ... startFg?=false) and the whole SDK-server
# side never comes up; start-foreground-service passes.
am start-foreground-service -n com.huawei.hvrsdkserverapp/.compat.VrGlassWatchService >/dev/null 2>&1
log "watch service started"
sleep 5
log "watch service settle done"

# 4) 2D boot entry first: a cold MainActivity start is blocked by
#    OplusAppStartupManager (classifyResrict) on this ROM.
am start --windowingMode 1 --display "$DID" -f 0x10008000 \
         -n "$PKG/.activity.Vr2dStartUpActivity" >/dev/null 2>&1
i=0
while [ "$i" -lt 15 ]; do
  dumpsys activity activities 2>/dev/null \
    | grep -qE 'mResumedActivity.*Vr2dStartUpActivity|topResumedActivity.*Vr2dStartUpActivity' && break
  i=$((i+1)); sleep 1
done
log "2D entry resumed after ${i}s"
dump_disp after-2D

# 5) wait for the vr_system_state binder (Unity CheckVRMode needs it)
i=0
while [ "$i" -lt 8 ]; do
  content call --uri content://com.huawei.vrlauncherx.registry \
    --method getService --arg vr_system_state 2>/dev/null | grep -q 'binder=' && break
  i=$((i+1)); sleep 1
done
log "vr_system_state binder after ${i}s"

# 6) wait for the HUAWEI VR Display carrier screen (panel-bootstrap)
i=0
while [ "$i" -lt 15 ]; do
  dumpsys display 2>/dev/null | grep -q 'HUAWEI VR Display' && break
  i=$((i+1)); sleep 1
done
log "carrier display after ${i}s"
dump_disp after-carrier

# 7) The 2D cold start may have re-extracted libs; restore again before 3D.
restore_libs

# 7.5) launcher reset exactly like the proven start6 cold-start sequence:
#      VRService may have spawned MainActivity on the phone display; a
#      CLEAR_TOP start would reuse that instance and the glasses stay dark.
am force-stop "$PKG" >/dev/null 2>&1
am force-stop com.huawei.vrvirtualscreen >/dev/null 2>&1
i=0
while [ "$i" -lt 10 ]; do
  dumpsys activity activities 2>/dev/null | grep -q "$PKG/.MainActivity" || break
  i=$((i+1)); sleep 1
done
log "launcher reset before 3D (${i}s)"

# 7.6) RE-PROBE the glasses display: the chain reset above can re-enumerate
#      the panel under a new displayId (verified 4 -> 55). NEVER guess.
DID=$(probe_did)
if [ -z "$DID" ]; then
  log "ERROR: glasses display gone after chain reset; aborting"
  exit 1
fi
log "3D target displayId=$DID"
setprop hvr.radius 0.032544

# 8) direct cold launch on the glasses display (start6 sequence)
i=1
while [ "$i" -le 3 ]; do
  out=$(am start --windowingMode 1 --display "$DID" -f 0x18000000 \
        -n "$PKG/.MainActivity" 2>&1)
  case "$out" in
    *"Activity not started because"*|*SecurityException*|*"Unknown displayId"*|*"not found"*|*"Permission Denial"*)
      log "3D attempt $i rejected: $(echo "$out" | head -1)"
      sleep 2 ;;
    *) log "3D MainActivity launched (attempt $i)"; break ;;
  esac
  i=$((i+1))
  DID=$(probe_did)
done
sleep 8
dump_disp after-3D
log "sdk: $(logcat -d 2>/dev/null | grep -E 'updateConstParams|SinglePassMode' | tail -2 | tr '\n' '|')"

# 9) NO vr.running broadcast here (it rebuilds the display chain and kills
#    the glasses display). VRPrepareActivity respawns on the phone display
#    by itself; it does not cover the glasses, just log it.
if ! dumpsys activity activities 2>/dev/null \
     | grep -qE 'mResumedActivity.*com\.huawei\.vrlauncherx/\.MainActivity'; then
  DID=$(probe_did)
  am start --windowingMode 1 --display "$DID" -f 0x18000000 \
      -n "$PKG/.MainActivity" >/dev/null 2>&1
  log "3D re-launched (was not resumed)"
fi
log "prepare: $(dumpsys activity activities 2>/dev/null | grep -m1 VRPrepareActivity)"
dump_disp final
log "=== done ==="
