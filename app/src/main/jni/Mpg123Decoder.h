#include <jni.h>

#include <mpg123/mpg123.h>
#include <vorbis/stb_vorbis.h>
#include "FLAC/share/compat.h"
#include "FLAC/FLAC/stream_decoder.h"

#include "Utils.h"

#ifdef __cplusplus
extern "C"
{
#endif

struct FieldsHolder
{
	int fileFormat;
	long st_rate;
	int st_channels;
	int st_enc;
	bool fileEnd;
    bool disableFilters;

	Buffer inputBuffer;
	Buffer filtersBuffer;
	Buffer outputBuffer;

	// MP3
	mpg123_handle *m;

	// OGG
	stb_vorbis *v;

	// FLAC
	FLAC__StreamDecoder *f;
	FLAC__StreamDecoderInitStatus init_status;
	bool halfSampleRate;
};

JNIEXPORT jobject JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_init(JNIEnv* , jobject , jboolean);
JNIEXPORT jlong JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_openFile(JNIEnv* , jobject , jobject, jint );
JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_navFeedSamples (JNIEnv * , jobject , jbyteArray , jint , jobject );
JNIEXPORT jint JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_navOutputSamples(JNIEnv * , jobject , jbyteArray , jobject );
JNIEXPORT jint JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_getNumChannels(JNIEnv* , jobject , jobject );
JNIEXPORT jint JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_getRate(JNIEnv* , jobject , jobject );
JNIEXPORT jint JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_getEncoding(JNIEnv* , jobject , jobject );
JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_closeFile(JNIEnv* , jobject , jobject );
JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_flush(JNIEnv* , jobject , jobject );
JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_setFileEnd(JNIEnv* , jobject , jobject );

#ifdef __cplusplus
}
#endif
