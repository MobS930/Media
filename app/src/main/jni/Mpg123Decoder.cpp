#include "Utils.h"
#define JNI_LOG_TAG "Mpg123"

// MP3
#include "Mpg123Decoder.h"
#include <mpg123/mpg123.h>

// OGG
#include <vorbis/stb_vorbis.h>

// FLAC
#include "FLAC/share/compat.h"
#include "FLAC/FLAC/stream_decoder.h"

FLAC__StreamDecoderReadStatus read_callback(const FLAC__StreamDecoder *decoder, FLAC__byte buffer[], size_t *bytes, void *client_data);
FLAC__StreamDecoderSeekStatus seek_callback(const FLAC__StreamDecoder *decoder, FLAC__uint64 absolute_byte_offset, void *client_data);
FLAC__StreamDecoderTellStatus tell_callback(const FLAC__StreamDecoder *decoder, FLAC__uint64 *absolute_byte_offset, void *client_data);
FLAC__StreamDecoderLengthStatus length_callback(const FLAC__StreamDecoder *decoder, FLAC__uint64 *stream_length, void *client_data);
FLAC__bool eof_callback(const FLAC__StreamDecoder *decoder, void *client_data);
FLAC__StreamDecoderWriteStatus write_callback(const FLAC__StreamDecoder *decoder, const FLAC__Frame *frame, const FLAC__int32 * const buffer[], void *client_data);
void metadata_callback(const FLAC__StreamDecoder *decoder, const FLAC__StreamMetadata *metadata, void *client_data);
void error_callback(const FLAC__StreamDecoder *decoder, FLAC__StreamDecoderErrorStatus status, void *client_data);

#include <stdio.h>
#include <string.h>
#include <limits.h>

#include "CustomMediaPlayer.h"

#include "filters/SilenceSkip.h"
#include "filters/VolumeBoost.h"
#include "filters/ReduceNoise.h"

#define MINIMUM_FILTERS_SIZE (2 * 16 * 1024 * sizeof(short))

unsigned char* ApplyFilters(unsigned char* out, size_t& size, unsigned int channels, Buffer* filtersBuffer, bool endOfFile,
                            bool disableFilters)
{
	filtersBuffer->AddData(out, size);
	unsigned int dataSize = filtersBuffer->GetDataSize();
	if (dataSize < MINIMUM_FILTERS_SIZE && !endOfFile)
	{
		size = 0;
		return out;
	}

	out = filtersBuffer->GetData();
	size = filtersBuffer->GetDataSize();
	unsigned char* finalOut = out;


    LOG_V("ApplyFilters disableFilters %u", disableFilters);

    if (disableFilters == false) {//disable filters because somehow state is shared between player and compressor and it causes corruption in file if reduce noise is on and compressing at same time

        if (IsReduceNoise() || NeedCleanupReduceNoise()) {
            PROFILE_START(ReduceNoise);
            finalOut = ReduceNoise(out, size, channels);
            PROFILE_END(ReduceNoise, "Reduce Noise: %ld (%ld)\n");
        }

        if (IsSilenceSkip()) {
            PROFILE_START(SilenceSkip);
            SilenceSkip(finalOut, size, channels);
            PROFILE_END(SilenceSkip, "Silence Skip: %ld (%ld)\n");
        }

        if (IsVolumeBoost() || NeedCleanupVolumeBoost()) {
            PROFILE_START(VolumeBoost);
            VolumeBoost(finalOut, size, channels);
            PROFILE_END(VolumeBoost, "Volume Boost: %ld (%ld)\n");
        }
    }

	filtersBuffer->SetDataSize(0);

	return finalOut;
}

