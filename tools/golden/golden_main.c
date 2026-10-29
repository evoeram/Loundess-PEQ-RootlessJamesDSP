/*
 * Golden-file harness for libjamesdsp core chain (docs/PLAN.md, P0.1).
 *
 * Generates a deterministic stereo signal, runs it through the engine with a
 * fixed parameter set, writes the output as 32-bit float WAV and prints an
 * FNV-1a hash of the raw PCM bytes. Two builds compared bit-exactly
 * (`cmp`) must produce identical hashes unless a change is intentional.
 *
 * Usage: golden <crossfeedMode> <out.wav> [ddcFile]
 *   crossfeedMode: 1 = BS2B path, 3 = convolver path
 *   ddcFile: optional .vdc string loaded into DDC (Main/CLI/Butterworth.vdc)
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

int main(int argc, char **argv)
{
	if (argc < 3)
	{
		printf("usage: golden <crossfeedMode> <out.wav>\n");
		return 1;
	}
	int xfMode = atoi(argv[1]);
	const char *outPath = argv[2];
	const char *ddcPath = argc > 3 ? argv[3] : 0;

	if (xfMode < 0)
	{
		// Parser-only mode: parse each .vdc argument, report PASS/FAIL.
		if (argc < 4)
		{
			printf("usage: golden -1 <file.vdc> [more.vdc ...]\n");
			return 1;
		}
		JamesDSPGlobalMemoryAllocation();
		int fails = 0;
		for (int a = 3; a < argc; a++)
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

	VacuumTubeSetGain(jdsp, 8.0);
	VacuumTubeEnable(jdsp);

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

	CrossfeedChangeMode(jdsp, xfMode);
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
