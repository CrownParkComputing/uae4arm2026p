#include "sysconfig.h"
#include "sysdeps.h"

#include "specialmonitors.h"

struct autoconfig_info;

// Minimal stubs for builds that exclude the full special monitor implementation
// (e.g. Android minimal build without libpng).

const TCHAR *specialmonitorfriendlynames[] = {
	_T("none"),
	NULL
};

const TCHAR *specialmonitormanufacturernames[] = {
	_T(""),
	NULL
};

const TCHAR *specialmonitorconfignames[] = {
	_T("none"),
	_T("autodetect"),
	NULL
};

bool emulate_specialmonitors(struct vidbuffer*, struct vidbuffer*)
{
	return false;
}

void specialmonitor_store_fmode(int, int, uae_u16)
{
}

void specialmonitor_reset(void)
{
}

bool specialmonitor_need_genlock(void)
{
	return false;
}

bool specialmonitor_uses_control_lines(void)
{
	return false;
}

bool specialmonitor_autoconfig_init(struct autoconfig_info*)
{
	return false;
}

bool emulate_genlock(struct vidbuffer*, struct vidbuffer*, bool)
{
	return false;
}

bool emulate_grayscale(struct vidbuffer*, struct vidbuffer*)
{
	return false;
}

bool specialmonitor_linebased(void)
{
	return false;
}

#ifndef ARCADIA
void genlock_infotext(uae_u8*, struct vidbuffer*)
{
}
#endif
