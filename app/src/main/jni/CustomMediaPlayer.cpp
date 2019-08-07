#include "Utils.h"
#define JNI_LOG_TAG "CustomMediaPlayer"

#include "CustomMediaPlayer.h"

#include "filters/SilenceSkip.h"
#include "filters/VolumeBoost.h"
#include "filters/ReduceNoise.h"

static bool volumeBoostEnabled = false;
static bool silenceSkipEnabled = false;
static bool reduceNoiseEnabled = false;

bool IsVolumeBoost(){
	return volumeBoostEnabled;
}

bool IsSilenceSkip(){
	return silenceSkipEnabled;
}

bool IsReduceNoise(){
	return reduceNoiseEnabled;
}

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_CustomMediaPlayer_setVolumeBoostNative(JNIEnv* env, jobject object, jboolean enable){
	LOG_D("setVolumeBoost(%u)", enable);

	if (enable)
	{
		if (NeedCleanupVolumeBoost())
		{
			// Volume boost was switched back on while it was fading out
			// so just stop doing the fade out
			SetCleanupVolumeBoost(false);
		}
		else if (!volumeBoostEnabled)
		{
			// Volume boost is being enabled so reset all the tracking variables
			ResetVolumeBoost();
		}
	}
	else
	{
		// Volume is currently boosted, indicate that we want it to end (gradually)
		if (volumeBoostEnabled)
			SetCleanupVolumeBoost(true);
	}

	volumeBoostEnabled = enable;
}

JNIEXPORT jboolean JNICALL Java_fm_player_mediaplayer_player_CustomMediaPlayer_isVolumeBoostNative(JNIEnv*, jobject){
	return volumeBoostEnabled;
}

JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_CustomMediaPlayer_setSilenceSkipNative(JNIEnv* env, jobject object, jboolean enable){
	LOG_D("setSilenceSkip(%u)", enable);
	if (enable && !silenceSkipEnabled)
	{
		// Silence skip is being enabled so reset all the tracking variables
		ResetSilenceSkip();
	}
	silenceSkipEnabled = enable;
}

JNIEXPORT jboolean JNICALL Java_fm_player_mediaplayer_player_CustomMediaPlayer_isSilenceSkipNative(JNIEnv*, jobject){
	return silenceSkipEnabled;
}

JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_CustomMediaPlayer_setReduceNoiseNative(JNIEnv* env, jobject object, jboolean enable){
	LOG_D("setReduceNoise(%u)", enable);

	if (enable)
	{
		if (NeedCleanupReduceNoise())
		{
			// Reduce noise was switched back on before the previous buffers were cleaned up
			// so just cancel the cleanup
			SetCleanupReduceNoise(false);
		}
	}
	else
	{
		// Reduce noise is currently active, indicate that we want it to end next time it's called
		if (reduceNoiseEnabled)
			SetCleanupReduceNoise(true);
	}

	reduceNoiseEnabled = enable;
}

JNIEXPORT jboolean JNICALL Java_fm_player_mediaplayer_player_CustomMediaPlayer_isReduceNoiseNative(JNIEnv*, jobject){
	return reduceNoiseEnabled;
}

#ifdef __cplusplus
}
#endif
