#include "../Utils.h"
#define JNI_LOG_TAG "ReduceNoise"

#include "ReduceNoise.h"

#include <string.h>
#include <math.h>
#include <float.h>

extern void __ogg_fdrffti(int, float*, int*);
extern void __ogg_fdrfftf(int, float*, float*, int*);
extern void __ogg_fdrfftb(int, float*, float*, int*);

/////////
// FFT //
/////////

#define FFT_FREQUENCY_BUCKETS ((FFT_SAMPLES - 2) / 2) // The number of frequency buckets that result from FFT_SAMPLES
#define FFT_NOISE_THRESHOLD_MAX 3072                  // Consider anything under this threshold to be noise
#define FFT_NOISE_THRESHOLD_MIN 128                   // Don't consider actual silence to be noise
#define FFT_NOISE_MIN_SIZE 4096	                      // This ensures we read at least 3 Hann windows at a time
#define FFT_SMOOTH_SAMPLES 2                          // Used for frequency smoothing (2 * FFT_SMOOTH_SAMPLES + 1)
#define FFT_STORED_WINDOWS 16                         // Keep a history of Hann windows to better identify the frequencies in the noise
#define FFT_ATTACK 0.2f                               // How quickly to reduce the volume of noise frequencies
#define FFT_DECAY  0.2f                               // How quickly to back off the volume reduction

// This buffer will hold the input samples, because we apply the noise reduction on Hann-window
// sized segments we almost always have some samples left over that haven't been processed
static short* samplesSrc = NULL;
static unsigned int samplesSrcBufferSize = 0;
static unsigned int samplesSrcLeftover = 0;

// Hann multipliers 
static float* hannWindowMultipliers = NULL;

struct FFTWindow
{
	float frequencies[2][FFT_FREQUENCY_BUCKETS];
};

static FFTWindow* storedFFTWindows = NULL;                 // Stores the forward transformed Hann-windowed noise frequencies
static unsigned int storedFFTWindowsCount[2] = {0, 0};     // The number of stored windows
static unsigned int storedFFTWindowsNextIndex[2] = {0, 0}; // The next window to replace (we only keep FFT_STORED_WINDOWS)
static float* meanPowers[2] = {NULL, NULL};                // The average power of the noise frequency buckets

static float* fftTransformation = NULL;                       // We use this array to forward/backwards FFT transform
static float* frequencyMultipliers = NULL;                    // The amount to decrease the frequency buckets
static float* previousFrequencyMultipliers[2] = {NULL, NULL}; // Keep track of the last frequency reduction multipliers

// FFTPack variables
static float* wsave = NULL;
static int* ifac = NULL;

static short previousFinalHalfHannWindow[(FFT_SAMPLES / 2) * 2]; // Store the last half of the final Hann window
static bool havePreviousFinalHalfHannWindow = false;             // Set to true when the above array is full

// When this is set, gracefully end noise reduction
static bool cleanupReduceNoise = false;

void ResetReduceNoise()
{
	// Allocate memory if it hasn't already been done
	if (hannWindowMultipliers == NULL)
	{
		hannWindowMultipliers = (float*)malloc(FFT_SAMPLES * sizeof(float));
		for (unsigned int i = 0; i < FFT_SAMPLES; ++i)
			hannWindowMultipliers[i] = 0.5f * (1.0f - cosf((TWO_PI * (float)i) / (FFT_SAMPLES - 1)));

		storedFFTWindows = (FFTWindow*)malloc(FFT_STORED_WINDOWS * sizeof(FFTWindow));
		meanPowers[0] = (float*)malloc(FFT_FREQUENCY_BUCKETS * sizeof(float));
		meanPowers[1] = (float*)malloc(FFT_FREQUENCY_BUCKETS * sizeof(float));

		fftTransformation = (float*)malloc(FFT_SAMPLES * sizeof(float));
		frequencyMultipliers = (float*)malloc(FFT_FREQUENCY_BUCKETS * sizeof(float));
		previousFrequencyMultipliers[0] = (float*)malloc(FFT_FREQUENCY_BUCKETS * sizeof(float));
		previousFrequencyMultipliers[1] = (float*)malloc(FFT_FREQUENCY_BUCKETS * sizeof(float));

		wsave = (float*)malloc((4 * FFT_SAMPLES + 15) * sizeof(float));
		ifac = (int*)malloc(8 * sizeof(int) - 1);
		__ogg_fdrffti(FFT_SAMPLES, wsave, ifac);
	}

	// Reset variables
	samplesSrcLeftover = 0;
	storedFFTWindowsCount[0] = storedFFTWindowsCount[1] = 0;
	storedFFTWindowsNextIndex[0] = storedFFTWindowsNextIndex[1] = 0;
	havePreviousFinalHalfHannWindow = false;
	cleanupReduceNoise = false;

	for (unsigned int i = 0; i < FFT_FREQUENCY_BUCKETS; ++i)
	{
		previousFrequencyMultipliers[0][i] = 1.0f;
		previousFrequencyMultipliers[1][i] = 1.0f;
	}
}

