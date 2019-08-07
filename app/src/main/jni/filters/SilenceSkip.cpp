#include "../Utils.h"
#define JNI_LOG_TAG "SilenceSkip"

#include "../CustomMediaPlayer.h"
#include "SilenceSkip.h"
#include "ReduceNoise.h"

#include <stdlib.h>
#include <string.h>

// Define this to replace silence with zeros (or a tone) instead of skipping it
// #define REPLACE_SILENCE_WTH_TONE

#define SILENCE_THRESHOLD    196               // Detect silence as any sample with an absolute value below SILENCE_THRESHOLD
#define SILENCE_THRESHOLD_NR 128               // When noise reduction is active, don't be as aggressive with the silence detection
#define SILENCE_MIN_SIZE_SAMPLES (8 * 1024)    // Only detect a block of silence if SILENCE_THRESHOLD is met for SILENCE_MIN_SIZE_SAMPLES
#define SILENCE_SKIP_RATIO 0.2f                // What percent of the detected silence to skip over
#define SILENCE_FULL_SKIP_SAMPLES (256 * 1024) // After how many contiguous samples of silence do we reduce the skip ratio?

static unsigned int fullSkipSamples = 0;

void ResetSilenceSkip()
{
	fullSkipSamples = 0;
}

// Return true if a contiguous region of samples whose absolute value of is less than "silenceThreshold"
// is found of at least "silenceMinSize" length
bool FindSilence(short* samples, unsigned int numSamples, unsigned int silenceThreshold, unsigned int silenceMinSize, unsigned int& silenceStart, unsigned int& silenceNumSamples, unsigned int& silenceMax)
{
	silenceNumSamples = 0;
	silenceMax = 0;

	for (unsigned int i = 0; i < numSamples; ++i)
	{
		unsigned int sample = Abs((int)samples[i]);
		if (sample < silenceThreshold)
		{
			if (silenceNumSamples == 0)
				silenceStart = i;

			if (sample > silenceMax)
				silenceMax = sample;

			++silenceNumSamples;
		}
		else
		{
			if (silenceNumSamples > silenceMinSize)
				return true;

			silenceNumSamples = 0;
			silenceMax = 0;
		}
	}

	if (silenceNumSamples >= silenceMinSize)
		return true;
	else
		return false;
}

// Writes PCM data to a buffer, starts at "start" and interpolates to "end" in "numSamples" samples
// If "stereo" is set the samples are written two samples apart
void InterpolatePCM(short* samples, unsigned int numSamples, bool stereo, int start, int end)
{
	float sampleDelta = (end - start) / (float)(numSamples - 1);
	unsigned int offset = stereo ? 2 : 1;

	float sampleCurrent = (float)start;
	for (unsigned int i = 0; i < numSamples; ++i)
	{
		samples[i * offset] = (short)sampleCurrent;
		sampleCurrent += sampleDelta;
	}
}

