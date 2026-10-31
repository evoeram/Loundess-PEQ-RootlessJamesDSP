/*
 * Golden-file harness for libjamesdsp core chain (docs/PLAN.md, P0.1).
 *
 * Generates a deterministic stereo signal, runs it through the engine with a
 * fixed parameter set, writes the output as 32-bit float WAV and prints an
 * FNV-1a hash of the raw PCM bytes. Two builds compared bit-exactly
 * (`cmp`) must produce identical hashes unless a change is intentional.
 *
 * Usage: golden <crossfeedMode> <out.wav> [ddcFile]
 *   crossfeedMode: 1 = BS2B path, 3 = convolver path; add 10 to disable the
 *   vacuum tube (11/13) - used to prove the non-tube fixes are bit-neutral
 *
 * Parser-only mode: golden -1 <file.vdc> [more.vdc ...] parses each DDC
 * string with DDCParser (via DDCRefresh on a scratch engine) and prints
 * PASS/FAIL per file; exits nonzero if any fails. Used to fuzz the vdc.c
 * parser against the whole .vdc corpus.
 *
 * Built without JAMESDSP_REFERENCE_IMPL on purpose: the convolver benchmark
 * thread measures real CPU speed and would make partition selection (and
 * therefore the output) nondeterministic between runs.
 *
 * Host build: see build_golden.sh (Android NDK, aarch64; runs on device via
 * adb, because no Windows-side compiler is available in this environment).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <stdint.h>
#include <time.h>
#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/jdsp_header.h"
#include "../../app/src/main/cpp/libjamesdsp/Main/libjamesdsp/jni/jamesdsp/jdsp/Effects/eel2/dr_wav.h"

#define FS 44100
#define SECONDS 10
#define FRAMES ((size_t)FS * SECONDS)
#define BLOCK 1024

static uint32_t lcgState = 0x12345678u;
static float lcgNoise(void)
{
	lcgState = lcgState * 1664525u + 1013904223u;
	return (float)((int32_t)lcgState / (double)(1u << 31)) * 0.5f;
}

static void generateInput(float *inL, float *inR)
{
	for (size_t i = 0; i < FRAMES; i++)
	{
		double t = (double)i / FS;
		// 6..7 s: loud passage, engages compressor / tube / limiter
		float gain = 1.0f;
		if (t >= 6.0 && t < 7.0)
			gain = 3.0f;
		// 8.5..9.0 s: digital silence, exercises envelope/denormal paths
		float mute = 1.0f;
		if (t >= 8.5 && t < 9.0)
			mute = 0.0f;
		inL[i] = (0.28f * (float)sin(2.0 * M_PI * 55.0 * t)
		        + 0.22f * (float)sin(2.0 * M_PI * 220.0 * t + 0.7)
		        + 0.18f * (float)sin(2.0 * M_PI * 1000.0 * t)
		        + 0.10f * (float)sin(2.0 * M_PI * 7000.0 * t + 1.3)
		        + 0.06f * lcgNoise()) * gain * mute;
		// right channel: small deterministic decorrelation so widening/crossfeed do real work
		inR[i] = (0.28f * (float)sin(2.0 * M_PI * 55.0 * t + 0.21)
		        + 0.22f * (float)sin(2.0 * M_PI * 220.0 * t + 0.9)
		        + 0.18f * (float)sin(2.0 * M_PI * 1000.0 * t + 0.1)
		        + 0.10f * (float)sin(2.0 * M_PI * 7000.0 * t + 1.5)
		        + 0.06f * lcgNoise()) * gain * mute;
	}
}

static uint32_t fnv1a(const float *buf, size_t floatCount)
{
	const uint8_t *p = (const uint8_t *)buf;
	size_t n = floatCount * sizeof(float);
	uint32_t h = 2166136261u;
	for (size_t i = 0; i < n; i++)
	{
		h ^= p[i];
		h *= 16777619u;
	}
	return h;
}

// ---- stress mode (-2): audio thread vs settings thread ---------------------
// Thread A runs the process chain in a loop; thread B hammers the settings
// entry points every ~20 ms the same way jamesdsp.c does (compander under
// the engine lock, self-locking EQ/crossfeed/arbEq calls, and the
// forceRefresh sample-rate switch whose old shape deadlocked against the
// self-locking Enables). Acceptance for docs/PLAN.md Phase 2 (report K6):
// the run must end cleanly; an ASAN build must report no heap errors.
static volatile int stressStop = 0;

static void stressSettings(JamesDSPLib *jdsp, int tick)
{
	// compander rebuild, under the engine lock like jamesdsp.c cmd 115
	if (tick % 5 == 0)
	{
		double freqDRC[NUMPTS_DRS] = { 95.0, 200.0, 400.0, 800.0, 1600.0, 3400.0, 7500.0 };
		double gainsDRC[NUMPTS_DRS];
		for (int i = 0; i < NUMPTS_DRS; i++)
			gainsDRC[i] = -1.0 + (tick % 7) * 0.25;
		jdsp_lock(jdsp);
		CompressorSetParam(jdsp, 0.8f, 4, tick % 3, 0);
		CompressorSetGain(jdsp, freqDRC, gainsDRC, 1);
		jdsp_unlock(jdsp);
	}
	// mEQ axis, self-locking
	if (tick % 5 == 1)
	{
		double freqM[NUMPTS] = { 25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0 };
		double gainsM[NUMPTS];
		for (int i = 0; i < NUMPTS; i++)
			gainsM[i] = ((i + tick) % 9 - 4) * 2.0;
		MultimodalEqualizerAxisInterpolation(jdsp, tick % 2, 5, freqM, gainsM);
	}
	// crossfeed mode sweep, self-locking (0..5 covers BS2B and convolver paths)
	if (tick % 5 == 2)
		CrossfeedChangeMode(jdsp, tick % 6);
	// arbitrary EQ reparse, self-locking
	if (tick % 5 == 3)
	{
		static char buf[128];
		snprintf(buf, sizeof(buf), "stress: 100 %d; 1000 %d; 10000 %d; 20000 0;", (tick % 9) - 4, (tick % 7) - 3, (tick % 5) - 2);
		ArbitraryResponseEqualizerStringParser(jdsp, buf);
	}
	// the forceRefresh rate switch: the old shape held the engine lock while
	// calling the self-locking Enables and hung for good
	if (tick % 5 == 4)
		JamesDSPSetSampleRate(jdsp, (tick % 10) < 5 ? 44100.0f : 48000.0f, 1);
}

typedef struct
{
	JamesDSPLib *jdsp;
	float *inL, *inR, *outL, *outR;
} stressArgs;

static void *stressAudioThread(void *arg)
{
	stressArgs *a = (stressArgs *)arg;
	while (!stressStop)
		for (size_t b = 0; b < FRAMES / BLOCK && !stressStop; b++)
			a->jdsp->processFloatDeinterleaved(a->jdsp, a->inL + b * BLOCK, a->inR + b * BLOCK, a->outL + b * BLOCK, a->outR + b * BLOCK, BLOCK);
	return 0;
}

static int stressRun(int seconds)
{
	printf("[stress] init engine\n");
	JamesDSPGlobalMemoryAllocation();
	JamesDSPLib *jdsp = (JamesDSPLib *)malloc(sizeof(JamesDSPLib));
	memset(jdsp, 0, sizeof(JamesDSPLib));
	JamesDSPInit(jdsp, BLOCK, FS);
	jdsp->smoothVolumeGain = 1.0f;
	jdsp->outputControlEnabled = 1;
	JLimiterSetCoefficients(jdsp, 0.0, 100);

	double freqDRC[NUMPTS_DRS] = { 95.0, 200.0, 400.0, 800.0, 1600.0, 3400.0, 7500.0 };
	double gainsDRC[NUMPTS_DRS] = { -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, };
	CompressorSetParam(jdsp, 0.8f, 4, 1, 1);
	CompressorSetGain(jdsp, freqDRC, gainsDRC, 1);
	CompressorEnable(jdsp, 1);
	double freqM[NUMPTS] = { 25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0 };
	double gainsM[NUMPTS] = { 5.0, -1.0, -4.0, -1.0, 2.1, 0.0, 0.0, 0.7, -10.7, 0.0, 0.0, 0.0, 0.0, 0.8, 8.0 };
	MultimodalEqualizerAxisInterpolation(jdsp, 0, 5, freqM, gainsM);
	MultimodalEqualizerEnable(jdsp, 1);
	static char arbStr[] = "stress: 20 -2; 1000 -3; 5000 2.5; 20000 0;";
	ArbitraryResponseEqualizerStringParser(jdsp, arbStr);
	ArbitraryResponseEqualizerEnable(jdsp, 1);
	CrossfeedChangeMode(jdsp, 1);
	CrossfeedEnable(jdsp, 1);
	StereoEnhancementSetParam(jdsp, 0.9f);
	StereoEnhancementEnable(jdsp);
	Reverb_SetParam(jdsp, 4);
	ReverbEnable(jdsp);

	float *inL = (float *)malloc(FRAMES * sizeof(float));
	float *inR = (float *)malloc(FRAMES * sizeof(float));
	generateInput(inL, inR);
	float *outL = (float *)calloc(FRAMES, sizeof(float));
	float *outR = (float *)calloc(FRAMES, sizeof(float));

	stressArgs args = { jdsp, inL, inR, outL, outR };
	pthread_t audioThread;
	if (pthread_create(&audioThread, 0, stressAudioThread, &args) != 0)
	{
		printf("[stress] cannot start audio thread\n");
		return 5;
	}
	printf("[stress] hammering settings for %d s\n", seconds);
	for (int t = 0; t * 20 < seconds * 1000; t++)
	{
		stressSettings(jdsp, t);
		struct timespec ts = { 0, 20 * 1000 * 1000 };
		nanosleep(&ts, 0);
	}
	stressStop = 1;
	pthread_join(audioThread, 0);
	printf("HASH_L %08x\n", fnv1a(outL, FRAMES));
	printf("HASH_R %08x\n", fnv1a(outR, FRAMES));

	ReverbDisable(jdsp);
	StereoEnhancementDisable(jdsp);
	ArbitraryResponseEqualizerDisable(jdsp);
	CrossfeedDisable(jdsp);
	MultimodalEqualizerDisable(jdsp);
	CompressorDisable(jdsp);
	JamesDSPFree(jdsp);
	JamesDSPGlobalMemoryDeallocation();
	free(jdsp);
	free(inL);
	free(inR);
	free(outL);
	free(outR);
	printf("[stress] clean exit\n");
	return 0;
}
// ---- rate-refresh mode (-3): do DDC, compander and stereo widening --------
// follow a 44.1 / 48 kHz move? (docs/PLAN.md Phase 3, report K8; port of
// source tools/raterefresh_harness.c + tools/sterenh_harness.c). Set both up
// at one rate with the real engine, move with the real JamesDSPSetSampleRate,
// resend the same settings the way the app does after ACTION_SAMPLE_RATE_UPDATED
// (P3.3) - the DDC text matches, so its parser returns early - and they must
// equal an engine set up at the new rate to begin with.
static const char rateDdc[] =
	"# test\nSR_44100:1.10,0.11,0.12,0.13,0.14,1.20,0.21,0.22,0.23,0.24\n"
	"SR_48000:2.10,0.31,0.32,0.33,0.34,2.20,0.41,0.42,0.43,0.44\n";
static double rateFreqs[NUMPTS_DRS] = { 95, 200, 400, 800, 1600, 3400, 7500 };
static double rateGains[NUMPTS_DRS] = { 0.6, -0.4, 0.3, -0.6, 0.5, -0.2, 0.4 };

static JamesDSPLib *rateMake(float rate)
{
	JamesDSPLib *d = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	JamesDSPInit(d, 128, rate);
	DDCStringParser(d, strdup(rateDdc));
	DDCEnable(d, 1);
	CompressorSetParam(d, 0.22f, 2, 0, 0);
	CompressorSetGain(d, rateFreqs, rateGains, 1);
	CompressorEnable(d, 1);
	StereoEnhancementSetParam(d, 0.6f);
	StereoEnhancementEnable(d);
	return d;
}
// what the app does next after the rate arrives: push the same settings again
static void rateResend(JamesDSPLib *d)
{
	DDCStringParser(d, strdup(rateDdc));
	DDCEnable(d, 1);
	CompressorSetParam(d, 0.22f, 2, 0, 0);
	CompressorSetGain(d, rateFreqs, rateGains, 1);
	CompressorEnable(d, 1);
	StereoEnhancementSetParam(d, 0.6f);
}
static int rateRefreshRun(void)
{
	int failures = 0;
	float dirs[2][2] = { { 48000.0f, 44100.0f }, { 44100.0f, 48000.0f } };
	for (int k = 0; k < 2; k++)
	{
		const float from = dirs[k][0], to = dirs[k][1];
		JamesDSPLib *moved = rateMake(from), *ref = rateMake(to);
		JamesDSPSetSampleRate(moved, to, 0);
		rateResend(moved);
		const double b0 = moved->vdcFl.sosPointer[0].b0, wantB0 = ref->vdcFl.sosPointer[0].b0;
		const int ddcOk = b0 == wantB0 && moved->ddcEnabled == ref->ddcEnabled;
		printf("  %5.0f -> %5.0f  DDC b0 %.2f (want %.2f)  %s\n", from, to, b0, wantB0, ddcOk ? "ok" : "FAIL");
		const int compOk = moved->comp.fgt_fac == ref->comp.fgt_fac && moved->comp.spectralRate == ref->comp.spectralRate;
		printf("  %5.0f -> %5.0f  compander smoothing %.6f (want %.6f)  %s\n", from, to,
			moved->comp.fgt_fac, ref->comp.fgt_fac, compOk ? "ok" : "FAIL");
		int stereoOk = 1;
		for (int i = 0; i < 5; i++)
			stereoOk &= moved->sterEnh.emaAlpha[i] == ref->sterEnh.emaAlpha[i];
		printf("  %5.0f -> %5.0f  widening envelope alpha %.6f (want %.6f)  %s\n", from, to,
			moved->sterEnh.emaAlpha[0], ref->sterEnh.emaAlpha[0], stereoOk ? "ok" : "FAIL");
		failures += !ddcOk + !compOk + !stereoOk;
		JamesDSPFree(moved);
		free(moved);
		JamesDSPFree(ref);
		free(ref);
	}
	printf(failures ? "FAIL (%d)\n" : "PASS\n", failures);
	return failures != 0;
}
// ---- ASRC up-sampling guard mode (-4): stream rates below 44.1 kHz grow --
// the resampled block past the scratch areas, which used to overlap the
// channels and run off the allocation (docs/PLAN.md Phase 3; port of source
// tools/asrcup_harness.c). Every allocation of 32 KB or more gets a 64 KB
// 0xA5 guard behind it (-Wl,--wrap=malloc). Each rate is fed 12 blocks of
// 4096 frames, left a 300 Hz sine at 0.5, right silence, all effects off,
// set up both by JamesDSPInit (the HAL's way) and by a 48 kHz -> rate move
// with JamesDSPSetSampleRate (the app engine's way). The scratch guard must
// be intact, the right channel silent, the left still the sine.
#define ASRC_N 4096
#define ASRC_BIG 32768
#define ASRC_GUARD 65536
#define ASRC_MAXREC 8192
static struct { unsigned char *p; size_t n; } asrcRec[ASRC_MAXREC];
static int asrcNrec = 0;

// GOLDEN_MALLOC_WRAP is set by build_golden.sh only together with
// -Wl,--wrap=malloc; without the wrap there is no __real_malloc to call.
#ifdef GOLDEN_MALLOC_WRAP
void *__real_malloc(size_t);
#else
#define __real_malloc malloc
#endif
void *__wrap_malloc(size_t n)
{
	if (n < ASRC_BIG || asrcNrec >= ASRC_MAXREC)
		return __real_malloc(n);
	unsigned char *p = (unsigned char *)__real_malloc(n + ASRC_GUARD);
	if (!p)
		return p;
	memset(p + n, 0xA5, ASRC_GUARD);
	asrcRec[asrcNrec].p = p;
	asrcRec[asrcNrec].n = n;
	asrcNrec++;
	return p;
}

static int asrcFailures = 0;
static void asrcRun(float rate, int viaSetRate)
{
	static float x[ASRC_N * 2], y[ASRC_N * 2];
	JamesDSPLib *lib = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	if (viaSetRate)
	{
		// the app engine's way: made at 48 kHz with a small block, then moved,
		// so the layout comes from JamesDSPSetSampleRate and then
		// JamesDSPReallocateBlock on the first full-size block
		JamesDSPInit(lib, 128, 48000.f);
		JamesDSPSetSampleRate(lib, rate, 0);
	}
	else
		JamesDSPInit(lib, ASRC_N, rate);
	double rmsL = 0, rmsR = 0, ph = 0, w = 2.0 * M_PI * 300.0 / rate;
	int cnt = 0;
	for (int b = 0; b < 12; b++)
	{
		for (int i = 0; i < ASRC_N; i++)
		{
			x[2 * i] = 0.5f * (float)sin(ph);
			x[2 * i + 1] = 0.0f;
			ph += w;
		}
		lib->processFloatMultiplexd(lib, x, y, ASRC_N);
		if (b >= 4)
			for (int i = 0; i < ASRC_N; i++)
			{
				rmsL += y[2 * i] * (double)y[2 * i];
				rmsR += y[2 * i + 1] * (double)y[2 * i + 1];
				cnt++;
			}
	}
	rmsL = sqrt(rmsL / cnt);
	rmsR = sqrt(rmsR / cnt);
	long changed = -1;
	for (int i = 0; i < asrcNrec; i++)
		if (asrcRec[i].p == (unsigned char *)lib->tmpBuffer[0])
		{
			changed = 0;
			for (long k = 0; k < ASRC_GUARD; k++)
				changed += asrcRec[i].p[asrcRec[i].n + k] != 0xA5;
		}
	// an unguarded scratch block (under 32 KB) is only possible without the ASRC
	if (changed < 0 && lib->enableASRC)
		changed = 1 << 30;
	if (changed < 0)
		changed = 0;
	int ok = changed == 0 && rmsR < 1e-3 && rmsL > 0.3 && rmsL < 0.4;
	printf("  %s %6.0f Hz  fs %5.0f  ASRC %d  guard bytes changed %6ld  RMS L %.4f R %.5f  %s\n",
		viaSetRate ? "set rate" : "init    ", rate, lib->fs, lib->enableASRC, changed, rmsL, rmsR, ok ? "ok" : "FAIL");
	if (!ok)
		asrcFailures++;
}
static int asrcGuardRun(void)
{
	setvbuf(stdout, NULL, _IONBF, 0);
	JamesDSPGlobalMemoryAllocation();
	const float rates[] = { 96000.f, 48000.f, 44100.f, 32000.f, 24000.f, 22050.f, 16000.f, 12000.f, 11025.f, 8000.f };
	for (int i = 0; i < (int)(sizeof(rates) / sizeof(rates[0])); i++)
		asrcRun(rates[i], 0);
	for (int i = 0; i < (int)(sizeof(rates) / sizeof(rates[0])); i++)
		asrcRun(rates[i], 1);
	printf(asrcFailures ? "FAIL (%d)\n" : "PASS\n", asrcFailures);
	return asrcFailures != 0;
}
// -----------------------------------------------------------------------------

int main(int argc, char **argv)
{
	if (argc >= 2 && atoi(argv[1]) == -4)
	{
		return asrcGuardRun();
	}
	if (argc >= 2 && atoi(argv[1]) == -3)
	{
		JamesDSPGlobalMemoryAllocation();
		int rc = rateRefreshRun();
		JamesDSPGlobalMemoryDeallocation();
		return rc;
	}
	if (argc < 3)
	{
		printf("usage: golden <crossfeedMode> <out.wav> [ddcFile]\n"
		       "       golden -1 <file.vdc> [more.vdc ...]\n"
		       "       golden -2 <seconds>\n"
		       "       golden -3\n"
		       "       golden -4\n");
		return 1;
	}
	if (atoi(argv[1]) == -2)
	{
		int seconds = argc > 2 ? atoi(argv[2]) : 60;
		return stressRun(seconds);
	}
	int xfMode = atoi(argv[1]);
	const int enableTube = xfMode < 10;
	const int xfPath = xfMode % 10;
	const char *outPath = argv[2];
	const char *ddcPath = argc > 3 ? argv[3] : 0;

	if (xfMode < 0)
	{
		// Parser-only mode: parse each .vdc argument, report PASS/FAIL.
		if (argc < 3)
		{
			printf("usage: golden -1 <file.vdc> [more.vdc ...]\n");
			return 1;
		}
		JamesDSPGlobalMemoryAllocation();
		int fails = 0;
		for (int a = 2; a < argc; a++)
		{
			FILE *f = fopen(argv[a], "rb");
			if (!f)
			{
				printf("VDC_OPEN_FAIL %s\n", argv[a]);
				fails++;
				continue;
			}
			fseek(f, 0, SEEK_END);
			long len = ftell(f);
			fseek(f, 0, SEEK_SET);
			char *str = (char *)malloc(len + 1);
			if (fread(str, 1, len, f) != (size_t)len)
				len = 0;
			fclose(f);
			str[len] = '\0';
			// DDCParser is static to vdc.c; go through DDCRefresh on a
			// scratch engine, exactly like the real DDCStringParser path.
			JamesDSPLib *jdsp = (JamesDSPLib *)malloc(sizeof(JamesDSPLib));
			memset(jdsp, 0, sizeof(JamesDSPLib));
			JamesDSPInit(jdsp, BLOCK, FS);
			int sos = DDCStringParser(jdsp, str) > 0 ? (int)jdsp->vdcFl.usedSOSCount : 0;
			if (sos > 0)
				printf("VDC_PASS %s sos=%d\n", argv[a], sos);
			else
			{
				printf("VDC_FAIL %s\n", argv[a]);
				fails++;
			}
			JamesDSPFree(jdsp);
			free(jdsp);
			free(str);
		}
		JamesDSPGlobalMemoryDeallocation();
		return fails ? 4 : 0;
	}

	float *inL = (float *)malloc(FRAMES * sizeof(float));
	float *inR = (float *)malloc(FRAMES * sizeof(float));
	generateInput(inL, inR);

	printf("[golden] init engine\n");
	JamesDSPGlobalMemoryAllocation();
	JamesDSPLib *jdsp = (JamesDSPLib *)malloc(sizeof(JamesDSPLib));
	memset(jdsp, 0, sizeof(JamesDSPLib));
	JamesDSPInit(jdsp, BLOCK, FS);
	jdsp->smoothVolumeGain = 1.0f;
	jdsp->outputControlEnabled = 1;
	JLimiterSetCoefficients(jdsp, 0.0, 100);

	// Fixed parameter set; keep every call in the same order forever, the
	// golden file depends on it.
	double freqDRC[NUMPTS_DRS] = { 95.0, 200.0, 400.0, 800.0, 1600.0, 3400.0, 7500.0 };
	double gainsDRC[NUMPTS_DRS] = { -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, };
	CompressorSetParam(jdsp, 0.8f, 4, 1, 1);
	CompressorSetGain(jdsp, freqDRC, gainsDRC, 1);
	CompressorEnable(jdsp, 1);

	if (enableTube)
	{
		VacuumTubeSetGain(jdsp, 8.0);
		VacuumTubeEnable(jdsp);
	}

	BassBoostSetParam(jdsp, 5.0f);
	BassBoostEnable(jdsp);

	double freqM[NUMPTS] = { 25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0 };
	double gainsM[NUMPTS] = { 5.0, -1.0, -4.0, -1.0, 2.1, 0.0, 0.0, 0.7, -10.7, 0.0, 0.0, 0.0, 0.0, 0.8, 8.0 };
	MultimodalEqualizerAxisInterpolation(jdsp, 0, 5, freqM, gainsM);
	MultimodalEqualizerEnable(jdsp, 1);

	if (ddcPath)
	{
		FILE *f = fopen(ddcPath, "rb");
		if (!f)
		{
			printf("ERROR: cannot open DDC file %s\n", ddcPath);
			return 3;
		}
		fseek(f, 0, SEEK_END);
		long len = ftell(f);
		fseek(f, 0, SEEK_SET);
		char *ddc = (char *)malloc(len + 1);
		if (fread(ddc, 1, len, f) != (size_t)len)
		{
			printf("ERROR: short read on %s\n", ddcPath);
			return 3;
		}
		ddc[len] = '\0';
		fclose(f);
		DDCStringParser(jdsp, ddc);
		free(ddc);
	}
	DDCEnable(jdsp, 1);

	CrossfeedChangeMode(jdsp, xfPath);
	CrossfeedEnable(jdsp, 1);

	static char arbStr[] = "golden: 20 -2; 100 1.5; 1000 -3; 5000 2.5; 15000 -1.5; 20000 0;";
	ArbitraryResponseEqualizerStringParser(jdsp, arbStr);
	ArbitraryResponseEqualizerEnable(jdsp, 1);

	StereoEnhancementSetParam(jdsp, 0.9f);
	StereoEnhancementEnable(jdsp);

	Reverb_SetParam(jdsp, 4);
	ReverbEnable(jdsp);

	printf("[golden] process %zu frames, block %d\n", FRAMES, BLOCK);
	float *outL = (float *)calloc(FRAMES, sizeof(float));
	float *outR = (float *)calloc(FRAMES, sizeof(float));
	for (size_t b = 0; b < FRAMES / BLOCK; b++)
		jdsp->processFloatDeinterleaved(
			jdsp,
			inL + b * BLOCK,
			inR + b * BLOCK,
			outL + b * BLOCK,
			outR + b * BLOCK,
			BLOCK);

	printf("HASH_L %08x\n", fnv1a(outL, FRAMES));
	printf("HASH_R %08x\n", fnv1a(outR, FRAMES));

	drwav pWav;
	drwav_data_format format;
	format.container = drwav_container_riff;
	format.format = DR_WAVE_FORMAT_IEEE_FLOAT;
	format.channels = 2;
	format.sampleRate = FS;
	format.bitsPerSample = 32;
	if (!drwav_init_file_write(&pWav, outPath, &format, 0))
	{
		printf("ERROR: cannot open %s for writing\n", outPath);
		return 2;
	}
	for (size_t b = 0; b < FRAMES / BLOCK; b++)
	{
		static float inter[2 * BLOCK];
		for (size_t j = 0; j < BLOCK; j++)
		{
			inter[2 * j] = outL[b * BLOCK + j];
			inter[2 * j + 1] = outR[b * BLOCK + j];
		}
		drwav_write_pcm_frames(&pWav, BLOCK, inter);
	}
	drwav_uninit(&pWav);
	printf("[golden] wrote %s\n", outPath);

	ReverbDisable(jdsp);
	StereoEnhancementDisable(jdsp);
	ArbitraryResponseEqualizerDisable(jdsp);
	CrossfeedDisable(jdsp);
	DDCDisable(jdsp);
	MultimodalEqualizerDisable(jdsp);
	BassBoostDisable(jdsp);
	CompressorDisable(jdsp);
	VacuumTubeDisable(jdsp);
	JamesDSPFree(jdsp);
	JamesDSPGlobalMemoryDeallocation();
	free(jdsp);
	free(inL);
	free(inR);
	free(outL);
	free(outR);
	return 0;
}
