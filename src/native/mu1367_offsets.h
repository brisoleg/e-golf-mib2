/*
 * ipod-drvr-iap2.so offsets for VW e-Golf MU1367 (HMI H29.319.75).
 * Driver SHA-256: 7149a3e75b1b19e89021841759f420a6eced456b6c697a3dd1f0e436abea0b8c
 *
 * These are ELF virtual addresses relative to the driver load base, re-derived from the
 * stock driver for this firmware (carplay_rgi/PORTING_ANALYSIS.md §2.4). They replace the
 * K2161_* constants hardcoded in the upstream edzenis native sources. Ghidra labels in
 * native/re/ipod-drvr-iap2.decomp.c are +0x10000 vs these ELF values.
 *
 * Only the log-only subset (ident component + message-group 0x52 activation + RX observe)
 * is used for milestone M1. Each consumer must still gate on the driver SHA-256 and verify
 * the live table layout before writing, exactly as the upstream code does.
 */
#ifndef MU1367_OFFSETS_H
#define MU1367_OFFSETS_H

/* base cross-check exports */
#define MU1367_IPOD_MODULE            0x00027e7c
#define MU1367_ADDPARAM_BLOB          0x0001e2c4

/* control message-group table (12-byte groups; 12-byte message entries) */
#define MU1367_CTRL_MSG_TABLE         0x000ace80
#define MU1367_GROUP50_NAME           0x0002518c
#define MU1367_GROUP50_MESSAGES       0x000ae30c
#define MU1367_GROUP68_NAME           0x000251bc
#define MU1367_GROUP68_MESSAGES       0x000ae36c
#define MU1367_NP_START_NAME          0x00025554
#define MU1367_NP_START_PARAMS        0x00028918
#define MU1367_NP_UPDATE_NAME         0x0002556c
#define MU1367_NP_UPDATE_PARAMS       0x00028938
/* group 0x52 slot = table + 0x52*12 = table + 0x3d8; stock = three zero words */
#define MU1367_GROUP52_SLOT           0x000ad258

/* identification builder table (ident_info_funcs): {id, builder} entries */
#define MU1367_IDENT_ID               0x000a9ba0  /* value 0x16 */
#define MU1367_IDENT_BUILDER          0x000a9ba4
#define MU1367_GPS_BUILDER            0x0000cb70  /* ident_info_gpscomp */

/* resmgr func table slots (.data, writable, R_ARM_RELATIVE) */
#define MU1367_IAP2_MSG_SLOT          0x000a9bd4
#define MU1367_IAP2_MSG               0x0000f4c8
#define MU1367_IAP2_CLOSE_SLOT        0x000a9be8
#define MU1367_IAP2_CLOSE             0x000107b4

/* packet/link helpers */
#define MU1367_ADDPARAM_NODATA        0x0001e13c
#define MU1367_ADDPARAM_NUM           0x0001e3fc
#define MU1367_SENDLIST_POP           0x0001f198
#define MU1367_SENDLIST_PUSH          0x0001ee00
#define MU1367_LINK_SEND_MESSAGE      0x0001165c

/* RX observe: memcpy return address inside link_get_transport_data (+0x3e0). CANDIDATE,
 * confirm on the car by counting observer hits and FF 5A .. 40 40 frames. */
#define MU1367_RX_MEMCPY_RA           0x000125e8

/*
 * Driver quirk vs K2161: ident_info_gpscomp returns 1 on success and 0 otherwise, never -1
 * (decomp.c:6318-6460). A wrapper must not treat 0 from the original builder as fatal.
 *
 * Driver quirk vs edzenis: iap2_msg rejects subtypes without bit 0x2000 with MsgError, so the
 * "0x1d00 via iap2_msg" session-boundary check cannot fire here. Detect iAP2 0x1D00/0x1D02 on
 * the receive side (link_handle_iap2pkt, decomp.c:10436) instead.
 */

#endif /* MU1367_OFFSETS_H */
