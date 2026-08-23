#!/bin/bash
# Put the timing banner on screen in the emulator for UI work.
#   ./tools/show-timing-banner.sh found      -> "Timing looks 5.5 s off" + Dismiss/Fix it
#   ./tools/show-timing-banner.sh searching  -> "Checking whether the clock is off... 1 of 3"
#   ./tools/show-timing-banner.sh hide       -> clear it
# Debug builds only. Add a device serial as the second argument if needed.
DEV=${2:-emulator-5554}
case "${1:-found}" in
  found)     KIND=1 ;;
  searching) KIND=0 ;;
  hide)      KIND=2 ;;
  *) echo "usage: $0 [found|searching|hide] [serial]"; exit 1 ;;
esac
adb -s "$DEV" shell am start -n com.js8call.example/.MainActivity -f 0x20000000 \
  --ei debug_timing_kind $KIND --el debug_timing_drift -5500 \
  --ei debug_timing_step 1 --ei debug_timing_steps 3 >/dev/null 2>&1
echo "banner -> ${1:-found}"