void SetCleanupReduceNoise(bool cleanup)
{
	cleanupReduceNoise = cleanup;
}

bool NeedCleanupReduceNoise()
{
	return cleanupReduceNoise;
}

/////////
// FFT //
/////////

// Iterates over the noise samples, at every half Hann window offset takes FFT_SAMPLES and calculates
// the power of the frequency buckets
// It then averages the power of the frequency buckets for all the stored Hann windows
void TrainFFT(short* samples, unsigned int numSamples, bool stereo, unsigned int index)
{
	unsigned int offset = stereo ? 2 : 1;

	// Iterate over the available Hann windows (at FFTSAMPLES / 2 offsets)
	unsigned int windowsAvailable = 2 * (unsigned int)floorf((float)numSamples / (float)FFT_SAMPLES) - 1;
	for (unsigned int i = 0; i < windowsAvailable; ++i)
	{
		// Take the samples and multiply them by the Hann window multipliers
		for (unsigned int j = 0; j < FFT_SAMPLES; ++j)
			fftTransformation[j] = hannWindowMultipliers[j] * (float)samples[(i * (FFT_SAMPLES / 2) + j) * offset];

		// Forward transform the windowed samples to get the frequency buckets
		__ogg_fdrfftf(FFT_SAMPLES, fftTransformation, wsave, ifac);

		// Calculate the power of each frequency bucket
		// NOTE: The first index is just an offset, it is then followed by FFT_FREQUENCY_BUCKETS complex pairs
		FFTWindow& fftWindow = storedFFTWindows[storedFFTWindowsNextIndex[index]];
		for (unsigned int j = 0; j < FFT_FREQUENCY_BUCKETS; ++j)
			fftWindow.frequencies[index][j] = sqrtf(fftTransformation[1 + 2 * j] * fftTransformation[1 + 2 * j] + fftTransformation[1 + 2 * j + 1] * fftTransformation[1 + 2 * j + 1]);

		// Increment the total stored window count, advance the next window index
		++storedFFTWindowsCount[index];
		++storedFFTWindowsNextIndex[index];
		if (storedFFTWindowsNextIndex[index] == FFT_STORED_WINDOWS)
			storedFFTWindowsNextIndex[index] = 0;
	}

	// Average the powers from the stored windows and save the average in meanPowers
	unsigned int storedWindows = storedFFTWindowsCount[index] > FFT_STORED_WINDOWS ? FFT_STORED_WINDOWS : storedFFTWindowsCount[index];
	float invStoredWindows = 1.0f / (float)storedWindows;

	FFTWindow& fftWindow = storedFFTWindows[0];
	for (unsigned int j = 0; j < FFT_FREQUENCY_BUCKETS; ++j)
		meanPowers[index][j] = invStoredWindows * fftWindow.frequencies[index][j];
	for (unsigned int i = 0; i < storedWindows - 1; ++i)
	{
		FFTWindow& fftWindow = storedFFTWindows[i + 1];
		for (unsigned int j = 0; j < FFT_FREQUENCY_BUCKETS; ++j)
			meanPowers[index][j] += invStoredWindows * fftWindow.frequencies[index][j];
	}
}

// Smooths out the sequence of "num" floats in "buffer"
void Smooth(float* buffer, unsigned int num)
{
	float previousSamples[FFT_SMOOTH_SAMPLES];
	for (unsigned int i = 0; i < FFT_SMOOTH_SAMPLES; ++i)
		previousSamples[i] = buffer[0];

	float averageTotal = buffer[0];
	for (unsigned int i = 0; i < FFT_SMOOTH_SAMPLES; ++i)
	{
		averageTotal += previousSamples[i];
		averageTotal += buffer[1 + i];
	}

	float invSmooth = 1.0f / (float)(2 * FFT_SMOOTH_SAMPLES + 1);
	for (unsigned int i = 0; i < num - FFT_SMOOTH_SAMPLES; ++i)
	{
		buffer[i] = invSmooth * averageTotal;

		averageTotal -= previousSamples[0];
		averageTotal += buffer[i + FFT_SMOOTH_SAMPLES + 1];

		for (unsigned int j = 0; j < FFT_SMOOTH_SAMPLES - 1; ++j)
			previousSamples[j] = previousSamples[(j + 1) ];
		previousSamples[(FFT_SMOOTH_SAMPLES - 1)] = buffer[i];
	}
}

