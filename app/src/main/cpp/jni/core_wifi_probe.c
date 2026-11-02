#include <jni.h>
#include <stdint.h>
#include <android/log.h>
#include "seedlessds/wifi_probe.h"
#include "spi/firmware.h"
#include "spi/firmware.h"

/*
 * This translation unit must be compiled into librecon_fn.so.
 * SeedlessCore loads librecon_fn.so before librecon.so, and Wi-Fi registers/counters
 * are stored in librecon_fn.so. Do NOT provide another JNI_OnLoad here: the
 * upstream core already defines it.
 */
JNIEXPORT void JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiProbeEnable(JNIEnv *env, jclass cls,
                                                     jboolean enabled)
{
    (void)env;
    (void)cls;
    nds_wifi_probe_enable(enabled == JNI_TRUE);
    __android_log_print(ANDROID_LOG_INFO, "SeedlessWifiJNI",
                        "Wi-Fi probe JNI enable called (recon_fn), state=%d",
                        (int)(enabled == JNI_TRUE));
}

JNIEXPORT jlongArray JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiProbeStats(JNIEnv *env, jclass cls)
{
    (void)cls;
    uint64_t counters[NDS_WIFI_PROBE_COUNTERS] = {0};
    jlong values[NDS_WIFI_PROBE_COUNTERS];
    nds_wifi_probe_snapshot(counters);
    for (unsigned i = 0; i < NDS_WIFI_PROBE_COUNTERS; i++)
        values[i] = (jlong)counters[i];
    jlongArray result = (*env)->NewLongArray(env, NDS_WIFI_PROBE_COUNTERS);
    if (!result) return NULL;
    (*env)->SetLongArrayRegion(env, result, 0, NDS_WIFI_PROBE_COUNTERS, values);
    __android_log_print(ANDROID_LOG_INFO, "SeedlessWifiJNI",
                        "Wi-Fi probe JNI snapshot read (recon_fn)");
    return result;
}

/* Optional, reversible RF idle-response experiment. Not LAN packet emulation. */
JNIEXPORT void JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiProbeSetRfIdleTest(JNIEnv *env, jclass cls,
                                                            jboolean enabled)
{
    (void)env;
    (void)cls;
    nds_wifi_probe_set_rf_idle_test(enabled == JNI_TRUE);
}

/* 1.0.69: tell the native Wi-Fi timer when a real LAN peer is connected.
 * This is only a timing guard input; no network thread mutates guest state. */
JNIEXPORT void JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiLanPeerActive(JNIEnv *env, jclass cls,
                                                        jboolean active)
{
    (void)env;
    (void)cls;
    nds_wifi_probe_set_lan_peer_active(active == JNI_TRUE);
}

/* Copies a validated emulated Beacon TX descriptor. Does not acknowledge TX,
 * set Wi-Fi interrupts, or write to guest RX memory. */
JNIEXPORT jbyteArray JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiProbeBeaconFrame(JNIEnv *env, jclass cls)
{
    (void)cls;
    uint8_t local[NDS_WIFI_STAGED_BEACON_CAP];
    /* 1.0.68: return a Beacon only for a real emulated Beacon TX event. */
    size_t size = nds_wifi_probe_take_beacon_tx(local, sizeof(local));
    jbyteArray ret = (*env)->NewByteArray(env, (jsize)size);
    if (!ret) return NULL;
    if (size) (*env)->SetByteArrayRegion(env, ret, 0, (jsize)size, (const jbyte *)local);
    return ret;
}

/* Native LAN->guest boundary: socket thread copies bytes to a bounded queue;
 * ARM7 reads its queue and writes the actual emulated RX buffer later. */
JNIEXPORT void JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiGuestRxEnable(JNIEnv *env, jclass cls,
                                                        jboolean enabled)
{
    (void)env; (void)cls;
    nds_wifi_probe_set_guest_rx(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiGuestRxBeacon(JNIEnv *env, jclass cls,
                                                        jbyteArray beacon)
{
    (void)cls;
    if (!beacon) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, beacon);
    if (n < 48 || n > (jsize)NDS_WIFI_STAGED_BEACON_CAP) return JNI_FALSE;
    uint8_t data[NDS_WIFI_STAGED_BEACON_CAP];
    (*env)->GetByteArrayRegion(env, beacon, 0, n, (jbyte *)data);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    return nds_wifi_probe_queue_guest_beacon(data, (size_t)n) ? JNI_TRUE : JNI_FALSE;
}

/* Ordered management TX/RX mailboxes. Only the emulator thread may
 * produce TX frames or consume RX frames; JNI merely copies bounded data. */
JNIEXPORT jbyteArray JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiMgmtTxFrame(JNIEnv *env, jclass cls) {
    (void)cls;
    uint8_t frame[NDS_WIFI_STAGED_BEACON_CAP];
    size_t n = nds_wifi_probe_take_mgmt_tx(frame, sizeof(frame));
    jbyteArray ret = (*env)->NewByteArray(env, (jsize)n);
    if (ret && n) (*env)->SetByteArrayRegion(env, ret, 0, (jsize)n,
                                           (const jbyte *)frame);
    return ret;
}
JNIEXPORT jboolean JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiMgmtRxFrame(JNIEnv *env, jclass cls,
                                                       jbyteArray received,
                                                       jlong token) {
    (void)cls;
    if (!received) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, received);
    if (n < 36 || n > (jsize)NDS_WIFI_STAGED_BEACON_CAP) return JNI_FALSE;
    uint8_t frame[NDS_WIFI_STAGED_BEACON_CAP];
    (*env)->GetByteArrayRegion(env, received, 0, n, (jbyte *)frame);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    return nds_wifi_probe_queue_mgmt_rx(frame, (size_t)n, (uint64_t)token)
        ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiMgmtTxAck(JNIEnv *env, jclass cls,
                                                    jlong token) {
    (void)env; (void)cls;
    nds_wifi_probe_mgmt_transport_ack((uint64_t)token);
}

JNIEXPORT jlong JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiMgmtRxAckToken(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)nds_wifi_probe_take_mgmt_rx_commit_ack();
}


