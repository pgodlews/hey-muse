// The built-in voice: SVOX Pico behind JNI, for Pico.kt. It is techo5-pico.c from hey-muse turned
// inside out: the engine stays loaded between answers, and the voice goes to a Java callback, 16 kHz
// 16-bit mono, 100 ms at a time, instead of to stdout.

#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "picoapi.h"
#include "picodefs.h"

#define TAG "HeyMusePico"
#define ENGINE_MEMORY 2500000
#define VOICE "heymuse"
#define OUT_BYTES 3200

typedef struct {
	void *memory;
	pico_System system;
	pico_Engine engine;
} Voice;

static void say_status(Voice *v, const char *what, pico_Status status)
{
	pico_Retstring why = "";
	if (v->system && status != PICO_OK) {
		pico_getSystemStatusMessage(v->system, status, why);
	}
	__android_log_print(ANDROID_LOG_ERROR, TAG, "%s: %d %s", what, status, why);
}

static void close_voice(Voice *v)
{
	if (!v) {
		return;
	}
	if (v->system) {
		if (v->engine) {
			pico_disposeEngine(v->system, &v->engine);
		}
		pico_terminate(&v->system);
	}
	free(v->memory);
	free(v);
}

static int load(Voice *v, const char *dir, const char *file)
{
	char path[1024];
	pico_Retstring name;
	pico_Resource resource = NULL;
	pico_Status status;
	if (snprintf(path, sizeof(path), "%s/%s", dir, file) >= (int)sizeof(path)) {
		return 0;
	}
	if ((status = pico_loadResource(v->system, (const pico_Char *)path, &resource)) != PICO_OK
	    || (status = pico_getResourceName(v->system, resource, name)) != PICO_OK
	    || (status = pico_addResourceToVoiceDefinition(v->system, (const pico_Char *)VOICE, (const pico_Char *)name)) != PICO_OK) {
		say_status(v, path, status);
		return 0;
	}
	return 1;
}

JNIEXPORT jlong JNICALL
Java_io_github_pgodlews_heymuse_Pico_nativeOpen(JNIEnv *env, jclass cls, jstring jdir)
{
	const char *dir = (*env)->GetStringUTFChars(env, jdir, NULL);
	Voice *v = calloc(1, sizeof(Voice));
	pico_Status status = PICO_OK;
	int ok = v && (v->memory = malloc(ENGINE_MEMORY))
	      && (status = pico_initialize(v->memory, ENGINE_MEMORY, &v->system)) == PICO_OK
	      && (status = pico_createVoiceDefinition(v->system, (const pico_Char *)VOICE)) == PICO_OK
	      && load(v, dir, "en-US_ta.bin")
	      && load(v, dir, "en-US_lh0_sg.bin")
	      && (status = pico_newEngine(v->system, (const pico_Char *)VOICE, &v->engine)) == PICO_OK;
	(*env)->ReleaseStringUTFChars(env, jdir, dir);
	if (!ok) {
		if (v) {
			say_status(v, "the engine did not start", status);
		}
		close_voice(v);
		return 0;
	}
	return (jlong)(intptr_t)v;
}

JNIEXPORT void JNICALL
Java_io_github_pgodlews_heymuse_Pico_nativeClose(JNIEnv *env, jclass cls, jlong handle)
{
	close_voice((Voice *)(intptr_t)handle);
}

// nativeSpeak says text, handing the voice to sink.write(byte[], int) as it is made. It returns
// false when the engine failed, and stops early, returning true, when write returns false.
JNIEXPORT jboolean JNICALL
Java_io_github_pgodlews_heymuse_Pico_nativeSpeak(JNIEnv *env, jclass cls, jlong handle, jstring jtext, jobject sink)
{
	Voice *v = (Voice *)(intptr_t)handle;
	if (!v) {
		return JNI_FALSE;
	}
	jmethodID write = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, sink), "write", "([BI)Z");
	if (!write) {
		return JNI_FALSE;
	}

	// The engine reads plain text. Anything outside ASCII, emoji above all, would be spelled out or
	// stall it, and a newline is only a space. The terminator tells the engine the text is complete.
	const char *raw = (*env)->GetStringUTFChars(env, jtext, NULL);
	size_t len = strlen(raw), n = 0;
	char *text = malloc(len + 1);
	if (!text) {
		(*env)->ReleaseStringUTFChars(env, jtext, raw);
		return JNI_FALSE;
	}
	for (size_t i = 0; i < len; i++) {
		unsigned char c = (unsigned char)raw[i];
		if (c >= 0x80 || c == 0) {
			continue;
		}
		text[n++] = c == '\n' || c == '\r' ? ' ' : (char)c;
	}
	text[n++] = 0;
	(*env)->ReleaseStringUTFChars(env, jtext, raw);

	jbyteArray out = (*env)->NewByteArray(env, OUT_BYTES);
	jbyte held[OUT_BYTES];
	int have = 0, stopped = 0, failed = 0;
	pico_resetEngine(v->engine, PICO_RESET_SOFT);
	for (size_t sent = 0; sent < n && !stopped && !failed;) {
		pico_Int16 took = 0;
		size_t rest = n - sent;
		pico_Status status = pico_putTextUtf8(v->engine, (const pico_Char *)text + sent, (pico_Int16)(rest > 256 ? 256 : rest), &took);
		if (status != PICO_OK) {
			say_status(v, "the engine did not take the text", status);
			failed = 1;
			break;
		}
		sent += (size_t)took;
		do {
			short samples[256];
			pico_Int16 bytes = 0, kind = 0;
			status = pico_getData(v->engine, samples, sizeof(samples), &bytes, &kind);
			for (int i = 0; i < bytes && !stopped;) {
				int take = bytes - i < OUT_BYTES - have ? bytes - i : OUT_BYTES - have;
				memcpy(held + have, (char *)samples + i, (size_t)take);
				have += take;
				i += take;
				if (have == OUT_BYTES) {
					(*env)->SetByteArrayRegion(env, out, 0, have, held);
					if (!(*env)->CallBooleanMethod(env, sink, write, out, have) || (*env)->ExceptionCheck(env)) {
						stopped = 1;
					}
					have = 0;
				}
			}
		} while (status == PICO_STEP_BUSY && !stopped);
		if (!stopped && status != PICO_STEP_IDLE) {
			say_status(v, "the engine stopped", status);
			failed = 1;
		}
	}
	if (!stopped && !failed && have > 0) {
		(*env)->SetByteArrayRegion(env, out, 0, have, held);
		(*env)->CallBooleanMethod(env, sink, write, out, have);
	}
	if (failed) {
		pico_resetEngine(v->engine, PICO_RESET_FULL);
	} else if (stopped) {
		pico_resetEngine(v->engine, PICO_RESET_SOFT);
	}
	(*env)->DeleteLocalRef(env, out);
	free(text);
	return failed ? JNI_FALSE : JNI_TRUE;
}