// Iterates over the samples from "samplesSrc" and one Hann window at a time (at an offset of FFT_SAMPLES / 2) it reduces the 
// noise using spectral noise gating and writes the noise-reduced samples back out to "samplesDst"
unsigned int ApplyFFT(short* samplesDst, unsigned int numSamplesDst, short* samplesSrc, unsigned int numSamplesSrc, bool stereo, unsigned int index)
{
	unsigned int offset = stereo ? 2 : 1;

	// Move forward one Hann window at a time until we hit the end of one of the buffers
	float invFFTSamples = 1.0f / (float)FFT_SAMPLES;
	unsigned int currentSample = 0;
	while ((currentSample + FFT_SAMPLES) <= numSamplesDst && (currentSample + FFT_SAMPLES) <= numSamplesSrc)
	{
		short* currentSamplesSrc = samplesSrc + (currentSample * offset);
		short* currentSamplesDst = samplesDst + (currentSample * offset);

		// Forward transform the source samples multiplied by a Hann window
		bool isClipped = false;
		for (unsigned int i = 0; i < FFT_SAMPLES; ++i)
		{
			short sample = currentSamplesSrc[i * offset];
			fftTransformation[i] = hannWindowMultipliers[i] * (float)sample;
			if (sample == SHRT_MAX || sample == SHRT_MIN)
				isClipped = true;
		}

		// Don't apply noise reduction to clipped samples
		if (isClipped)
		{
			for (unsigned int i = 0; i < FFT_SAMPLES; ++i)
				currentSamplesDst[i * offset] += (short)fftTransformation[i];

			currentSample += FFT_SAMPLES / 2;
			continue;
		}

		__ogg_fdrfftf(FFT_SAMPLES, fftTransformation, wsave, ifac);

		// The first value holds the offset, reduce it to properly center the waveform
		fftTransformation[0] *= 0.1f;

		// Set up frequency bucket multipliers
		for (unsigned int i = 0; i < FFT_FREQUENCY_BUCKETS; ++i)
		{
			// Compare the power of the current frequency bucket to the average power of the noise frequency bucket
			float meanPower = meanPowers[index][i];
			float power = sqrtf(fftTransformation[1 + i * 2] * fftTransformation[1 + i * 2] + fftTransformation[1 + i * 2 + 1] * fftTransformation[1 + i * 2 + 1]);

			// If the power is lower than a certain multiple of the noise power
			// Set the frequency multiplier to less than 1.0f
			float percent = 1.0f;
			if (power < 2.5f * meanPower)
			{
				if (power > 1.5f * meanPower)
					percent = (power - 1.5f * meanPower) / meanPower;
				else
					percent = 0.0f;
			}

			frequencyMultipliers[i] = (1.0f - percent) * 0.1f + percent * 1.0f;
		}

		// Limit the rate of change of the frequency multipliers to FFT_ATTACK/FFT_DECAY
		// This avoids noise artifacts caused by turning on/off the noise gates too frequently
		float multiplier = (float)(storedFFTWindowsCount[index] > FFT_STORED_WINDOWS ? FFT_STORED_WINDOWS : storedFFTWindowsCount[index]) / (float)FFT_STORED_WINDOWS;
		for (unsigned int i = 0; i < FFT_FREQUENCY_BUCKETS; ++i)
		{
			float difference = frequencyMultipliers[i] - previousFrequencyMultipliers[index][i];
			if (difference > FFT_DECAY)
				difference = FFT_DECAY;
			else if (difference < -FFT_ATTACK)
				difference = -FFT_ATTACK;
			difference *= multiplier;

			frequencyMultipliers[i] = previousFrequencyMultipliers[index][i] + difference;
			previousFrequencyMultipliers[index][i] = frequencyMultipliers[i];
		}

		// Smooth the frequency multipliers so that no frequencies are reduced in isolation
		Smooth(frequencyMultipliers, FFT_FREQUENCY_BUCKETS);

		// Multiply the frequency buckets of the source samples by the multipliers then re-construct the samples
		for (unsigned int i = 0; i < FFT_FREQUENCY_BUCKETS; ++i)
		{
			fftTransformation[1 + i * 2] *= frequencyMultipliers[i];
			fftTransformation[1 + i * 2 + 1] *= frequencyMultipliers[i];
		}

		__ogg_fdrfftb(FFT_SAMPLES, fftTransformation, wsave, ifac);

		// Write out the final noise-reduced samples to the destination buffer
		// We need to multiply by invFFTSamples because the results of __ogg_fdrfftb
		// are multiplied by FFT_SAMPLES
		for (unsigned int i = 0; i < FFT_SAMPLES; ++i)
			currentSamplesDst[i * offset] += (short)(invFFTSamples * fftTransformation[i]);

		currentSample += FFT_SAMPLES / 2;
	}

	return currentSample + FFT_SAMPLES / 2;
}

