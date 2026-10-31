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
// ---- NaN-latch mode (-5): one bad sample must not kill the output --------
// (docs/PLAN.md Phase 4, report K9; port of source tools/limlatch_harness.c,
// dbbnan_harness.c and the sterenh_harness.c NaN case). A non-finite sample
// used to latch the limiter envelope (gain 0 forever, or NaN out forever),
// the bass boost state, or the widening filterbank - nothing but an engine
// reset cleared it. After the guards one bad block must be forgotten: the
// chain recovers to full peak with zero non-finite samples.
#define NL_N 1024
static JamesDSPLib nlG;
static float nlIn[NL_N * 2], nlOut[NL_N * 2];
static long nlT;

static void nlFresh(void)
{
	static int inited = 0;
	if (inited)
		JamesDSPFree(&nlG);
	memset(&nlG, 0, sizeof(nlG));
	JamesDSPInit(&nlG, NL_N, 48000);
	inited = 1;
	JLimiterSetCoefficients(&nlG, 0.0, 60.0); /* 0 dB threshold, 60 ms release */
	nlT = 0;
}
/* one block where frame 0 carries the spike in the chosen channels, rest silence */
static void nlSpike(float v, int chL, int chR)
{
	memset(nlIn, 0, sizeof(nlIn));
	if (chL) nlIn[0] = v;
	if (chR) nlIn[1] = v;
	nlG.processFloatMultiplexd(&nlG, nlIn, nlOut, NL_N);
}
/* seconds of clean -6 dBFS 1 kHz tone; report peak and non-finite count */
static void nlTone(double sec, double *peak, long *nf)
{
	*peak = 0;
	*nf = 0;
	int blocks = (int)(sec * 48000 / NL_N);
	for (int b = 0; b < blocks; b++)
	{
		for (int i = 0; i < NL_N; i++, nlT++)
			nlIn[2 * i] = nlIn[2 * i + 1] = 0.5f * (float)sin(2 * M_PI * 1000.0 * nlT / 48000.0);
		nlG.processFloatMultiplexd(&nlG, nlIn, nlOut, NL_N);
		for (int i = 0; i < 2 * NL_N; i++)
		{
			if (!isfinite(nlOut[i]))
				(*nf)++;
			else if (fabsf(nlOut[i]) > *peak)
				*peak = fabsf(nlOut[i]);
		}
	}
}
static int nlDead(double pk, long nf) { return !(pk > 0.3 && nf == 0); }