JNIEXPORT jobject JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_init(JNIEnv * env, jobject obj, jboolean df)
{
	FieldsHolder* holderClass = (FieldsHolder*)malloc(sizeof(FieldsHolder));
	holderClass->m = NULL;
	holderClass->v = NULL;
	holderClass->f = NULL;
	holderClass->halfSampleRate = false;
    holderClass->disableFilters = df;
	holderClass->inputBuffer.Init();
	holderClass->filtersBuffer.Init();
	holderClass->outputBuffer.Init();
	return env->NewDirectByteBuffer(holderClass, 0);
}

JNIEXPORT jlong JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_openFile(JNIEnv* env, jobject object, jobject handle, jint format)
{
	FieldsHolder* holderClass = (FieldsHolder*) env->GetDirectBufferAddress(handle);

	holderClass->fileFormat = format;
	holderClass->st_rate = -1;
	holderClass->st_channels = -1, holderClass->st_enc = -1;
	holderClass->fileEnd = false;

	ResetVolumeBoost();
	ResetSilenceSkip();
	ResetReduceNoise();

	jlong ret = -1;
	if (holderClass->fileFormat == 1)
	{
		// MP3
		mpg123_init();
		int err = MPG123_OK;
		holderClass->m = mpg123_new(NULL, &err);
		mpg123_param(holderClass->m, MPG123_VERBOSE, 2, 0);
		ret = mpg123_open_feed(holderClass->m);
	}
	else if (holderClass->fileFormat == 2)
	{
		// OGG

		holderClass->f = FLAC__stream_decoder_new();
		holderClass->init_status = FLAC__stream_decoder_init_ogg_stream(holderClass->f, read_callback, seek_callback, tell_callback, length_callback, eof_callback, write_callback, metadata_callback, error_callback, holderClass);
	}
	else if (holderClass->fileFormat == 3)
	{
		// FLAC
		holderClass->f = FLAC__stream_decoder_new();
		holderClass->init_status = FLAC__stream_decoder_init_stream(holderClass->f, read_callback, seek_callback, tell_callback, length_callback, eof_callback, write_callback, metadata_callback, error_callback, holderClass);
	}
	else if (holderClass->fileFormat == 4 || holderClass->fileFormat == 5)
	{
		// AAC
	}
	else
	{
		ASSERT(false);
	}

	return ret;
}

JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_navFeedSamples (JNIEnv * env, jobject object, jbyteArray inputdata, jint inputdatasize, jobject handle)
{
	LOG_V("navFeedSamples\n");
	FieldsHolder* holderClass = (FieldsHolder*) env->GetDirectBufferAddress(handle);

	unsigned char* buf = new unsigned char[inputdatasize];
	env->GetByteArrayRegion(inputdata, 0, inputdatasize, reinterpret_cast<jbyte*>(buf));

	if (holderClass->fileFormat == 1)
	{
		// MP3
		int ret;
		ret = mpg123_feed(holderClass->m, buf, inputdatasize);
		if (ret == MPG123_NEW_FORMAT)
		{
			mpg123_getformat(holderClass->m, &(holderClass->st_rate), &(holderClass->st_channels), &(holderClass->st_enc));
			LOG_D("rate: %d channels: %d enc: %d\n", (int)holderClass->st_rate, holderClass->st_channels, holderClass->st_enc);
			ASSERT(holderClass->st_enc == MPG123_ENC_SIGNED_16);
		}
		else
		{
			ASSERT_TAG(ret == MPG123_OK, "mpg123_feed returned %d", ret);
		}
	}
	else if (holderClass->fileFormat == 2)
	{
		// OGG
		holderClass->inputBuffer.AddData(buf, inputdatasize);
	}
	else if (holderClass->fileFormat == 3)
	{
		// FLAC
		holderClass->inputBuffer.AddData(buf, inputdatasize);
	}
	else if (holderClass->fileFormat == 4 || holderClass->fileFormat == 5)
	{
		// AAC
		holderClass->inputBuffer.AddData(buf, inputdatasize);
	}
	else
	{
		ASSERT(false);
	}

	delete buf;
}

