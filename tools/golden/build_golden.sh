#!/bin/sh
# Build the golden-file harness (docs/PLAN.md P0.1) for aarch64 Android.
# Runs on the MT2111 via adb (see run_golden.sh) - no Windows-side C compiler
# is available, so the NDK toolchain is used even for host-side verification.
#
# Usage: tools/golden/build_golden.sh [ndk-version]
set -e
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
NDKVER="${1:-27.2.12479018}"
NDK="${ANDROID_NDK_HOME:-$LOCALAPPDATA/Android/Sdk/ndk/$NDKVER}"
CC="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android30-clang"
ENG="$REPO/app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp"

test -x "$CC" || { echo "NDK clang not found: $CC" >&2; exit 1; }

SRCS="
cpthread.c
jdsp/generalDSP/spectralInterpolatorFloat.c
jdsp/generalDSP/ArbFIRGen.c
jdsp/Effects/eel2/numericSys/codelet.c
jdsp/generalDSP/digitalFilters.c
jdsp/Effects/eel2/numericSys/FFTConvolver.c
jdsp/generalDSP/TwoStageFFTConvolver.c
jdsp/generalDSP/interpolation.c
jdsp/generalDSP/generalProg.c
jdsp/Effects/vdc.c
jdsp/Effects/vacuumTube.c
jdsp/Effects/stereoEnhancement.c
jdsp/Effects/reverb.c
jdsp/Effects/liveprogWrapper.c
jdsp/Effects/multimodalEQ.c
jdsp/Effects/dynamic.c
jdsp/Effects/dbb.c
jdsp/Effects/harmonicExpander.c
jdsp/Effects/nosR2R.c
jdsp/Effects/subHarmonicExpander.c
jdsp/Effects/convolver1D.c
jdsp/Effects/crossfeed.c
jdsp/Effects/bs2b.c
jdsp/Effects/arbEqConv.c
jdsp/Effects/eel2/numericSys/libsamplerate/samplerate.c
jdsp/Effects/eel2/numericSys/libsamplerate/src_sinc.c
jdsp/Effects/eel2/numericSys/libsamplerate/src_linear.c
jdsp/Effects/eel2/numericSys/FilterDesign/generalFdesign.c
jdsp/Effects/eel2/numericSys/FilterDesign/cos_fib_paraunitary.c
jdsp/Effects/eel2/numericSys/FilterDesign/polyphaseFilterbank.c
jdsp/Effects/eel2/numericSys/FilterDesign/polyphaseASRC.c
jdsp/Effects/eel2/numericSys/FilterDesign/eqnerror.c
jdsp/Effects/eel2/numericSys/FilterDesign/firls.c
jdsp/Effects/eel2/numericSys/SolveLinearSystem/inv.c
jdsp/Effects/eel2/numericSys/SolveLinearSystem/pinv.c
jdsp/Effects/eel2/numericSys/SolveLinearSystem/mldivide.c
jdsp/Effects/eel2/numericSys/SolveLinearSystem/mrdivide.c
jdsp/Effects/eel2/numericSys/SolveLinearSystem/qr_fact.c
jdsp/Effects/eel2/numericSys/solvopt.c
jdsp/Effects/eel2/numericSys/cpoly.c
jdsp/Effects/eel2/numericSys/MersenneTwister.c
jdsp/Effects/eel2/numericSys/quadprog.c
jdsp/Effects/eel2/numericSys/HPFloat/atox.c
jdsp/Effects/eel2/numericSys/HPFloat/constant.c
jdsp/Effects/eel2/numericSys/HPFloat/cxaop.c
jdsp/Effects/eel2/numericSys/HPFloat/cxbasic.c
jdsp/Effects/eel2/numericSys/HPFloat/cxconstant.c
jdsp/Effects/eel2/numericSys/HPFloat/cxconvf.c
jdsp/Effects/eel2/numericSys/HPFloat/cxexp.c
jdsp/Effects/eel2/numericSys/HPFloat/cxhypb.c
jdsp/Effects/eel2/numericSys/HPFloat/cxidiv.c
jdsp/Effects/eel2/numericSys/HPFloat/cxpow.c
jdsp/Effects/eel2/numericSys/HPFloat/cxprcmp.c
jdsp/Effects/eel2/numericSys/HPFloat/cxtrig.c
jdsp/Effects/eel2/numericSys/HPFloat/hpaconf.c
jdsp/Effects/eel2/numericSys/HPFloat/prcxpr.c
jdsp/Effects/eel2/numericSys/HPFloat/print.c
jdsp/Effects/eel2/numericSys/HPFloat/prxpr.c
jdsp/Effects/eel2/numericSys/HPFloat/sfmod.c
jdsp/Effects/eel2/numericSys/HPFloat/shift.c
jdsp/Effects/eel2/numericSys/HPFloat/xadd.c
jdsp/Effects/eel2/numericSys/HPFloat/xchcof.c
jdsp/Effects/eel2/numericSys/HPFloat/xdiv.c
jdsp/Effects/eel2/numericSys/HPFloat/xevtch.c
jdsp/Effects/eel2/numericSys/HPFloat/xexp.c
jdsp/Effects/eel2/numericSys/HPFloat/xfmod.c
jdsp/Effects/eel2/numericSys/HPFloat/xfrac.c
jdsp/Effects/eel2/numericSys/HPFloat/xhypb.c
jdsp/Effects/eel2/numericSys/HPFloat/xivhypb.c
jdsp/Effects/eel2/numericSys/HPFloat/xivtrg.c
jdsp/Effects/eel2/numericSys/HPFloat/xlog.c
jdsp/Effects/eel2/numericSys/HPFloat/xmul.c
jdsp/Effects/eel2/numericSys/HPFloat/xneg.c
jdsp/Effects/eel2/numericSys/HPFloat/xprcmp.c
jdsp/Effects/eel2/numericSys/HPFloat/xpwr.c
jdsp/Effects/eel2/numericSys/HPFloat/xsigerr.c
jdsp/Effects/eel2/numericSys/HPFloat/xsqrt.c
jdsp/Effects/eel2/numericSys/HPFloat/xtodbl.c
jdsp/Effects/eel2/numericSys/HPFloat/xtoflt.c
jdsp/Effects/eel2/numericSys/HPFloat/xtrig.c
jdsp/Effects/eel2/s_str.c
jdsp/Effects/eel2/fft.c
jdsp/Effects/eel2/nseel-compiler.c
jdsp/Effects/eel2/nseel-ram.c
jdsp/Effects/eel2/y.tab.c
jdsp/binaryBlobs.c
jdsp/jdspController.c
jdsp/Effects/dynamicEq.c
jdsp/Effects/lufs_normalizer.c
jdsp/Effects/fet_compressor.c
jdsp/Effects/viper_clarity.c
jdsp/Effects/field_surround.c
jdsp/Effects/viper_agc.c
jdsp/Effects/cure.c
jdsp/Effects/tape.c
peq_loudness.c
"