static int nanLatchRun(void)
{
	int fails = 0;
	double p0 = 0;
	long n0 = 0;
	printf("[nanlatch] full chain through the float entry, limiter defaults\n");
	struct { const char *name; float v; int l, r; } cs[] = {
		{ "control: finite 1e30 spike (L+R)", 1e30f, 1, 1 },
		{ "one +Inf sample (L+R)", INFINITY, 1, 1 },
		{ "one -Inf sample (R only)", -INFINITY, 0, 1 },
		{ "one NaN sample (L+R)", NAN, 1, 1 },
		{ "one NaN sample (L only)", NAN, 1, 0 },
		{ "one NaN sample (R only)", NAN, 0, 1 },
	};
	for (unsigned k = 0; k < sizeof(cs) / sizeof(cs[0]); k++)
	{
		nlFresh();
		double p0;
		long n0;
		nlTone(1.0, &p0, &n0); /* pre-roll: proves the chain passes the tone */
		nlSpike(cs[k].v, cs[k].l, cs[k].r);
		double pk;
		long nf;
		nlTone(10.0, &pk, &nf);
		int d = nlDead(pk, nf);
		printf("  %-34s pre peak %.4f | 10 s later peak %.4f non-finite %ld -> %s\n",
		       cs[k].name, p0, pk, nf, d ? "DEAD (latched)" : "recovered");
		if (k == 0 && d)
		{
			printf("  control broke: harness invalid\n");
			return 9;
		}
		if (d)
			fails++;
	}
	// the widening's band envelopes latch NaN the same way (port of the
	// sterenh_harness NaN case, guard from ed58602)
	printf("[nanlatch] stereo widening\n");
	nlFresh();
	StereoEnhancementSetParam(&nlG, 0.6f);
	StereoEnhancementEnable(&nlG);
	nlTone(1.0, &p0, &n0);
	nlSpike(NAN, 1, 0);
	{
		double pk;
		long nf;
		nlTone(1.0, &pk, &nf);
		int d = nlDead(pk, nf);
		printf("  %-34s pre peak %.4f | 1 s later peak %.4f non-finite %ld -> %s\n",
		       "widening, one NaN (L only)", p0, pk, nf, d ? "DEAD (latched)" : "recovered");
		if (d)
			fails++;
	}
	// the bass boost analysis/smoothing/SVF state (port of dbbnan_harness.c)
	printf("[nanlatch] bass boost (direct, as the wrapper sets it up)\n");
	{
		static float dL[1024], dR[1024];
		static long dt;
		const char *names[] = { "one NaN sample", "one +Inf sample", "one -Inf sample" };
		float bads[] = { NAN, INFINITY, -INFINITY };
		for (int c = 0; c < 3; c++)
		{
			JamesDSPLib d;
			memset(&d, 0, sizeof(d));
			d.fs = 48000.0f;
			d.tmpBuffer[0] = dL;
			d.tmpBuffer[1] = dR;
			BassBoostConstructor(&d); // as JamesDSPInit
			BassBoostSetParam(&d, 5.0f);
			BassBoostSetParam(&d, 10.0f); // as setBassBoost
			BassBoostEnable(&d);
			dt = 0;
			double before = 0, after = 0;
			int nf = 0;
			for (int b = 0; b < 47; b++)
			{
				for (int i = 0; i < 1024; i++, dt++)
					dL[i] = dR[i] = 0.2f * (float)sin(2 * M_PI * 60.0 * dt / d.fs);
				BassBoostProcess(&d, 1024);
				for (int i = 0; i < 1024; i++)
				{
					if (!isfinite(dL[i]) || !isfinite(dR[i])) nf++;
					else if (fabs(dL[i]) > before) before = fabs(dL[i]);
				}
			}
			memset(dL, 0, sizeof(dL));
			memset(dR, 0, sizeof(dR));
			dL[100] = bads[c];
			BassBoostProcess(&d, 1024);
			for (int b = 0; b < 47; b++)
			{
				for (int i = 0; i < 1024; i++, dt++)
					dL[i] = dR[i] = 0.2f * (float)sin(2 * M_PI * 60.0 * dt / d.fs);
				BassBoostProcess(&d, 1024);
				for (int i = 0; i < 1024; i++)
				{
					if (!isfinite(dL[i]) || !isfinite(dR[i])) nf++;
					else if (fabs(dL[i]) > after) after = fabs(dL[i]);
				}
			}
			const int ok = nf == 0 && after > 0.3;
			printf("  %-18s peak before %.4f, 1 s after %.4f, non-finite %5d  %s\n",
			       names[c], before, after, nf, ok ? "ok" : "FAIL");
			if (!ok)
				fails++;
		}
	}
	printf(fails ? "FAIL (%d)\n" : "PASS\n", fails);
	return fails != 0;
}
// ---- perf mode (-6): ns/sample per effect, and what silence costs --------
// (docs/PLAN.md Phase 4, P4.3; method from source commit 5672330). Two
// questions, one harness, because the second is only visible in the timing
// of the first: what each effect costs per sample on real hardware, and
// what happens when the music stops - every IIR filter and envelope keeps
// decaying through the denormal range, where hardware that traps denormals
// slows by orders of magnitude. The silent phase is timed block by block
// and judged on its shape: a denormal stall is thousands of consecutive
// samples, so it must appear as an unbroken run of slow blocks, while the
// scheduler takes a core away one block at a time. Averaging the silent
// run would hide exactly the thing being looked for.
#define PF_BLOCK 1024
#define PF_BLOCKS 400
static double pfNowNs(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (double)ts.tv_sec * 1e9 + (double)ts.tv_nsec;
}
static int perfSubject(const char *name, void (*setup)(JamesDSPLib *), double *nsPerSample, int *worstRunOut)
{
	JamesDSPLib *jdsp = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	JamesDSPInit(jdsp, PF_BLOCK, 48000);
	jdsp->smoothVolumeGain = 1.0f;
	if (setup)
		setup(jdsp);
	float *inL = (float *)malloc(PF_BLOCK * sizeof(float));
	float *inR = (float *)malloc(PF_BLOCK * sizeof(float));
	float *outL = (float *)malloc(PF_BLOCK * sizeof(float));
	float *outR = (float *)malloc(PF_BLOCK * sizeof(float));
	unsigned s = 4242u;
	for (int i = 0; i < PF_BLOCK; i++)
	{
		s = s * 1664525u + 1013904223u;
		const float a = 0.6f * ((float)((s >> 9) & 0xFFFF) / 32768.0f - 1.0f);
		inL[i] = a;
		inR[i] = a * 0.8f;
	}
	// warmup: lets the convolver benchmark thread finish and processInternal
	// settle on JamesDSPProcess before any timing happens
	for (int b = 0; b < 50; b++)
		jdsp->processFloatDeinterleaved(jdsp, inL, inR, outL, outR, PF_BLOCK);
	// loud phase: total time -> ns/sample (includes the copy + limiter floor)
	double t0 = pfNowNs();
	for (int b = 0; b < PF_BLOCKS; b++)
		jdsp->processFloatDeinterleaved(jdsp, inL, inR, outL, outR, PF_BLOCK);
	double t1 = pfNowNs();
	*nsPerSample = (t1 - t0) / ((double)PF_BLOCKS * PF_BLOCK);
	// silent phase, block by block, state genuinely decaying after loud audio
	memset(inL, 0, PF_BLOCK * sizeof(float));
	memset(inR, 0, PF_BLOCK * sizeof(float));
	double blk[PF_BLOCKS];
	for (int b = 0; b < PF_BLOCKS; b++)
	{
		double a = pfNowNs();
		jdsp->processFloatDeinterleaved(jdsp, inL, inR, outL, outR, PF_BLOCK);
		blk[b] = pfNowNs() - a;
	}
	// median on a sorted copy; the run analysis keeps the original order
	double sorted[PF_BLOCKS];
	memcpy(sorted, blk, sizeof(blk));
	for (int i = 1; i < PF_BLOCKS; i++)
		for (int j = i; j > 0 && sorted[j - 1] > sorted[j]; j--)
		{
			double tmp = sorted[j - 1];
			sorted[j - 1] = sorted[j];
			sorted[j] = tmp;
		}
	double median = sorted[PF_BLOCKS / 2];
	double worst = sorted[PF_BLOCKS - 1];
	double slowAt = median * 3.0;
	int longest = 0, run = 0;
	for (int b = 0; b < PF_BLOCKS; b++)
	{
		if (blk[b] > slowAt)
		{
			run++;
			if (run > longest)
				longest = run;
		}
		else
			run = 0;
	}
	*worstRunOut = longest;
	free(inL);
	free(inR);
	free(outL);
	free(outR);
	JamesDSPFree(jdsp);
	free(jdsp);
	printf("  %-14s %8.1f ns/sample  silent median %7.1f us worst %7.1f us (%.1fx)  slow-run %d %s\n",
	       name, *nsPerSample, median / 1000.0, worst / 1000.0, worst / median, longest,
	       longest <= 2 ? "ok" : "SUSPECT");
	return longest <= 2;
}