JNIEXPORT jint JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_navOutputSamples(JNIEnv * env, jobject object, jbyteArray samples, jobject handle)
{
	LOG_V("navOutputSamples\n");
	FieldsHolder* holderClass = (FieldsHolder*) env->GetDirectBufferAddress(handle);
	Buffer tempBuffer;

	int lastPos = 0;

	if (holderClass->fileFormat == 1)
	{
		int ret;
		size_t size;
		unsigned char* out = new unsigned char[env->GetArrayLength(samples)];

		do
		{
			// MP3
			ret = mpg123_read(holderClass->m, out, env->GetArrayLength(samples) - lastPos, &size);

			if (ret == MPG123_NEW_FORMAT)
			{
				mpg123_getformat(holderClass->m, &(holderClass->st_rate), &(holderClass->st_channels), &(holderClass->st_enc));
				LOG_D("rate: %d channels: %d enc: %d\n", (int)holderClass->st_rate, holderClass->st_channels, holderClass->st_enc);
			}
			else if (ret != MPG123_NEED_MORE && ret != MPG123_DONE)
			{
				ASSERT_TAG(ret == MPG123_OK, "mpg123_read returned %d", ret);
			}

			unsigned char* finalOut = ApplyFilters(out, size, holderClass->st_channels, &holderClass->filtersBuffer, ret == MPG123_DONE, holderClass->disableFilters);
			if (size > 0)
				env->SetByteArrayRegion(samples, lastPos, size, reinterpret_cast<jbyte*>(finalOut));
			lastPos += size;
		}
		while (ret == MPG123_OK && env->GetArrayLength(samples) - lastPos > 0);

		delete out;

		if (ret == MPG123_NEED_MORE)
			LOG_V("MPG123_NEED_MORE Can write %u, wrote %u", env->GetArrayLength(samples), lastPos);
	}
	else if (holderClass->fileFormat == 2)
	{
		// OGG
		int error = 0;
		int used = 0;
		if (holderClass->v == NULL)
		{
			holderClass->v = stb_vorbis_open_pushdata(holderClass->inputBuffer.GetData(), holderClass->inputBuffer.GetDataSize(), &used, &error, NULL);
			LOG_D("stb_vorbis_open_pushdata  in: %d used %d\n", holderClass->inputBuffer.GetDataSize(), used);
			if (holderClass->v == NULL && error != VORBIS_need_more_data)
				LOG_E("stb_vorbis_open_pushdata error: %d\n", error);

			holderClass->inputBuffer.RemoveData(used);
		}

		if (holderClass->v != NULL)
		{
			stb_vorbis_info info = stb_vorbis_get_info(holderClass->v);
			holderClass->st_channels = info.channels;
			holderClass->st_rate = info.sample_rate;
			holderClass->st_enc = MPG123_ENC_SIGNED_16;

			int samplesCount = 0;
			do
			{
				int channels = 0;
				float **outputs = NULL;
				used = stb_vorbis_decode_frame_pushdata(holderClass->v, holderClass->inputBuffer.GetData(), holderClass->inputBuffer.GetDataSize(), &channels, &outputs, &samplesCount);
				LOG_V("stb_vorbis_decode_frame_pushdata  in: %d used %d channels: %d samples: %d\n", holderClass->inputBuffer.GetDataSize(), used, channels, samplesCount);

				size_t size = samplesCount * channels * sizeof(short);
				if (size > 0 || holderClass->fileEnd)
				{
					tempBuffer.Resize(size);
					short* out = (short*)tempBuffer.GetBuffer();

					if (channels == 2)
					{
						for (int i = 0; i < samplesCount; ++i)
						{
							out[2 * i + 0] = (short)((float)(SHRT_MAX - 1) * outputs[0][i]);
							out[2 * i + 1] = (short)((float)(SHRT_MAX - 1) * outputs[1][i]);
						}
					}
					else
					{
						for (int i = 0; i < samplesCount; ++i)
							out[i] = (short)((float)(SHRT_MAX - 1) * outputs[0][i]);
					}

					unsigned char* finalOut = ApplyFilters((unsigned char*)out, size, holderClass->st_channels, &holderClass->filtersBuffer, holderClass->fileEnd, holderClass->disableFilters);
					if (size > 0)
						env->SetByteArrayRegion(samples, lastPos, size, reinterpret_cast<jbyte*>(finalOut));
					lastPos += size;
				}

				holderClass->inputBuffer.RemoveData(used);
			} while (samplesCount > 0 && env->GetArrayLength(samples) - lastPos > 0 && holderClass->inputBuffer.GetDataSize() > 0);
		}
	}
	else if (holderClass->fileFormat == 3)
	{
		// FLAC
		//
		// The reason for FLAC_MINIMUM_BUFFER_SIZE is because the FLAC decoder will sometimes error out if we don't
		// have enough data to give it for a frame, ignore this constraint if we're at the end of the file
		#define FLAC_MINIMUM_BUFFER_SIZE (256 * 1024)
		if (holderClass->inputBuffer.GetDataSize() < FLAC_MINIMUM_BUFFER_SIZE && holderClass->fileEnd == false)
			return 0;

		LOG_V("FLAC__stream_decoder_process_single\n");
		FLAC__bool ret = FLAC__stream_decoder_process_single(holderClass->f);

		// If this assert is hit, it's probably because FLAC_MINIMUM_BUFFER_SIZE needs to be increased
		ASSERT(ret != 0);

		short* out = (short*)holderClass->outputBuffer.GetData();
		size_t size = holderClass->outputBuffer.GetDataSize();
		holderClass->outputBuffer.SetDataSize(0);

		unsigned char* finalOut = ApplyFilters((unsigned char*)out, size, holderClass->st_channels, &holderClass->filtersBuffer, FLAC__stream_decoder_get_state(holderClass->f) == FLAC__STREAM_DECODER_END_OF_STREAM || holderClass->fileEnd, holderClass->disableFilters);
		lastPos += size;

		if (size > 0)
		{
			env->SetByteArrayRegion(samples, 0, size, reinterpret_cast<jbyte*>(finalOut));

			if (holderClass->fileEnd)
			{
				// If the file has been completely read, make sure to process all the data in the pipeline
				LOG_D("File end, reading out all data\n");

				size_t lastOutputSize = 0;
				do
				{
					FLAC__stream_decoder_process_single(holderClass->f);

					out = (short*)holderClass->outputBuffer.GetData();
					lastOutputSize = holderClass->outputBuffer.GetDataSize();
					LOG_D("  lastOutputSize: %u\n", lastOutputSize);
					holderClass->outputBuffer.SetDataSize(0);

					finalOut = ApplyFilters((unsigned char*)out, lastOutputSize, holderClass->st_channels, &holderClass->filtersBuffer, true, holderClass->disableFilters);
					lastPos += lastOutputSize;

					if (lastOutputSize > 0)
						env->SetByteArrayRegion(samples, lastPos - lastOutputSize, lastOutputSize, reinterpret_cast<jbyte*>(finalOut));
				}
				while(lastOutputSize > 0);
			}
		}
	}
	else if (holderClass->fileFormat == 4 || holderClass->fileFormat == 5)
	{
		// AAC
		short* out = (short*)holderClass->inputBuffer.GetData();
		size_t size = holderClass->inputBuffer.GetDataSize();
		holderClass->inputBuffer.SetDataSize(0);

		unsigned char* finalOut = ApplyFilters((unsigned char*)out, size, holderClass->st_channels, &holderClass->filtersBuffer, holderClass->fileEnd, holderClass->disableFilters);
		if (size > 0)
			env->SetByteArrayRegion(samples, 0, size, reinterpret_cast<jbyte*>(finalOut));
		lastPos += size;
	}
	else
	{
		ASSERT(false);
	}

	return lastPos;
}