# Same options as the on-device CMake build (app/src/main/cpp/CMakeLists.txt,
# jamesdsp_effect target). No JAMESDSP_REFERENCE_IMPL: keeps convolver
# partition selection deterministic (static default benchmark tables).
# GOLDEN_ASAN=1 adds -fsanitize=address (push the ASAN runtime next to the
# binary and run with LD_LIBRARY_PATH set; see run_golden.sh).
FULL_LIST=""
for s in $SRCS; do FULL_LIST="$FULL_LIST $ENG/$s"; done

SANITIZE=""
if [ -n "$GOLDEN_ASAN" ]; then SANITIZE="-fsanitize=address"; fi

mkdir -p "$REPO/tools/golden/build"
"$CC" -O2 -std=gnu11 \
	-ffunction-sections -fdata-sections \
	-Wno-incompatible-pointer-types -Wno-implicit-int -Wno-implicit-function-declaration \
	-DDEBUG $SANITIZE \
	-I"$ENG" -I"$ENG/jdsp" \
	"$REPO/tools/golden/golden_main.c" $FULL_LIST \
	-lm -llog \
	-o "$REPO/tools/golden/build/golden_arm64"
if [ -n "$GOLDEN_ASAN" ]; then
	CLANG_LIB_DIR="$(dirname "$CC")/../lib/clang"
	RT="$(ls "$CLANG_LIB_DIR"/*/lib/linux/libclang_rt.asan-aarch64-android.so 2>/dev/null | head -1)"
	test -n "$RT" && cp "$RT" "$REPO/tools/golden/build/" && echo "copied ASAN runtime"
fi
echo "OK: tools/golden/build/golden_arm64"