static void pfTube(JamesDSPLib *j) { VacuumTubeSetGain(j, 8.0); VacuumTubeEnable(j); }
static void pfDbb(JamesDSPLib *j) { BassBoostSetParam(j, 5.0f); BassBoostEnable(j); }
static void pfComp(JamesDSPLib *j)
{
	double freq[NUMPTS_DRS] = { 95.0, 200.0, 400.0, 800.0, 1600.0, 3400.0, 7500.0 };
	double gains[NUMPTS_DRS] = { -1.0, -1.0, -1.0, -1.0, -1.0, -1.0, -1.0 };
	CompressorSetParam(j, 0.8f, 4, 1, 1);
	CompressorSetGain(j, freq, gains, 1);
	CompressorEnable(j, 1);
}
static void pfMeq(JamesDSPLib *j)
{
	double freqM[NUMPTS] = { 25.0, 40.0, 63.0, 100.0, 160.0, 250.0, 400.0, 630.0, 1000.0, 1600.0, 2500.0, 4000.0, 6300.0, 10000.0, 16000.0 };
	double gainsM[NUMPTS] = { 5.0, -1.0, -4.0, -1.0, 2.1, 0.0, 0.0, 0.7, -10.7, 0.0, 0.0, 0.0, 0.0, 0.8, 8.0 };
	MultimodalEqualizerAxisInterpolation(j, 0, 5, freqM, gainsM);
	MultimodalEqualizerEnable(j, 1);
}
static void pfStereo(JamesDSPLib *j) { StereoEnhancementSetParam(j, 0.9f); StereoEnhancementEnable(j); }
static void pfReverb(JamesDSPLib *j) { Reverb_SetParam(j, 4); ReverbEnable(j); }
static void pfCrossfeed(JamesDSPLib *j) { CrossfeedChangeMode(j, 1); CrossfeedEnable(j, 1); }
static void pfArbEq(JamesDSPLib *j)
{
	static char s[] = "perf: 20 -2; 100 1.5; 1000 -3; 5000 2.5; 15000 -1.5; 20000 0;";
	ArbitraryResponseEqualizerStringParser(j, s);
	ArbitraryResponseEqualizerEnable(j, 1);
}

