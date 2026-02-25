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

#include "sysconfig.h"
#include "sysdeps.h"

#include "android_audio.h"

#if defined(__ANDROID__)

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/log.h>
#include <atomic>
#include <stdlib.h>
#include <string.h>

#if __ANDROID_API__ >= 26
#include <aaudio/AAudio.h>
#define AMIBERRY_ANDROID_HAS_AAUDIO 1
#else
#define AMIBERRY_ANDROID_HAS_AAUDIO 0
#endif

#define LOG_TAG "AndroidAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct android_audio_config
{
    int sample_rate;
    int channels;
    int frames_per_buffer;
};

class AndroidAudio
{
public:
    AndroidAudio();
    ~AndroidAudio();

    bool init(int sample_rate, int channels, int frames_per_buffer);
    bool start();
    void stop();
    void close();
    bool is_running() const { return m_running.load(); }
    void write_data(const int16_t* data, int32_t num_frames);
    void set_volume(float volume);
    int32_t get_buffered_frames() const;

private:
#if AMIBERRY_ANDROID_HAS_AAUDIO
    static aaudio_data_callback_result_t aaudio_callback(
        AAudioStream* stream,
        void* user_data,
        void* audio_data,
        int32_t num_frames);
#endif

    static void opensles_player_callback(SLBufferQueueItf bufferQueue, void* context);
    bool init_aaudio(int sample_rate, int channels, int frames_per_buffer);
    bool init_opensles(int sample_rate, int channels, int frames_per_buffer);

    SLObjectItf m_opensles_player_object = nullptr;
    SLPlayItf m_opensles_player = nullptr;
    SLBufferQueueItf m_opensles_buffer_queue = nullptr;
#if AMIBERRY_ANDROID_HAS_AAUDIO
    AAudioStream* m_aaudio_stream = nullptr;
#else
    void* m_aaudio_stream = nullptr;
#endif
    android_audio_config m_config{};
    std::atomic<bool> m_running{false};
    std::atomic<bool> m_initialized{false};
    int16_t* m_opensles_buffer = nullptr;
    int32_t m_opensles_buffer_size = 0;
    int32_t m_opensles_buffer_index = 0;
    float m_volume = 1.0f;
    bool m_use_aaudio = true;
    void* m_user_data = nullptr;
};

// Global Android audio instance
static AndroidAudio* g_android_audio = nullptr;

// Circular buffer for audio data
static int16_t* g_audio_buffer = nullptr;
static int32_t g_audio_buffer_capacity = 0;
static int32_t g_audio_buffer_head = 0;
static int32_t g_audio_buffer_tail = 0;
static std::atomic<int32_t> g_audio_buffer_frames{0};

AndroidAudio::AndroidAudio()
    : m_opensles_player_object(nullptr)
    , m_opensles_player(nullptr)
    , m_opensles_buffer_queue(nullptr)
    , m_aaudio_stream(nullptr)
    , m_running(false)
    , m_initialized(false)
    , m_opensles_buffer(nullptr)
    , m_opensles_buffer_size(0)
    , m_opensles_buffer_index(0)
    , m_volume(1.0f)
    , m_use_aaudio(true)
    , m_user_data(nullptr)
{
    memset(&m_config, 0, sizeof(m_config));
}

AndroidAudio::~AndroidAudio()
{
    close();
}

