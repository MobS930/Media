#ifndef Utils_H_
#define Utils_H_

#include <stddef.h>
#include <stdio.h>
#include <time.h>
#include <malloc.h>
#include <string.h>

#define PI      3.141592654f
#define TWO_PI  6.283185307f
#define HALF_PI 1.570796327f

// Returns absolute value of an integer
inline int Abs(int x)
{
	int y = (x >> 31);
	return (x ^ y) - y;
}

inline void DummyFunc()
{
}

inline timespec TimespecDiff(timespec start, timespec end)
{
	timespec temp;
	if ((end.tv_nsec - start.tv_nsec) < 0) 
	{
		temp.tv_sec = end.tv_sec - start.tv_sec - 1;
		temp.tv_nsec = 1000000000 + end.tv_nsec - start.tv_nsec;
	} 
	else 
	{
		temp.tv_sec = end.tv_sec - start.tv_sec;
		temp.tv_nsec = end.tv_nsec - start.tv_nsec;
	}
	return temp;
}

// Uncomment to enable logs and asserts from JNI
#define JNI_LOGS_ENABLED
//#define JNI_LOGS_VERBOSE
// #define JNI_LOGS_PROFILING

#if defined(JNI_LOGS_ENABLED)

#include <android/log.h>

#if defined(JNI_LOGS_VERBOSE)
	#define LOG_V(...) __android_log_print(ANDROID_LOG_VERBOSE, JNI_LOG_TAG, __VA_ARGS__)
#else
	#define LOG_V(...) while (0) DummyFunc()
#endif
#define LOG_D(...) __android_log_print(ANDROID_LOG_DEBUG, JNI_LOG_TAG, __VA_ARGS__)
#define LOG_I(...) __android_log_print(ANDROID_LOG_INFO,  JNI_LOG_TAG, __VA_ARGS__)
#define LOG_W(...) __android_log_print(ANDROID_LOG_WARN,  JNI_LOG_TAG, __VA_ARGS__)
#define LOG_E(...) __android_log_print(ANDROID_LOG_ERROR, JNI_LOG_TAG, __VA_ARGS__)

#define ASSERT(a) do{ if (!(a)) { LOG_E("%s\nASSERT failed on line %u in file %s\n", #a, __LINE__, __FILE__); } } while (0)
#define ASSERT_TAG(a, ...) do{ if (!(a)) { char szTag[256]; sprintf(szTag, __VA_ARGS__); LOG_E("%s\nASSERT failed on line %u in file %s\n%s\n", #a, __LINE__, __FILE__, szTag); } } while (0)

#if defined(JNI_LOGS_PROFILING)
	#define PROFILE_START(a) timespec start##a, end##a, diff##a; clock_gettime(CLOCK_MONOTONIC, &start##a);
	#define PROFILE_END(a, b) clock_gettime(CLOCK_MONOTONIC, &end##a); diff##a = TimespecDiff(start##a, end##a); static long max##a = 0; if (diff##a.tv_nsec > max##a) max##a = diff##a.tv_nsec; LOG_I(b, diff##a.tv_nsec / (long)1000, max##a / (long)1000);
#else
	#define PROFILE_START(a) while (0) DummyFunc()
	#define PROFILE_END(a, b) while (0) DummyFunc()
#endif

#else // JNI_LOGS_ENABLED

#define LOG_V(...) while (0) DummyFunc()
#define LOG_D(...) while (0) DummyFunc()
#define LOG_I(...) while (0) DummyFunc()
#define LOG_W(...) while (0) DummyFunc()
#define LOG_E(...) while (0) DummyFunc()

#define ASSERT(a) while (0) DummyFunc()
#define ASSERT_TAG(a, ...) while (0) DummyFunc()

#define PROFILE_START(a) while (0) DummyFunc()
#define PROFILE_END(a, b) while (0) DummyFunc()

#endif // JNI_LOGS_ENABLED

#define JNI_LOG_TAG "Buffer"

class Buffer
{
public:

	inline Buffer()
	{
		Init();
	}

	inline ~Buffer()
	{
		Free();
	}

	inline void Init()
	{
		mBuffer = NULL;
		mBufferSize = 0;

		mDataOffset = 0;
		mDataSize = 0;
	}

	inline void Free()
	{
		free(mBuffer);
		Init();
	}

	inline unsigned char* GetBuffer()
	{
		return mBuffer;
	}

	inline unsigned int GetBufferSize()
	{
		return mBufferSize;
	}

	inline unsigned char* GetData()
	{
		return mBuffer + mDataOffset;
	}

	inline unsigned int GetDataSize()
	{
		return mDataSize;
	}

	inline void SetDataSize(unsigned int size, unsigned int offset = 0)
	{
		mDataOffset = offset;
		mDataSize = size;
	}

	inline void Resize(unsigned int size, bool copyData = false)
	{
		if (mBufferSize >= size)
			return;

		// LOG_V("  Resize  BufferSize: %u -> %u\n", mBufferSize, size);

		if (mBuffer == NULL)
		{
			ASSERT(mDataSize == 0 && mDataOffset == 0);

			mBuffer = (unsigned char*)malloc(size);
			mBufferSize = size;

			return;
		}

		if (copyData)
		{
			unsigned char* old = mBuffer;
			mBuffer = (unsigned char*)malloc(size);
			mBufferSize = size;
			if (mDataSize > 0)
				memcpy(mBuffer, old + mDataOffset, mDataSize);
			free(old);
			mDataOffset = 0;
		}
		else
		{
			free(mBuffer);
			mBuffer = (unsigned char*)malloc(size);
			mBufferSize = size;
			mDataOffset = 0;
			mDataSize = 0;
		}
	}

	inline void AddData(unsigned char* data, unsigned int size)
	{
		// LOG_V("  AddData  DataSize: %u -> %u\n", mDataSize, mDataSize + size);

		MoveDataToStartOfBuffer();

		if (mDataSize + size > mBufferSize)
			Resize(mDataSize + size, true);

		memcpy(mBuffer + mDataSize, data, size);
		mDataSize += size;
	}

	inline void RemoveData(unsigned int size)
	{
		// LOG_V("  RemoveData  DataSize: %u -> %u\n", mDataSize, mDataSize - size);
		
		mDataOffset += size;
		mDataSize -= size;
	}

private:

	inline void MoveDataToStartOfBuffer()
	{
		if (mDataOffset != 0)
		{
			// LOG_V("    MoveDataToStartOfBuffer  DataOffset: %u -> 0\n", mDataOffset);

			if (mDataSize > 0)
				memcpy(mBuffer, mBuffer + mDataOffset, mDataSize);
			mDataOffset = 0;
		}
	}

	unsigned char* mBuffer;
	unsigned int mBufferSize;

	unsigned int mDataOffset;
	unsigned int mDataSize;
};

#undef JNI_LOG_TAG

#endif