static int perfRun(void)
{
	int fails = 0;
	double ns;
	int run;
	printf("[perf] ns/sample at 48 kHz; silent-phase shape after loud audio\n");
	JamesDSPGlobalMemoryAllocation();
	struct { const char *name; void (*setup)(JamesDSPLib *); } cs[] = {
		{ "baseline", 0 },
		{ "vacuum tube", pfTube },
		{ "bass boost", pfDbb },
		{ "compander", pfComp },
		{ "mEQ", pfMeq },
		{ "arbEq", pfArbEq },
		{ "widening", pfStereo },
		{ "reverb", pfReverb },
		{ "crossfeed", pfCrossfeed },
	};
	for (unsigned k = 0; k < sizeof(cs) / sizeof(cs[0]); k++)
		if (!perfSubject(cs[k].name, cs[k].setup, &ns, &run))
			fails++;
	JamesDSPGlobalMemoryDeallocation();
	printf(fails ? "FAIL (%d)\n" : "PASS\n", fails);
	return fails != 0;
}
// ---- FIR-eq mode (-7): phase mode + per-ear curves --------------------------
// Acceptance for docs/PLAN.md Phase 6 (report K14; ports of source
// tools/percheq_harness.c and tools/arbphase_harness.c). The arbitrary
// magnitude EQ must (a) take a curve per ear through the 2x2 convolver it has
// always had, (b) rebuild its coefficient generator on a minimum/linear phase
// switch and keep the magnitude within +-0.1 dB while the impulse becomes
// symmetric, and (c) survive 200 phase switches - each changes the filter
// length 8192 <-> 16383, which used to refresh the wrong segment count and
// read past the per-ear buffer - without a loudness jump. Heap overruns are
// caught by running this same mode in the GOLDEN_ASAN=1 build (Phase 2
// pattern: functional pass here, clean ASAN exit there).
#define EQ_N 4096
#define EQ_FS 48000.0
#define EQ_SINE_RMS_DB (-15.05) /* 20*log10(0.25/sqrt(2)) */