// AAudio callback - called by audio hardware
#if AMIBERRY_ANDROID_HAS_AAUDIO
aaudio_data_callback_result_t AndroidAudio::aaudio_callback(
    AAudioStream* stream,
    void* user_data,
    void* audio_data,
    int32_t num_frames)
{
    auto* audio = static_cast<AndroidAudio*>(user_data);
    auto* output = static_cast<int16_t*>(audio_data);

    if (!output)
    {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    if (!audio)
    {
        memset(output, 0, num_frames * 2 * sizeof(int16_t)); // stereo 16-bit
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    // Read from circular buffer
    int32_t frames_to_read = num_frames;
    const int32_t available_samples = g_audio_buffer_frames.load();
    const int32_t available_frames = available_samples / audio->m_config.channels;

    if (available_frames == 0)
    {
        // Underrun - output silence
        memset(output, 0, num_frames * audio->m_config.channels * sizeof(int16_t));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    frames_to_read = (available_frames < num_frames) ? available_frames : num_frames;
    const int32_t samples_to_read = frames_to_read * audio->m_config.channels;

    for (int32_t i = 0; i < samples_to_read; ++i)
    {
        output[i] = g_audio_buffer[g_audio_buffer_head];
        g_audio_buffer_head = (g_audio_buffer_head + 1) % g_audio_buffer_capacity;
    }

    g_audio_buffer_frames -= samples_to_read;

    // Zero any remaining space (underrun protection)
    if (frames_to_read < num_frames)
    {
        int32_t remaining = (num_frames - frames_to_read) * audio->m_config.channels;
        memset(output + (frames_to_read * audio->m_config.channels), 0, remaining * sizeof(int16_t));
    }

    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}
#endif

// OpenSLES buffer queue callback
void AndroidAudio::opensles_player_callback(SLBufferQueueItf bufferQueue, void* context)
{
    auto* audio = static_cast<AndroidAudio*>(context);
    if (!audio || !audio->m_opensles_buffer_queue)
        return;

    SLresult result;

    // Get next buffer from our circular buffer
    const int32_t available_samples = g_audio_buffer_frames.load();
    const int32_t available_frames = available_samples / audio->m_config.channels;
    if (available_frames == 0)
    {
        // Underrun - fill with silence
        memset(audio->m_opensles_buffer, 0, audio->m_opensles_buffer_size);
    }
    else
    {
        int32_t frames_to_copy = available_frames;
        if (frames_to_copy > audio->m_config.frames_per_buffer)
            frames_to_copy = audio->m_config.frames_per_buffer;

        int32_t samples_to_copy = frames_to_copy * audio->m_config.channels;

        for (int32_t i = 0; i < samples_to_copy; ++i)
        {
            audio->m_opensles_buffer[i] = g_audio_buffer[g_audio_buffer_head];
            g_audio_buffer_head = (g_audio_buffer_head + 1) % g_audio_buffer_capacity;
        }

        g_audio_buffer_frames -= samples_to_copy;

        // Zero remaining if underrun
        if (frames_to_copy < audio->m_config.frames_per_buffer)
        {
            int32_t remaining = (audio->m_config.frames_per_buffer - frames_to_copy) * audio->m_config.channels;
            memset(audio->m_opensles_buffer + samples_to_copy, 0, remaining * sizeof(int16_t));
        }
    }

    // Enqueue the buffer
    result = (*audio->m_opensles_buffer_queue)->Enqueue(
        audio->m_opensles_buffer_queue,
        audio->m_opensles_buffer,
        audio->m_opensles_buffer_size);

    if (result != SL_RESULT_SUCCESS)
    {
        LOGE("OpenSLES: Enqueue failed: %d", (int)result);
    }
}

bool AndroidAudio::init(int sample_rate, int channels, int frames_per_buffer)
{
    m_config.sample_rate = sample_rate;
    m_config.channels = channels;
    m_config.frames_per_buffer = frames_per_buffer;

    // Allocate circular buffer (4x the buffer size for safety)
    g_audio_buffer_capacity = frames_per_buffer * 4 * channels;
    g_audio_buffer = new int16_t[g_audio_buffer_capacity];
    memset(g_audio_buffer, 0, g_audio_buffer_capacity * sizeof(int16_t));
    g_audio_buffer_head = 0;
    g_audio_buffer_tail = 0;
    g_audio_buffer_frames = 0;

    LOGI("Audio buffer allocated: %d frames capacity", g_audio_buffer_capacity / channels);

    // Try AAudio first when API level supports it (Android 8+)
    m_use_aaudio = AMIBERRY_ANDROID_HAS_AAUDIO;

    if (m_use_aaudio)
    {
        if (init_aaudio(sample_rate, channels, frames_per_buffer))
        {
            LOGI("Using AAudio for audio output");
            m_initialized = true;
            return true;
        }
        LOGE("AAudio initialization failed, falling back to OpenSLES");
        m_use_aaudio = false;
    }

    // Fallback to OpenSLES
    if (init_opensles(sample_rate, channels, frames_per_buffer))
    {
        LOGI("Using OpenSLES for audio output");
        m_initialized = true;
        return true;
    }

    LOGE("Both AAudio and OpenSLES failed to initialize");
    delete[] g_audio_buffer;
    g_audio_buffer = nullptr;
    return false;
}

bool AndroidAudio::init_aaudio(int sample_rate, int channels, int frames_per_buffer)
{
#if !AMIBERRY_ANDROID_HAS_AAUDIO
    (void)sample_rate;
    (void)channels;
    (void)frames_per_buffer;
    return false;
#else
    AAudioStreamBuilder* builder = nullptr;

    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || !builder)
    {
        LOGE("AAudio: Failed to create stream builder: %s", AAudio_convertResultToText(result));
        return false;
    }

    // Set stream parameters
    AAudioStreamBuilder_setSampleRate(builder, sample_rate);
    AAudioStreamBuilder_setChannelCount(builder, channels);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setDataCallback(builder, aaudio_callback, this);
    AAudioStreamBuilder_setFramesPerDataCallback(builder, frames_per_buffer);

    // Open the stream
    result = AAudioStreamBuilder_openStream(builder, &m_aaudio_stream);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK)
    {
        LOGE("AAudio: Failed to open stream: %s", AAudio_convertResultToText(result));
        m_aaudio_stream = nullptr;
        return false;
    }

    // Log actual parameters
    int32_t actual_sample_rate = AAudioStream_getSampleRate(m_aaudio_stream);
    int32_t actual_channels = AAudioStream_getChannelCount(m_aaudio_stream);
    int32_t actual_frames = AAudioStream_getFramesPerDataCallback(m_aaudio_stream);

    LOGI("AAudio stream opened: %dHz, %d channels, %d frames/callback",
         actual_sample_rate, actual_channels, actual_frames);

    return true;
#endif
}

bool AndroidAudio::init_opensles(int sample_rate, int channels, int frames_per_buffer)
{
    SLresult result;

    // Create engine
    SLObjectItf engineObject;
    result = slCreateEngine(&engineObject, 0, nullptr, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS)
    {
        LOGE("OpenSLES: Failed to create engine");
        return false;
    }

    // Realize engine
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS)
    {
        LOGE("OpenSLES: Failed to realize engine");
        (*engineObject)->Destroy(engineObject);
        return false;
    }

    // Get engine interface
    SLEngineItf engine;
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engine);
    if (result != SL_RESULT_SUCCESS)
    {
        LOGE("OpenSLES: Failed to get engine interface");
        (*engineObject)->Destroy(engineObject);
        return false;
    }

    // Create output mix
    SLObjectItf outputMixObject;
    result = (*engine)->CreateOutputMix(engine, &outputMixObject, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS)
    {
        LOGE("OpenSLES: Failed to create output mix");
        (*engineObject)->Destroy(engineObject);
        return false;
    }

    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS)
    {
        LOGE("OpenSLES: Failed to realize output mix");
        (*outputMixObject)->Destroy(outputMixObject);
        (*engineObject)->Destroy(engineObject);
        return false;
    }

    // Create audio player
    SLDataLocator_BufferQueue bufferQueueLocator;
    bufferQueueLocator.locatorType = SL_DATALOCATOR_BUFFERQUEUE;
    bufferQueueLocator.numBuffers = 4; // Number of buffers

    SLDataFormat_PCM format;
    format.formatType = SL_DATAFORMAT_PCM;
    format.numChannels = channels;
    format.samplesPerSec = sample_rate * 1000;
    format.bitsPerSample = 16;
    format.containerSize = 16;
    format.channelMask = (channels == 1) ? SL_SPEAKER_FRONT_CENTER : (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT);
    format.endianness = SL_BYTEORDER_LITTLEENDIAN;

    SLDataSource dataSource;
    dataSource.pLocator = &bufferQueueLocator;
    dataSource.pFormat = &format;

    SLDataLocator_OutputMix outputMixLocator;
    outputMixLocator.locatorType = SL_DATALOCATOR_OUTPUTMIX;
    outputMixLocator.outputMix = outputMixObject;

    SLDataSink dataSink;
    dataSink.pLocator = &outputMixLocator;
    dataSink.pFormat = nullptr;

    // Create player
    SLInterfaceID ids[] = {SL_IID_BUFFERQUEUE};
    SLboolean required[] = {SL_BOOLEAN_TRUE};

    result = (*engine)->CreateAudioPlayer(engine, &m_opensles_player_object, &dataSource, &dataSink, 1, ids, required);
    if (result != SL_RESULT_SUCCESS)
    {
        LOGE("OpenSLES: Failed to create audio player");
        (*outputMixObject)->Destroy(outputMixObject);
        (*engineObject)->Destroy(engineObject);
        return false;
    }

    result = (*m_opensles_player_object)->Realize(m_opensles_player_object, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS)
    {
        LOGE("OpenSLES: Failed to realize player");
        (*m_opensles_player_object)->Destroy(m_opensles_player_object);
        m_opensles_player_object = nullptr;
        (*outputMixObject)->Destroy(outputMixObject);
        (*engineObject)->Destroy(engineObject);
        return false;
    }

    // Get player interface
    result = (*m_opensles_player_object)->GetInterface(m_opensles_player_object, SL_IID_PLAY, &m_opensles_player);
    if (result != SL_RESULT_SUCCESS)
    {
        LOGE("OpenSLES: Failed to get play interface");
        (*m_opensles_player_object)->Destroy(m_opensles_player_object);
        m_opensles_player_object = nullptr;
        (*outputMixObject)->Destroy(outputMixObject);
        (*engineObject)->Destroy(engineObject);
        return false;
    }

    // Get buffer queue interface
    result = (*m_opensles_player_object)->GetInterface(m_opensles_player_object, SL_IID_BUFFERQUEUE, &m_opensles_buffer_queue);
    if (result != SL_RESULT_SUCCESS)
    {
        LOGE("OpenSLES: Failed to get buffer queue interface");
        (*m_opensles_player_object)->Destroy(m_opensles_player_object);
        m_opensles_player_object = nullptr;
        (*outputMixObject)->Destroy(outputMixObject);
        (*engineObject)->Destroy(engineObject);
        return false;
    }

    // Register callback
    result = (*m_opensles_buffer_queue)->RegisterCallback(m_opensles_buffer_queue, opensles_player_callback, this);
    if (result != SL_RESULT_SUCCESS)
    {
        LOGE("OpenSLES: Failed to register callback");
        (*m_opensles_player_object)->Destroy(m_opensles_player_object);
        m_opensles_player_object = nullptr;
        (*outputMixObject)->Destroy(outputMixObject);
        (*engineObject)->Destroy(engineObject);
        return false;
    }

    // Allocate buffer
    m_opensles_buffer_size = frames_per_buffer * channels * sizeof(int16_t);
    m_opensles_buffer = new int16_t[frames_per_buffer * channels];
    memset(m_opensles_buffer, 0, m_opensles_buffer_size);

    // Destroy engine and output mix (player keeps references)
    (*outputMixObject)->Destroy(outputMixObject);
    (*engineObject)->Destroy(engineObject);

    LOGI("OpenSLES audio player created: %dHz, %d channels, %d frames",
         sample_rate, channels, frames_per_buffer);

    return true;
}

