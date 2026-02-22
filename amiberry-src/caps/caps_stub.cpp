#include "sysdeps.h"

#ifdef AMIBERRY_ANDROID_MINIMAL

#include "uae/caps.h"
#include "zfile.h"

int caps_init(void)
{
	return 0;
}

void caps_unloadimage(int drv)
{
	(void)drv;
}

int caps_loadimage(struct zfile* zf, int drv, int* num_tracks)
{
	(void)zf;
	(void)drv;
	if (num_tracks)
		*num_tracks = 0;
	return 0;
}

int caps_loadtrack(uae_u16* mfmbuf, uae_u16* tracktiming, int drv, int track, int* tracklength, int* multirev,
	int* gapoffset, int* nextrev, bool setrev)
{
	(void)mfmbuf;
	(void)tracktiming;
	(void)drv;
	(void)track;
	(void)setrev;
	if (tracklength)
		*tracklength = 0;
	if (multirev)
		*multirev = 0;
	if (gapoffset)
		*gapoffset = 0;
	if (nextrev)
		*nextrev = 0;
	return 0;
}

int caps_loadrevolution(uae_u16* mfmbuf, uae_u16* tracktiming, int drv, int track, int* tracklength, int* nextrev,
	bool track_access_done)
{
	(void)mfmbuf;
	(void)tracktiming;
	(void)drv;
	(void)track;
	(void)track_access_done;
	if (tracklength)
		*tracklength = 0;
	if (nextrev)
		*nextrev = 0;
	return 0;
}

#endif
