#include <jni.h>

bool IsVolumeBoost();
bool IsSilenceSkip();
bool IsReduceNoise();

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_CustomMediaPlayer_setVolumeBoostNative(JNIEnv*, jobject, jboolean);
JNIEXPORT jboolean JNICALL Java_fm_player_mediaplayer_player_CustomMediaPlayer_isVolumeBoostNative(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_CustomMediaPlayer_setSilenceSkipNative(JNIEnv*, jobject, jboolean);
JNIEXPORT jboolean JNICALL Java_fm_player_mediaplayer_player_CustomMediaPlayer_isSilenceSkipNative(JNIEnv*, jobject);
JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_CustomMediaPlayer_setReduceNoiseNative(JNIEnv*, jobject, jboolean);
JNIEXPORT jboolean JNICALL Java_fm_player_mediaplayer_player_CustomMediaPlayer_isReduceNoiseNative(JNIEnv*, jobject);

#ifdef __cplusplus
}
#endif