bool AndroidAudio::start()
{
    if (!m_initialized)
        return false;

    if (m_use_aaudio && m_aaudio_stream)
    {
#if AMIBERRY_ANDROID_HAS_AAUDIO
        aaudio_result_t result = AAudioStream_requestStart(m_aaudio_stream);
        if (result != AAUDIO_OK)
        {
            LOGE("AAudio: Failed to start stream: %s", AAudio_convertResultToText(result));
            return false;
        }
#else
        return false;
#endif
    }
    else if (!m_use_aaudio && m_opensles_player)
    {
        SLresult result = (*m_opensles_player)->SetPlayState(m_opensles_player, SL_PLAYSTATE_PLAYING);
        if (result != SL_RESULT_SUCCESS)
        {
            LOGE("OpenSLES: Failed to start playback");
            return false;
        }

        // Pre-fill buffers
        for (int i = 0; i < 2; ++i)
        {
            memset(m_opensles_buffer, 0, m_opensles_buffer_size);
            (*m_opensles_buffer_queue)->Enqueue(m_opensles_buffer_queue, m_opensles_buffer, m_opensles_buffer_size);
        }
    }

    m_running = true;
    LOGI("Audio started");
    return true;
}

void AndroidAudio::stop()
{
    if (!m_running)
        return;

    if (m_use_aaudio && m_aaudio_stream)
    {
#if AMIBERRY_ANDROID_HAS_AAUDIO
        AAudioStream_requestStop(m_aaudio_stream);
#endif
    }
    else if (!m_use_aaudio && m_opensles_player)
    {
        (*m_opensles_player)->SetPlayState(m_opensles_player, SL_PLAYSTATE_STOPPED);
    }

    m_running = false;
    LOGI("Audio stopped");
}

