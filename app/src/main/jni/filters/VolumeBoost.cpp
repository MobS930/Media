#include "../Utils.h"
#define JNI_LOG_TAG "VolumeBoost"

#include "VolumeBoost.h"

#include <limits.h>

#define VOLUME_BOOST_SAMPLES (10 * 1024)    // The 'standard' area to apply the volume boost on
#define VOLUME_BOOST_MAX 16.0f              // The maximum multiplier that can be applied (2047 -> 32767)
#define VOLUME_BOOST_CHANGE_LIMIT 0.5f      // The max amount to increase the multiplier per VOLUME_BOOST_SAMPLES
#define VOLUME_BOOST_DYNAMIC_MAX_DECAY 0.1f // The amount per VOLUME_BOOST_SAMPLES to return the dynamic max to VOLUME_BOOST_MAX

static float volumeBoostDynamicMax = VOLUME_BOOST_MAX; // Limit the maximum volume boost to recent peaks in volume
static float volumeBoostLast = 1.0f;                   // Keep track of the volume boost applied to the last sample
                                                       // in the previous buffer
static bool cleanupVolumeBoost = false;                // When volume boost is deactivated we need to return volume to
                                                       // normal level before stopping the boost

void ResetVolumeBoost()
{
	LOG_V("ResetVolumeBoost()");

	volumeBoostDynamicMax = VOLUME_BOOST_MAX;
	volumeBoostLast = 1.0f;
	cleanupVolumeBoost = false;
}

void SetCleanupVolumeBoost(bool cleanup)
{
	cleanupVolumeBoost = cleanup;
}

bool NeedCleanupVolumeBoost()
{
	return cleanupVolumeBoost;
}

void VolumeBoost(unsigned char* buffer, unsigned int size, unsigned int channels)
{
	if (size == 0)
		return;

	short* samples = (short*)buffer;
	unsigned int numSamples = size / 2;
	bool stereo = channels == 2;

	// Variable used to store the first sample that would be clipped above SHRT_MAX (or below SHRT_MIN)
	int clippedSampleThreshold = (int)((float)SHRT_MAX / volumeBoostLast);
	int firstClippedSample = -1;

	// Iterate over the samples and keep track of the one with the loudest absolute volume
	// Also store the location of the first clipped sample we find (if any)
	int maxVolume = 0;
	for (unsigned int i = 0; i < numSamples; ++i)
	{
		int sample = (int)samples[i];
		sample = sample == SHRT_MIN ? SHRT_MAX : Abs(sample);

		if (firstClippedSample == -1 && sample >= clippedSampleThreshold)
			firstClippedSample = i;

		if (sample > maxVolume)
			maxVolume = sample;
	}

	float volumeBoostChangeLimit = VOLUME_BOOST_CHANGE_LIMIT * ((float)(stereo ? numSamples / 2 : numSamples) / (float)VOLUME_BOOST_SAMPLES);

	// What do we need to multiply the loudest sample by to make it as loud as possible (but not clip over SHRT_MAX)
	float volumeBoost = (float)(SHRT_MAX - 1) / (float)maxVolume;
	// If this calculated volume boost is over the dynamic maximum, change the maximum
	if (volumeBoostDynamicMax > volumeBoost)
		volumeBoostDynamicMax = volumeBoost;
	// Don't increase the volume boost too quickly, ramp up gently
	if (volumeBoost > volumeBoostLast && volumeBoost - volumeBoostLast > volumeBoostChangeLimit)
		volumeBoost = volumeBoostLast + volumeBoostChangeLimit;
	// And finally limit the volume boost to the maximum
	if (volumeBoost > volumeBoostDynamicMax)
		volumeBoost = volumeBoostDynamicMax;

	// If we're ending volume boost, override the calculated value
	if (cleanupVolumeBoost)
	{
		// Force a decrease
		volumeBoost = volumeBoostLast - volumeBoostChangeLimit;
		// Don't go below 1.0f
		if (volumeBoost < 1.0f)
			volumeBoost = 1.0f;
		// Also make sure to clamp by volumeBoostDynamicMax
		// otherwise clipping will occur
		if (volumeBoost > volumeBoostDynamicMax)
			volumeBoost = volumeBoostDynamicMax;

		// Once the volume boost is reset to 1.0f we can end the fade out
		if (volumeBoost == 1.0f)
			cleanupVolumeBoost = false;
	}

	if (firstClippedSample != -1)
		LOG_V("maxVolume: %d boosting by: %f -> %f (max: %f) by sample %u (of %u)", maxVolume, volumeBoostLast, volumeBoost, volumeBoostDynamicMax, firstClippedSample, numSamples);
	else
		LOG_V("maxVolume: %d boosting by: %f -> %f (max: %f)", maxVolume, volumeBoostLast, volumeBoost, volumeBoostDynamicMax);

	// Iterate over the samples a second time, and multiply them by the volume boost
	// We start the volume boost at the same level as the one from the last buffer
	// and change it until it's at the level we want (by the time we get to the last sample)
	// We ramp it more quickly when clipping would occur, in that case the new level
	// is calculated to be reached at the first (potentially) clipped sample
	double volumeBoostStep = (double)(volumeBoost - volumeBoostLast) / (double)(firstClippedSample != -1 ? (firstClippedSample + 1) : numSamples);
	double volumeBoostCurrent = (double)volumeBoostLast + volumeBoostStep;
	for (unsigned int i = 0; i < numSamples; ++i)
	{
		int boostedSample = (int)((float)volumeBoostCurrent * (float)samples[i]);
		ASSERT_TAG(boostedSample <= SHRT_MAX && boostedSample >= SHRT_MIN, "volumeBoostLast: %f volumeBoostStep: %f volumeBoostCurrent: %f sample: %f -> %d", volumeBoostLast, (float)volumeBoostStep, (float)volumeBoostCurrent, (float)samples[i], boostedSample);
		samples[i] = (short)boostedSample;

		if (firstClippedSample != -1)
		{
			// Ramp the volume multiplier only until we hit firstClippedSample
			if ((int)i <= firstClippedSample)
				volumeBoostCurrent += volumeBoostStep;
		}
		else
		{
			volumeBoostCurrent += volumeBoostStep;
		}
	}

	volumeBoostLast = volumeBoost;

	// Slowly let the maximum volume boost return to VOLUME_BOOST_MAX
	float volumeBoostDynamicMaxDecay = VOLUME_BOOST_DYNAMIC_MAX_DECAY * ((float)(stereo ? numSamples / 2 : numSamples) / (float)VOLUME_BOOST_SAMPLES);
	volumeBoostDynamicMax += volumeBoostDynamicMaxDecay;
	if (volumeBoostDynamicMax > VOLUME_BOOST_MAX)
		volumeBoostDynamicMax = VOLUME_BOOST_MAX;
}