// This function iterates over the "buffer" looking for areas of silence
// When silent areas are found the silent area is removed
// It uses a temp buffer "tempSamples" to create the silence-removed samples
// which are then copied back into "buffer" at the end and "size" is modified
void SilenceSkip(unsigned char* buffer, size_t& size, unsigned int channels)
{
	if (size == 0)
		return;

	bool stereo = channels == 2;

	// Set "size" to zero but keep track of what it originally was
	unsigned int originalSize = size;
	size = 0;

	// Variables to keep track of position in original buffer
	short* samples = (short*)buffer;
	unsigned int numSamples = originalSize / 2;
	unsigned int currentSample = 0;

	unsigned int silenceMinSize = stereo ? 2 * SILENCE_MIN_SIZE_SAMPLES : SILENCE_MIN_SIZE_SAMPLES;
	if (silenceMinSize > numSamples)
		silenceMinSize = numSamples;

	// Variables to keep track of position in temp buffer
	static short* tempSamples = NULL;
	static unsigned int numTempSamples = 0;
	unsigned int currentTempSample = 0;
	if (numSamples > numTempSamples)
	{
		LOG_D("realloc tempSamples %u -> %u samples\n", numTempSamples, numSamples);
		tempSamples = (short*)realloc(tempSamples, sizeof(short) * numSamples);
		numTempSamples = numSamples;
	}

	// Iterate through the "buffer" looking for areas with silence
	unsigned int silenceThreshold = IsReduceNoise() || NeedCleanupReduceNoise() ? SILENCE_THRESHOLD_NR : SILENCE_THRESHOLD;

	unsigned int silenceStart = 0;
	unsigned int silenceNumSamples = 0;
	unsigned int silenceMax = 0;
	while (FindSilence(samples + currentSample, numSamples - currentSample, silenceThreshold, silenceMinSize, silenceStart, silenceNumSamples, silenceMax))
	{
		LOG_I("Found silence at sample %u of size %u samples\n", currentSample + silenceStart, silenceNumSamples);
		if (silenceNumSamples == numSamples)
			fullSkipSamples += silenceNumSamples;
		else
			fullSkipSamples = 0;

		// Noise reduction keeps FFT_SAMPLES / 2 (per channel) from the last buffer, so make sure not modify any samples
		// in that area
		unsigned int fftSamples = stereo ? FFT_SAMPLES : FFT_SAMPLES / 2;
		bool noiseReduction = IsReduceNoise() || NeedCleanupReduceNoise();
		if (noiseReduction && (currentSample + silenceStart + silenceNumSamples > numSamples - fftSamples))
		{
			unsigned int reduction = (currentSample + silenceStart + silenceNumSamples) - (numSamples - fftSamples);
			if (reduction >= silenceNumSamples)
				break;

			silenceNumSamples -= reduction;
			if (silenceNumSamples < 4096)
				break;
		}

#if defined(REPLACE_SILENCE_WTH_TONE)
		// Replace the silence with a tone, don't modify size
		for (unsigned int i = silenceStart; i < silenceNumSamples; ++i)
		{
			static float currentSine = 0.0f;
			samples[currentSample + i] = (short)currentSine;
			samples[currentSample + i] /= 32;
			currentSine += 128.0f;
		}

		currentSample += silenceStart + silenceNumSamples;
#else
		if (stereo)
		{
			// Align silence area on two-sample boundary
			silenceStart += silenceStart % 2;
			ASSERT((silenceStart % 2) == 0);
			silenceNumSamples -= silenceNumSamples % 2;
			ASSERT((silenceNumSamples % 2) == 0);
		}

		if (silenceStart > 0)
		{
			// Copy the non-silence before the silence into the temp buffer
			LOG_V("Copying %u samples from %u to %u\n", silenceStart, currentSample, currentTempSample);
			memcpy(tempSamples + currentTempSample, samples + currentSample, silenceStart * sizeof(short));

			currentTempSample += silenceStart;
			size += 2 * silenceStart;
			currentSample += silenceStart;
		}

		// Replace the silent area with a crossfade between the first "crossfadeSamples" samples of the silence
		// and the last "crossfadeSamples" of the silence
		LOG_V("Crossfading at %u\n", currentTempSample);

		ASSERT(SILENCE_SKIP_RATIO <= 0.5f);
		float silenceSkipRatio = noiseReduction ? 0.5f * SILENCE_SKIP_RATIO : SILENCE_SKIP_RATIO;
		if (fullSkipSamples > SILENCE_FULL_SKIP_SAMPLES)
			silenceSkipRatio *= 0.1f;
		unsigned int crossfadeSamples = (unsigned int)((stereo ? silenceSkipRatio : 2 * silenceSkipRatio) * (float)silenceNumSamples);
		float percent = 0.0f;
		float step = 1.0f / (float)(crossfadeSamples - 1);
		if (stereo)
		{
			unsigned int offset = 2;
			unsigned int tempSamplesStart = currentTempSample / 2;
			unsigned int silenceCrossfadeStart = currentSample / 2;
			unsigned int silenceCrossfadeEnd = (currentSample + silenceNumSamples) / 2 - crossfadeSamples;
			for (unsigned int i = 0; i < crossfadeSamples; ++i)
			{
				tempSamples[(tempSamplesStart + i) * offset] =     (short)((1.0f - percent) * samples[(silenceCrossfadeStart + i) * offset]     + percent * samples[(silenceCrossfadeEnd + i) * offset]);
				tempSamples[(tempSamplesStart + i) * offset + 1] = (short)((1.0f - percent) * samples[(silenceCrossfadeStart + i) * offset + 1] + percent * samples[(silenceCrossfadeEnd + i) * offset + 1]);

				percent += step;
			}

			size += 2 * crossfadeSamples * sizeof(short);
			currentTempSample += 2 * crossfadeSamples;
		}
		else
		{
			unsigned int tempSamplesStart = currentTempSample;
			unsigned int silenceCrossfadeStart = currentSample;
			unsigned int silenceCrossfadeEnd = currentSample + silenceNumSamples - crossfadeSamples;
			for (unsigned int i = 0; i < crossfadeSamples; ++i)
			{
				tempSamples[tempSamplesStart + i] = (short)((1.0f - percent) * samples[silenceCrossfadeStart + i] + percent * samples[silenceCrossfadeEnd + i]);
				percent += step;
			}

			size += crossfadeSamples * sizeof(short);
			currentTempSample += crossfadeSamples;
		}

		currentSample += silenceNumSamples;
#endif

		ASSERT(currentSample <= numSamples);
		if (currentSample == numSamples)
			break;
	}

	if (size != 0)
	{
		// Size not being zero means we've removed some silent sections and filled the temp buffer
		// First copy the bit after the last silence that still isn't in the temp buffer
		unsigned int sizeLeft = originalSize - currentSample * sizeof(short);
		if (sizeLeft > 0)
		{
			LOG_V("Doing last non-silence copy, %u samples from %u to %u\n", sizeLeft / 2, currentSample, currentTempSample);
			memcpy(tempSamples + currentTempSample, samples + currentSample, sizeLeft);
			size += sizeLeft;
		}

		// Finally copy the temp buffer back into the original buffer ("size" is already adjusted to the correct value)
		LOG_V("Doing final copy into original buffer %u samples\n", size / 2);
		memcpy(samples, tempSamples, size);
	}
	else
	{
		// If size is still zero, no silence replacement was done and original buffer is still intact
		size = originalSize;
	}
}
