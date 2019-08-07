#ifndef VolumeBoost_H_
#define VolumeBoost_H_

void ResetVolumeBoost();

void SetCleanupVolumeBoost(bool cleanup);
bool NeedCleanupVolumeBoost();

void VolumeBoost(unsigned char*, unsigned int, unsigned int);

#endif