void AndroidAudio::close()
{
    stop();

    if (m_use_aaudio && m_aaudio_stream)
    {
#if AMIBERRY_ANDROID_HAS_AAUDIO
        AAudioStream_close(m_aaudio_stream);
#endif
        m_aaudio_stream = nullptr;
    }
    else if (!m_use_aaudio && m_opensles_player_object)
    {
        (*m_opensles_player_object)->Destroy(m_opensles_player_object);
        m_opensles_player_object = nullptr;
        m_opensles_player = nullptr;
        m_opensles_buffer_queue = nullptr;
    }

    delete[] m_opensles_buffer;
    m_opensles_buffer = nullptr;

    delete[] g_audio_buffer;
    g_audio_buffer = nullptr;
    g_audio_buffer_capacity = 0;

    m_initialized = false;
    LOGI("Audio closed");
}

void AndroidAudio::write_data(const int16_t* data, int32_t num_frames)
{
    if (!data || num_frames <= 0)
        return;

    int32_t samples_to_write = num_frames * m_config.channels;
    int32_t available_space = g_audio_buffer_capacity - g_audio_buffer_frames.load();

    if (samples_to_write > available_space)
    {
        // Buffer full - drop oldest data or skip new data
        int32_t samples_to_drop = samples_to_write - available_space;
        g_audio_buffer_head = (g_audio_buffer_head + samples_to_drop) % g_audio_buffer_capacity;
        g_audio_buffer_frames -= samples_to_drop;
    }

    // Write to circular buffer
    for (int32_t i = 0; i < samples_to_write; ++i)
    {
        g_audio_buffer[g_audio_buffer_tail] = data[i];
        g_audio_buffer_tail = (g_audio_buffer_tail + 1) % g_audio_buffer_capacity;
    }

    g_audio_buffer_frames += samples_to_write;
}

