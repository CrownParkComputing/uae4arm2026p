/*
 * Amiberry
 *
 * Native Android Audio (AAudio/OpenSLES) backend
 *
 * Copyright 2025 Dimitris Panokostas
 *
 * Native Android audio implementation that provides low-latency audio
 * without SDL2, synchronized with OpenGL rendering.
 */

#pragma once

#include <stdint.h>

// C interface for integration with sound.cpp
extern "C" {

// Initialize Android audio
int android_audio_init(int sample_rate, int channels, int frames_per_buffer);

// Start playback
int android_audio_start();

// Stop playback
void android_audio_stop();

// Close audio
void android_audio_close();

// Write audio data
void android_audio_write(const int16_t* data, int32_t num_frames);

// Set volume
void android_audio_set_volume(float volume);

// Check if audio is running
int android_audio_is_running();

// Get buffered frames
int android_audio_get_buffered_frames();

// Get device name for enumeration
const char* android_audio_get_device_name(int index);

// Get number of available devices
int android_audio_get_device_count();

} // extern "C"