extern bool FindSilence(short*, unsigned int, unsigned int, unsigned int, unsigned int&, unsigned int&, unsigned int&);

unsigned char* ReduceNoise(unsigned char* buffer, size_t& size, unsigned int channels)
{
	bool stereo = channels == 2;
	short* samples = (short*)buffer;
	unsigned int samplesCount = size / sizeof(short);

	/////////
	// FFT //
	/////////

	unsigned int samplesSrcCount = samplesCount + samplesSrcLeftover;
	if (samplesSrcBufferSize < samplesSrcCount)
	{
		// If the buffer isn't big enough increase its size
		short* samplesSrcOld = samplesSrc;
		samplesSrc = (short*)malloc(samplesSrcCount * sizeof(short));
		samplesSrcBufferSize = samplesSrcCount;

		// Transfer the existing samples from the old buffer to the new
		if (samplesSrcOld != NULL)
		{
			memcpy(samplesSrc, samplesSrcOld, samplesSrcLeftover * sizeof(short));
			free(samplesSrcOld);
		}
	}

	memcpy(samplesSrc + samplesSrcLeftover, samples, samplesCount * sizeof(short));

	// When shutting down noise reduction we need to deal with:
	// 1. The leftover samples from last time
	// 2. The previous half Hann window
	if (cleanupReduceNoise)
	{
		cleanupReduceNoise = false;

		size = (samplesSrcLeftover + samplesCount) * sizeof(short);

		if (havePreviousFinalHalfHannWindow)
		{
			// Fade from the noise reduced samples in previousFinalHalfHannWindow to the
			// non-noise-reduced samples in samplesSrc
			if (stereo)
			{
				unsigned int offset = 2;
				for (unsigned int i = 0; i < FFT_SAMPLES / 2; ++i)
				{
					float hann = hannWindowMultipliers[FFT_SAMPLES / 2 + i];
					samplesSrc[i * offset]     = (short)((1.0f - hann) * (float)samplesSrc[i * offset]     + hann * previousFinalHalfHannWindow[i * offset]);
					samplesSrc[i * offset + 1] = (short)((1.0f - hann) * (float)samplesSrc[i * offset + 1] + hann * previousFinalHalfHannWindow[i * offset + 1]);
				}
			}
			else
			{
				for (unsigned int i = 0; i < FFT_SAMPLES / 2; ++i)
				{
					float hann = hannWindowMultipliers[FFT_SAMPLES / 2 + i];
					samplesSrc[i] = (short)((1.0f - hann) * samplesSrc[i] + hann * previousFinalHalfHannWindow[i]);
				}
			}
		}

		samplesSrcLeftover = 0;
		havePreviousFinalHalfHannWindow = false;

		for (unsigned int i = 0; i < FFT_FREQUENCY_BUCKETS; ++i)
		{
			previousFrequencyMultipliers[0][i] = 1.0f;
			previousFrequencyMultipliers[1][i] = 1.0f;
		}

		return (unsigned char*)samplesSrc;
	}

	// Train FFT
	unsigned int silenceStart = 0;
	unsigned int silenceNumSamples = 0;
	unsigned int silenceMax = 0;

	// FindSilence() in this case looks for noise under FFT_NOISE_THRESHOLD_MAX
	if (FindSilence(samples, samplesCount, FFT_NOISE_THRESHOLD_MAX, stereo ? FFT_NOISE_MIN_SIZE : FFT_NOISE_MIN_SIZE / 2, silenceStart, silenceNumSamples, silenceMax))
	{
		if (silenceMax > FFT_NOISE_THRESHOLD_MIN)
		{
			if (stereo)
			{
				TrainFFT(samples + silenceStart, silenceNumSamples / 2, true, 0);
				TrainFFT(samples + silenceStart + 1, silenceNumSamples / 2, true, 1);
			}
			else
			{
				TrainFFT(samples + silenceStart, silenceNumSamples, false, 0);
			}
		}
	}

	// If noise reduction hasn't been trained just return the unmodified buffer
	if (storedFFTWindowsCount[0] == 0)
	{
		ASSERT(samplesSrcLeftover == 0);
		return (unsigned char*)samples;
	}

	// Allocate memory for destination buffer
	static unsigned int samplesDstSamplesCount = 0;
	static short* samplesDst = NULL;
	if (samplesDstSamplesCount < samplesSrcCount)
	{
		samplesDst = (short*)malloc(samplesSrcCount * sizeof(short));
		samplesDstSamplesCount = samplesSrcCount;
	}
	samples = samplesDst;

	// Because we add overlapped Hann windows into the destination buffer, set it to zero
	memset(samplesDst, 0, samplesDstSamplesCount * sizeof(short));

	// Apply the noise reduction
	unsigned int samplesDstCount = 0;
	if (stereo)
	{
		unsigned int offset = 2;

		// Initialize the start of the buffer with the last half-Hann window from the last time we did noise reduction
		// If there is no previous window just set up the start of the buffer with the noisy samples
		if (havePreviousFinalHalfHannWindow)
		{
			memcpy(samplesDst, previousFinalHalfHannWindow, (FFT_SAMPLES / 2) * 2 * sizeof(short));
		}
		else
		{
			for (unsigned int i = 0; i < FFT_SAMPLES / 2; ++i)
			{
				samplesDst[i * offset] =     (short)(hannWindowMultipliers[FFT_SAMPLES / 2 + i] * samplesSrc[i * offset]);
				samplesDst[i * offset + 1] = (short)(hannWindowMultipliers[FFT_SAMPLES / 2 + i] * samplesSrc[i * offset + 1]);
			}
		}

		// Apply the noise reduction to each channel
		unsigned int samplesProcessedLeft = ApplyFFT(samplesDst, samplesDstSamplesCount / 2, samplesSrc, samplesSrcCount / 2, true, 0);
		unsigned int samplesProcessedRight = ApplyFFT(samplesDst + 1, samplesDstSamplesCount / 2, samplesSrc + 1, samplesSrcCount / 2, true, 1);
		ASSERT(samplesProcessedLeft == samplesProcessedRight);

		// Store the last half of the final Hann window for next time
		if (samplesProcessedLeft > 0 || samplesProcessedRight > 0)
		{
			memcpy(previousFinalHalfHannWindow, &samplesDst[(samplesProcessedLeft - FFT_SAMPLES / 2) * offset], (FFT_SAMPLES / 2) * 2 * sizeof(short));
			samplesDstCount = (samplesProcessedLeft - FFT_SAMPLES / 2) * 2;
			havePreviousFinalHalfHannWindow = true;
		}

		size = samplesDstCount * sizeof(short);
	}
	else
	{
		if (havePreviousFinalHalfHannWindow)
		{
			memcpy(samplesDst, previousFinalHalfHannWindow, (FFT_SAMPLES / 2) * sizeof(short));
		}
		else
		{
			for (unsigned int i = 0; i < FFT_SAMPLES / 2; ++i)
				samplesDst[i] = (short)(hannWindowMultipliers[FFT_SAMPLES / 2 + i] * samplesSrc[i]);
		}

		unsigned int samplesProcessed = ApplyFFT(samplesDst, samplesDstSamplesCount, samplesSrc, samplesSrcCount, false, 0);
		if (samplesProcessed > 0)
		{
			memcpy(previousFinalHalfHannWindow, &samplesDst[samplesProcessed - FFT_SAMPLES / 2], (FFT_SAMPLES / 2) * sizeof(short));
			samplesDstCount = samplesProcessed - FFT_SAMPLES / 2;
			havePreviousFinalHalfHannWindow = true;
		}

		size = samplesDstCount * sizeof(short);
	}

	samplesCount = samplesDstCount;

	// Store the unprocessed samples for next time
	samplesSrcLeftover = samplesSrcCount - samplesDstCount;
	if (samplesDstCount > 0)
		memmove(samplesSrc, samplesSrc + samplesDstCount, samplesSrcLeftover * sizeof(short));

	return (unsigned char*)samples;
}