JNIEXPORT jint JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_getNumChannels(JNIEnv* env, jobject object, jobject handle)
{
	FieldsHolder* holderClass = (FieldsHolder*) env->GetDirectBufferAddress(handle);
	return holderClass->st_channels;
}

JNIEXPORT jint JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_getRate(JNIEnv* env, jobject object, jobject handle)
{
	FieldsHolder* holderClass = (FieldsHolder*) env->GetDirectBufferAddress(handle);
	return holderClass->st_rate;
}

JNIEXPORT jint JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_getEncoding(JNIEnv* env, jobject object, jobject handle)
{
	FieldsHolder* holderClass = (FieldsHolder*) env->GetDirectBufferAddress(handle);
	return holderClass->st_enc;
}

JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_closeFile(JNIEnv* env, jobject object, jobject handle)
{
	FieldsHolder* holderClass = (FieldsHolder*) env->GetDirectBufferAddress(handle);

	if (holderClass->m != NULL)
	{
		// MP3
		mpg123_close(holderClass->m);
		mpg123_delete(holderClass->m);
		mpg123_exit();
		holderClass->m = NULL;
	}

	if (holderClass->v != NULL)
	{
		// OGG
		stb_vorbis_close(holderClass->v);
		holderClass->v = NULL;
	}

	if (holderClass->f != NULL)
	{
		// FLAC
		FLAC__stream_decoder_delete(holderClass->f);
		holderClass->f = NULL;
	}

	holderClass->inputBuffer.Free();
	holderClass->outputBuffer.Free();
}

JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_flush(JNIEnv* env, jobject object, jobject handle)
{
	LOG_V("Mpg123Decoder_flush\n");
	FieldsHolder* holderClass = (FieldsHolder*) env->GetDirectBufferAddress(handle);

	if (holderClass->fileFormat == 2)
	{
		LOG_V("  stb_vorbis_flush_pushdata\n");
		stb_vorbis_flush_pushdata(holderClass->v);
	}
	else if (holderClass->fileFormat == 3)
	{
		LOG_V("  FLAC__stream_decoder_flush\n");
		bool ret = FLAC__stream_decoder_flush(holderClass->f);
		ASSERT(ret == true);
		holderClass->inputBuffer.Free();
	}
}

JNIEXPORT void JNICALL Java_fm_player_mediaplayer_player_Mpg123Decoder_setFileEnd(JNIEnv* env, jobject object, jobject handle)
{
	LOG_V("Mpg123Decoder_setFileEnd\n");
	FieldsHolder* holderClass = (FieldsHolder*) env->GetDirectBufferAddress(handle);
	holderClass->fileEnd = true;
}

// FLAC
FLAC__StreamDecoderReadStatus read_callback(const FLAC__StreamDecoder *decoder, FLAC__byte buffer[], size_t *bytes, void *client_data)
{
	LOG_V("FLAC read_callback %u\n", *bytes);
	FieldsHolder* holderClass = (FieldsHolder*)client_data;

	if (holderClass->inputBuffer.GetDataSize() == 0)
	{
		LOG_V("  FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM");
		*bytes = 0;
		return FLAC__STREAM_DECODER_READ_STATUS_END_OF_STREAM;
	}

	size_t bytesToCopy = *bytes > holderClass->inputBuffer.GetDataSize() ? holderClass->inputBuffer.GetDataSize() : *bytes;
	memcpy(buffer, holderClass->inputBuffer.GetData(), bytesToCopy);
	*bytes = bytesToCopy;
	holderClass->inputBuffer.RemoveData(bytesToCopy);

	return FLAC__STREAM_DECODER_READ_STATUS_CONTINUE;
}

