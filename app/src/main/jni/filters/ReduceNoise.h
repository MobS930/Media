#ifndef ReduceNoise_H_
#define ReduceNoise_H_

#define FFT_SAMPLES 2048

void ResetReduceNoise();

void SetCleanupReduceNoise(bool cleanup);
bool NeedCleanupReduceNoise();

unsigned char* ReduceNoise(unsigned char*, size_t&, unsigned int);

#endif