static int eqFails = 0;
static void eqCheck(const char *what, double got, double lo, double hi)
{
	int ok = got >= lo && got <= hi;
	if (!ok)
		eqFails++;
	printf("  %-52s %10.4f  [%.4f, %.4f]  %s\n", what, got, lo, hi, ok ? "ok" : "FAIL");
}

static JamesDSPLib *eqPrepare(void)
{
	JamesDSPLib *lib = (JamesDSPLib *)calloc(1, sizeof(JamesDSPLib));
	JamesDSPInit(lib, EQ_N, (float)EQ_FS);
	return lib;
}

/* The filter is 8192-16383 taps against a 4096-sample block, so a single
   block measures the convolver filling up rather than its response: push
   enough blocks for it to settle, keeping the tone's phase continuous across
   them, and measure the last one (source percheq_harness.c approach). */
static long eqOscPhase = 0;
static void eqLevelDb(JamesDSPLib *lib, double freq, double *lDb, double *rDb)
{
	double sumL = 0.0, sumR = 0.0;
	eqOscPhase = 0;
	for (int block = 0; block < 6; block++)
	{
		sumL = sumR = 0.0;
		for (int i = 0; i < EQ_N; i++, eqOscPhase++)
		{
			float s = (float)(0.25 * sin(2.0 * M_PI * freq * (double)eqOscPhase / EQ_FS));
			lib->tmpBuffer[0][i] = s;
			lib->tmpBuffer[1][i] = s;
		}
		ArbitraryResponseEqualizerProcess(lib, EQ_N);
		for (int i = 0; i < EQ_N; i++)
		{
			sumL += (double)lib->tmpBuffer[0][i] * lib->tmpBuffer[0][i];
			sumR += (double)lib->tmpBuffer[1][i] * lib->tmpBuffer[1][i];
		}
	}
	double rmsL = sqrt(sumL / EQ_N), rmsR = sqrt(sumR / EQ_N);
	*lDb = 20.0 * log10(rmsL > 1e-12 ? rmsL : 1e-12);
	*rDb = 20.0 * log10(rmsR > 1e-12 ? rmsR : 1e-12);
}

/* First EQ_N taps of the convolver's impulse response, straight off the
   process path (what actually plays), L<-L and R<-R. The segment ring is
   flushed with silence first: whatever ran before (the sine blocks of a
   level measurement) still sits in the history and would bleed through the
   higher IR segments into the capture. */
static void eqImpulse(JamesDSPLib *lib, float *outL, float *outR)
{
	for (int block = 0; block < (int)lib->arbMag.instance.convState._segCount + 1; block++)
	{
		memset(lib->tmpBuffer[0], 0, EQ_N * sizeof(float));
		memset(lib->tmpBuffer[1], 0, EQ_N * sizeof(float));
		ArbitraryResponseEqualizerProcess(lib, EQ_N);
	}
	memset(lib->tmpBuffer[0], 0, EQ_N * sizeof(float));
	memset(lib->tmpBuffer[1], 0, EQ_N * sizeof(float));
	lib->tmpBuffer[0][0] = 1.0f;
	lib->tmpBuffer[1][0] = 1.0f;
	ArbitraryResponseEqualizerProcess(lib, EQ_N);
	memcpy(outL, lib->tmpBuffer[0], EQ_N * sizeof(float));
	memcpy(outR, lib->tmpBuffer[1], EQ_N * sizeof(float));
}

static double eqAsymmetry(const float *h, int n)
{
	double peak = 0.0, worst = 0.0;
	for (int i = 0; i < n; i++)
		if (fabs(h[i]) > peak)
			peak = fabs(h[i]);
	for (int i = 0; i < n / 2; i++)
	{
		double d = fabs(h[i] - h[n - 1 - i]);
		if (d > worst)
			worst = d;
	}
	return peak > 0.0 ? worst / peak : 1.0;
}