void AndroidAudio::set_volume(float volume)
{
    m_volume = volume;
    // Apply volume to output (would need to be done in callback for actual effect)
}

int32_t AndroidAudio::get_buffered_frames() const
{
    return g_audio_buffer_frames.load() / m_config.channels;
}

// C interface implementations
extern "C" {

int android_audio_init(int sample_rate, int channels, int frames_per_buffer)
{
    if (g_android_audio)
    {
        android_audio_close();
    }

    g_android_audio = new AndroidAudio();
    if (!g_android_audio->init(sample_rate, channels, frames_per_buffer))
    {
        delete g_android_audio;
        g_android_audio = nullptr;
        return 0;
    }

    return 1;
}

int android_audio_start()
{
    if (!g_android_audio)
        return 0;

    return g_android_audio->start() ? 1 : 0;
}

void android_audio_stop()
{
    if (g_android_audio)
    {
        g_android_audio->stop();
    }
}

void android_audio_close()
{
    if (g_android_audio)
    {
        g_android_audio->close();
        delete g_android_audio;
        g_android_audio = nullptr;
    }
}

void android_audio_write(const int16_t* data, int32_t num_frames)
{
    if (g_android_audio)
    {
        g_android_audio->write_data(data, num_frames);
    }
}

void android_audio_set_volume(float volume)
{
    if (g_android_audio)
    {
        g_android_audio->set_volume(volume);
    }
}

int android_audio_is_running()
{
    return g_android_audio && g_android_audio->is_running() ? 1 : 0;
}

int android_audio_get_buffered_frames()
{
    if (!g_android_audio)
        return 0;

    return g_android_audio->get_buffered_frames();
}

const char* android_audio_get_device_name(int index)
{
    // For now, return a default device name
    // AAudio doesn't enumerate devices the same way
    static const char* default_device = "Android Native Audio";
    return default_device;
}

int android_audio_get_device_count()
{
    // Return 1 for the default device
    // AAudio handles device selection automatically
    return 1;
}

} // extern "C"

#else // !__ANDROID__

// Stub implementations for non-Android platforms
extern "C" {

int android_audio_init(int sample_rate, int channels, int frames_per_buffer)
{
    (void)sample_rate;
    (void)channels;
    (void)frames_per_buffer;
    return 0;
}

int android_audio_start()
{
    return 0;
}

void android_audio_stop()
{
}

void android_audio_close()
{
}

void android_audio_write(const int16_t* data, int32_t num_frames)
{
    (void)data;
    (void)num_frames;
}

void android_audio_set_volume(float volume)
{
    (void)volume;
}

int android_audio_is_running()
{
    return 0;
}

int android_audio_get_buffered_frames()
{
    return 0;
}

const char* android_audio_get_device_name(int index)
{
    (void)index;
    return nullptr;
}

int android_audio_get_device_count()
{
    return 0;
}

} // extern "C"

#endif // __ANDROID__