/* 1.0.91: post-association data/MP frame bridge. */
JNIEXPORT jbyteArray JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiDataTxFrame(JNIEnv *env, jclass cls) {
    (void)cls;
    uint8_t frame[NDS_WIFI_STAGED_BEACON_CAP];
    size_t n = nds_wifi_probe_take_data_tx(frame, sizeof(frame));
    jbyteArray ret = (*env)->NewByteArray(env, (jsize)n);
    if (ret && n) (*env)->SetByteArrayRegion(env, ret, 0, (jsize)n,
                                            (const jbyte *)frame);
    return ret;
}
JNIEXPORT jboolean JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiDataRxFrame(JNIEnv *env, jclass cls,
                                                       jbyteArray received) {
    (void)cls;
    if (!received) return JNI_FALSE;
    jsize n = (*env)->GetArrayLength(env, received);
    if (n < 36 || n > (jsize)NDS_WIFI_STAGED_BEACON_CAP) return JNI_FALSE;
    uint8_t frame[NDS_WIFI_STAGED_BEACON_CAP];
    (*env)->GetByteArrayRegion(env, received, 0, n, (jbyte *)frame);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    return nds_wifi_probe_queue_data_rx(frame, (size_t)n) ? JNI_TRUE : JNI_FALSE;
}

/* v19: pass LAN-only transaction metadata together with the exact frame.
 * Do not prepend anything to the DS descriptor that enters Wi-Fi RAM. */
JNIEXPORT jbyteArray JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiDataTxTaggedFrame(JNIEnv *env, jclass cls) {
    (void)cls;
    uint8_t frame[NDS_WIFI_STAGED_BEACON_CAP];
    uint64_t txn = 0u;
    const size_t n = nds_wifi_probe_take_data_tx_tagged(frame, sizeof(frame), &txn);
    jbyteArray ret = (*env)->NewByteArray(env, (jsize)(n ? n + 8u : 0u));
    if (!ret) return NULL;
    if (!n) return ret;
    jbyte head[8];
    for (unsigned i = 0; i < 8; ++i)
        head[i] = (jbyte)((txn >> (56u - 8u * i)) & 0xffu);
    (*env)->SetByteArrayRegion(env, ret, 0, 8, head);
    (*env)->SetByteArrayRegion(env, ret, 8, (jsize)n, (const jbyte*)frame);
    return ret;
}
JNIEXPORT jboolean JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiDataRxFrameTagged(JNIEnv *env, jclass cls,
                                                            jbyteArray received, jlong transaction) {
    (void)cls;
    if (!received) return JNI_FALSE;
    const jsize n = (*env)->GetArrayLength(env, received);
    if (n < 36 || n > (jsize)NDS_WIFI_STAGED_BEACON_CAP) return JNI_FALSE;
    uint8_t frame[NDS_WIFI_STAGED_BEACON_CAP];
    (*env)->GetByteArrayRegion(env, received, 0, n, (jbyte *)frame);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    return nds_wifi_probe_queue_data_rx_tagged(frame, (size_t)n,
            (uint64_t)transaction) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiMpEndRxCompleteTxn(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return (jlong)nds_wifi_probe_take_mp_end_rx_complete();
}
JNIEXPORT void JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiMpPeerEndRxCompleteTxn(JNIEnv *env,
                                                               jclass cls, jlong transaction) {
    (void)env; (void)cls;
    if (transaction > 0) nds_wifi_probe_remote_mp_end_rx_complete((uint64_t)transaction);
}

/* A distinct DS console MAC is configured once at game startup. It is not the
 * Android phone's physical MAC (which modern Android intentionally hides). */
JNIEXPORT jboolean JNICALL
Java_com_seedlessds_core_SeedlessCore_firmwareWifiMacSet(JNIEnv *env, jclass cls,
                                                       jbyteArray value)
{
    (void)cls;
    if (!value || (*env)->GetArrayLength(env, value) != 6) return JNI_FALSE;
    uint8_t mac[6];
    (*env)->GetByteArrayRegion(env, value, 0, 6, (jbyte *)mac);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    return firmware_mac_configure(mac, sizeof(mac)) ? JNI_TRUE : JNI_FALSE;
}

/* 1.0.156: textual dump of the first/last MP frames for host-vs-PC comparison. */
JNIEXPORT jstring JNICALL
Java_com_seedlessds_core_SeedlessCore_wifiProbeMpTrace(JNIEnv *env, jclass cls)
{
    (void)cls;
    static char buf[24 * 1024];
    size_t n = nds_wifi_probe_mp_trace_text(buf, sizeof(buf));
    if (!n) buf[0] = '\0';
    return (*env)->NewStringUTF(env, buf);
}
