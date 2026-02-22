#if defined(__ANDROID__)

extern "C" {
__attribute__((visibility("default")))
int penguin_stub_version(void)
{
	return 1;
}
}

#endif