static int firEqRun(void)
{
	static char curveLeft[]  = "GraphicEQ: 20 0; 500 0; 1000 12; 2000 0; 20000 0";
	static char curveRight[] = "GraphicEQ: 20 0; 500 0; 1000 -12; 2000 0; 20000 0";
	static char curveFlat[]  = "GraphicEQ: 20 0; 20000 0";
	static char curveShape[] = "GraphicEQ: 20 4; 200 -2; 1000 3; 5000 -4; 20000 0";
	static const double freqs[] = { 100.0, 1000.0, 5000.0, 10000.0 };
	double lDb, rDb;

	setvbuf(stdout, NULL, _IONBF, 0);
	printf("[fireq] per-ear curves + min/linear phase (report K14)\n");
	JamesDSPGlobalMemoryAllocation();

	printf(" each ear gets its own curve\n");
	{
		JamesDSPLib *lib = eqPrepare();
		ArbitraryResponseEqualizerStringParserStereo(lib, curveLeft, curveRight);
		ArbitraryResponseEqualizerEnable(lib, 1);
		eqLevelDb(lib, 1000.0, &lDb, &rDb);
		printf("  left %.2f dB, right %.2f dB\n", lDb, rDb);
		/* 24 dB asked for; the FIR will not hit it exactly at one frequency,
		   but anything under half of it means the curves are being mixed
		   rather than kept apart. */
		eqCheck("left louder than right by about 24 dB", lDb - rDb, 14.0, 30.0);
		eqCheck("left really was boosted", lDb, -18.0, 0.0);
		eqCheck("right really was cut", rDb, -40.0, -20.0);
		JamesDSPFree(lib);
		free(lib);
	}

	printf(" the same curve on both ears matches the single-curve path\n");
	{
		JamesDSPLib *st = eqPrepare();
		ArbitraryResponseEqualizerStringParserStereo(st, curveLeft, curveLeft);
		ArbitraryResponseEqualizerEnable(st, 1);
		double stL, stR;
		eqLevelDb(st, 1000.0, &stL, &stR);
		JamesDSPFree(st);
		free(st);

		JamesDSPLib *mo = eqPrepare();
		ArbitraryResponseEqualizerStringParser(mo, curveLeft);
		ArbitraryResponseEqualizerEnable(mo, 1);
		double moL, moR;
		eqLevelDb(mo, 1000.0, &moL, &moR);
		JamesDSPFree(mo);
		free(mo);

		printf("  stereo path L %.3f R %.3f, mono path L %.3f\n", stL, stR, moL);
		eqCheck("both ears match each other", fabs(stL - stR), 0.0, 0.05);
		eqCheck("and match the single-curve path", fabs(stL - moL), 0.0, 0.05);
	}

	printf(" a flat curve on one side leaves that side alone\n");
	{
		JamesDSPLib *lib = eqPrepare();
		ArbitraryResponseEqualizerStringParserStereo(lib, curveFlat, curveRight);
		ArbitraryResponseEqualizerEnable(lib, 1);
		eqLevelDb(lib, 1000.0, &lDb, &rDb);
		printf("  flat side %.2f dB, cut side %.2f dB (input %.2f)\n", lDb, rDb, EQ_SINE_RMS_DB);
		eqCheck("the flat side is left alone", fabs(lDb - EQ_SINE_RMS_DB), 0.0, 0.2);
		eqCheck("the cut side is well below it", EQ_SINE_RMS_DB - rDb, 6.0, 30.0);
		JamesDSPFree(lib);
		free(lib);
	}

	printf(" swapping the sides swaps the result\n");
	{
		JamesDSPLib *lib = eqPrepare();
		ArbitraryResponseEqualizerStringParserStereo(lib, curveRight, curveLeft);
		ArbitraryResponseEqualizerEnable(lib, 1);
		eqLevelDb(lib, 1000.0, &lDb, &rDb);
		printf("  left %.2f dB, right %.2f dB\n", lDb, rDb);
		eqCheck("now the right is the loud one", rDb - lDb, 14.0, 30.0);
		JamesDSPFree(lib);
		free(lib);
	}

	printf(" re-sending a per-channel pair does not drift\n");
	{
		JamesDSPLib *lib = eqPrepare();
		double firstL = 0.0, firstR = 0.0;
		for (int pass = 0; pass < 4; pass++)
		{
			ArbitraryResponseEqualizerStringParserStereo(lib, curveLeft, curveRight);
			ArbitraryResponseEqualizerEnable(lib, 1);
			eqLevelDb(lib, 1000.0, &lDb, &rDb);
			if (!pass)
			{
				firstL = lDb;
				firstR = rDb;
			}
			else
			{
				eqCheck("left unchanged on a repeat", fabs(lDb - firstL), 0.0, 0.5);
				eqCheck("right unchanged on a repeat", fabs(rDb - firstR), 0.0, 0.5);
			}
		}
		JamesDSPFree(lib);
		free(lib);
	}

	printf(" a forceRefresh reload keeps the per-ear pair (kept filters)\n");
	{
		JamesDSPLib *lib = eqPrepare();
		ArbitraryResponseEqualizerStringParserStereo(lib, curveLeft, curveRight);
		ArbitraryResponseEqualizerEnable(lib, 1);
		lib->arbMagForceRefresh = 1; // what JamesDSPSetSampleRate sets
		ArbitraryResponseEqualizerEnable(lib, 1);
		eqLevelDb(lib, 1000.0, &lDb, &rDb);
		printf("  after refresh: left %.2f dB, right %.2f dB\n", lDb, rDb);
		eqCheck("per-ear separation survives the refresh", lDb - rDb, 14.0, 30.0);
		JamesDSPFree(lib);
		free(lib);
	}

	printf(" minimum vs linear phase: same magnitude, different impulse\n");
	double minLevels[4], linLevels[4];
	{
		JamesDSPLib *lib = eqPrepare();
		eqCheck("minimum-phase filter is 8192 taps", lib->arbMag.instance.filterLen, 8192.0, 8192.0);
		ArbitraryResponseEqualizerStringParser(lib, curveShape);
		ArbitraryResponseEqualizerEnable(lib, 1);
		for (int i = 0; i < 4; i++)
		{
			double a, b;
			eqLevelDb(lib, freqs[i], &a, &b);
			minLevels[i] = a;
		}
		/* Symmetry of a linear-phase FIR is about the centre of the whole
		   filter (h[i] vs h[len-1-i]); the largest one is 16383 taps. */
		static float hFull[16384];
		float *h = lib->arbMag.instance.coeffGen.GetFilter(&lib->arbMag.instance.coeffGen, (float)lib->fs);
		memcpy(hFull, h, lib->arbMag.instance.filterLen * sizeof(float));
		double asymMin = eqAsymmetry(hFull, (int)lib->arbMag.instance.filterLen);
		ArbitraryResponseEqualizerSetPhaseMode(lib, 1);
		eqCheck("linear-phase filter is 16383 taps", lib->arbMag.instance.filterLen, 16383.0, 16383.0);
		ArbitraryResponseEqualizerStringParser(lib, curveShape);
		for (int i = 0; i < 4; i++)
		{
			double a, b;
			eqLevelDb(lib, freqs[i], &a, &b);
			linLevels[i] = a;
		}
		h = lib->arbMag.instance.coeffGen.GetFilter(&lib->arbMag.instance.coeffGen, (float)lib->fs);
		memcpy(hFull, h, lib->arbMag.instance.filterLen * sizeof(float));
		double asymLin = eqAsymmetry(hFull, (int)lib->arbMag.instance.filterLen);
		eqCheck("linear impulse is symmetric", asymLin, 0.0, 1e-3);
		eqCheck("minimum impulse is not symmetric", asymMin, 0.01, 2.0);
		for (int i = 0; i < 4; i++)
		{
			char what[96];
			snprintf(what, sizeof(what), "magnitude match @ %.0f Hz (min vs linear)", freqs[i]);
			eqCheck(what, fabs(minLevels[i] - linLevels[i]), 0.0, 0.1);
		}
		JamesDSPFree(lib);
		free(lib);
	}

	printf(" 200 phase switches: response follows, level stays put\n");
	{
		JamesDSPLib *lib = eqPrepare();
		ArbitraryResponseEqualizerStringParser(lib, curveShape);
		ArbitraryResponseEqualizerEnable(lib, 1);
		double refL, refR;
		eqLevelDb(lib, 1000.0, &refL, &refR);

		/* what a convolver built at each phase from the start must answer:
		   capture both references once, then compare after every 25th switch */
		ArbitraryResponseEqualizerSetPhaseMode(lib, 0);
		ArbitraryResponseEqualizerStringParser(lib, curveShape);
		float refMinL[EQ_N], refMinR[EQ_N], refLinL[EQ_N], refLinR[EQ_N], gotL[EQ_N], gotR[EQ_N];
		eqImpulse(lib, refMinL, refMinR);
		ArbitraryResponseEqualizerSetPhaseMode(lib, 1);
		ArbitraryResponseEqualizerStringParser(lib, curveShape);
		eqImpulse(lib, refLinL, refLinR);

		double worstJump = 0.0, worstDiff = 0.0;
		for (int sw = 0; sw < 200; sw++)
		{
			ArbitraryResponseEqualizerSetPhaseMode(lib, sw % 2);
			ArbitraryResponseEqualizerStringParser(lib, curveShape);
			ArbitraryResponseEqualizerEnable(lib, 1);
			eqLevelDb(lib, 1000.0, &lDb, &rDb);
			double jump = fabs(lDb - refL);
			if (jump > worstJump)
				worstJump = jump;
			if (sw % 25 == 24)
			{
				const float *rl = sw % 2 ? refLinL : refMinL;
				const float *rr = sw % 2 ? refLinR : refMinR;
				double d = 0.0;
				eqImpulse(lib, gotL, gotR);
				for (int i = 0; i < EQ_N; i++)
				{
					double a = fabs(gotL[i] - rl[i]);
					double b = fabs(gotR[i] - rr[i]);
					if (a > d) d = a;
					if (b > d) d = b;
				}
				if (d > worstDiff)
					worstDiff = d;
			}
		}
		printf("  worst level jump %.4f dB, worst impulse diff %.3g\n", worstJump, worstDiff);
		eqCheck("no loudness jump across 200 switches", worstJump, 0.0, 0.5);
		eqCheck("switched convolver matches fresh-built", worstDiff, 0.0, 1e-3);
		JamesDSPFree(lib);
		free(lib);
	}

	JamesDSPGlobalMemoryDeallocation();
	printf(eqFails ? "FAIL (%d)\n" : "PASS\n", eqFails);
	return eqFails != 0;
}
// -----------------------------------------------------------------------------

int main(int argc, char **argv)
{
	if (argc >= 2 && atoi(argv[1]) == -4)
	{
		return asrcGuardRun();
	}
	if (argc >= 2 && atoi(argv[1]) == -5)
	{
		JamesDSPGlobalMemoryAllocation();
		int rc = nanLatchRun();
		JamesDSPGlobalMemoryDeallocation();
		return rc;
	}
	if (argc >= 2 && atoi(argv[1]) == -6)
	{
		return perfRun();
	}
	if (argc >= 2 && atoi(argv[1]) == -7)
	{
		return firEqRun();
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
		       "       golden -4\n"
		       "       golden -5\n"
		       "       golden -6\n"
		       "       golden -7\n");
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