FLAC__StreamDecoderSeekStatus seek_callback(const FLAC__StreamDecoder *decoder, FLAC__uint64 absolute_byte_offset, void *client_data)
{
	LOG_V("FLAC seek_callback\n");
	return FLAC__STREAM_DECODER_SEEK_STATUS_UNSUPPORTED;
}

FLAC__StreamDecoderTellStatus tell_callback(const FLAC__StreamDecoder *decoder, FLAC__uint64 *absolute_byte_offset, void *client_data)
{
	LOG_V("FLAC tell_callback\n");
	return FLAC__STREAM_DECODER_TELL_STATUS_UNSUPPORTED;
}

FLAC__StreamDecoderLengthStatus length_callback(const FLAC__StreamDecoder *decoder, FLAC__uint64 *stream_length, void *client_data)
{
	LOG_V("FLAC length_callback\n");
	return FLAC__STREAM_DECODER_LENGTH_STATUS_UNSUPPORTED;
}

FLAC__bool eof_callback(const FLAC__StreamDecoder *decoder, void *client_data)
{
	FieldsHolder* holderClass = (FieldsHolder*)client_data;
	LOG_V("FLAC eof_callback: %u\n", holderClass->fileEnd && holderClass->inputBuffer.GetDataSize() == 0);
	return holderClass->fileEnd && holderClass->inputBuffer.GetDataSize() == 0;
}

FLAC__StreamDecoderWriteStatus write_callback(const FLAC__StreamDecoder *decoder, const FLAC__Frame *frame, const FLAC__int32 * const buffer[], void *client_data)
{
	LOG_V("FLAC write_callback %u\n", frame->header.blocksize);
	FieldsHolder* holderClass = (FieldsHolder*)client_data;

	unsigned int shift = frame->header.bits_per_sample - 16;
	ASSERT(shift >= 0);

	unsigned int size = frame->header.blocksize * frame->header.channels * sizeof(short);
	unsigned int multiplier = 1;
	unsigned int samples = frame->header.blocksize;
	if (holderClass->halfSampleRate)
	{
		multiplier = 2;
		size /= 2;
		samples /= 2;
	}

	holderClass->outputBuffer.Resize(size);
	short* dst = (short*)holderClass->outputBuffer.GetBuffer();
	if (frame->header.channels == 2)
	{
		for (unsigned int i = 0; i < samples; ++i)
		{
			dst[2 * i + 0] = (short)(buffer[0][multiplier * i] >> shift);
			dst[2 * i + 1] = (short)(buffer[1][multiplier * i] >> shift);
		}
	}
	else
	{
		for (unsigned int i = 0; i < samples; ++i)
			dst[i] = (short)(buffer[0][multiplier * i] >> shift);
	}
	holderClass->outputBuffer.SetDataSize(size);

	return FLAC__STREAM_DECODER_WRITE_STATUS_CONTINUE;
}

void metadata_callback(const FLAC__StreamDecoder *decoder, const FLAC__StreamMetadata *metadata, void *client_data)
{
	LOG_V("FLAC metadata_callback %u %u\n", metadata->data.stream_info.channels, metadata->data.stream_info.sample_rate);
	FieldsHolder* holderClass = (FieldsHolder*)client_data;

	ASSERT(metadata->type == FLAC__METADATA_TYPE_STREAMINFO);
	holderClass->st_channels = metadata->data.stream_info.channels;
	holderClass->st_rate = metadata->data.stream_info.sample_rate;
	holderClass->st_enc = MPG123_ENC_SIGNED_16;

	if (holderClass->st_rate > 48000)
	{
		// Sonic fails with a higher sample rate
		holderClass->st_rate /= 2;
		holderClass->halfSampleRate = true;
	}
	else
	{
		holderClass->halfSampleRate = false;
	}
}

void error_callback(const FLAC__StreamDecoder *decoder, FLAC__StreamDecoderErrorStatus status, void *client_data)
{
	LOG_E("FLAC error_callback: %d\n", status);
}
