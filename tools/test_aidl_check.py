"""Tests for aidl_check.py. Run: python3 -m unittest discover -s tools -p 'test_*.py'"""
import re
import unittest

from aidl_check import Interface, Method, best, compare, decode_switch_data, parse_aidl, parse_dexdump

OVERVIEW_AIDL = """/*
 * Copyright (C) 2017 The Android Open Source Project
 */
package com.android.systemui.shared.recents;

import android.graphics.Region;
import android.os.Bundle;

oneway interface IOverviewProxy {

    void onActiveNavBarRegionChanges(in Region activeRegion) = 11;

    /** Sent once, with the binder of ISystemUiProxy. */
    void onInitialize(in Bundle params) = 12;

    // void onBind(in ISystemUiProxy sysUiProxy) = 0;
    void onOverviewHidden(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) = 8;

    /* removed: void onTip(int x) = 9; */
    void onAssistantVisibilityChanged(float visibility) = 14;
}
"""

LISTENER_AIDL = """package com.android.wm.shell.recents;

import android.app.ActivityManager.RunningTaskInfo;

oneway interface IRecentTasksListener {
    void onRecentTasksChanged();
    void onRunningTaskAppeared(in RunningTaskInfo taskInfo);
}
"""

RECENT_TASKS_AIDL = """package com.android.wm.shell.recents;

import android.app.IApplicationThread;
import android.app.PendingIntent;
import com.android.wm.shell.util.GroupedRecentTaskInfo;

interface IRecentTasks {
    oneway void registerRecentTasksListener(in IRecentTasksListener listener) = 1;
    GroupedRecentTaskInfo[] getRecentTasks(int maxNum, int flags, int userId) = 3;
    oneway void startRecentsTransition(in PendingIntent intent,
                    IApplicationThread appThread, long durationMs) = 5;
    IBinder getShellApplyToken() = 6;
    oneway void setOverrides(in int[] types, String reason) = 7;
}
"""

# Realistic `dexdump -d` excerpt: the interface, its Stub (TRANSACTION_* constants) and its
# Proxy (a non-oneway method calls Parcel.readException, a oneway one does not).
DEXDUMP = """Class #546            -
  Class descriptor  : 'Lcom/android/wm/shell/pip/PipContentOverlay;'
  Access flags      : 0x0401 (PUBLIC ABSTRACT)
  Virtual methods   -
    #0              : (in Lcom/android/wm/shell/pip/PipContentOverlay;)
      name          : 'getRecentTasks'
      type          : '()V'
      access        : 0x0001 (PUBLIC)
  source_file_idx   : 20556 (PipContentOverlay.java)

Class #547            -
  Class descriptor  : 'Lcom/android/wm/shell/recents/IRecentTasks;'
  Access flags      : 0x0601 (PUBLIC INTERFACE ABSTRACT)
  Superclass        : 'Ljava/lang/Object;'
  Interfaces        -
    #0              : 'Landroid/os/IInterface;'
  Static fields     -
    #0              : (in Lcom/android/wm/shell/recents/IRecentTasks;)
      name          : 'DESCRIPTOR'
      type          : 'Ljava/lang/String;'
      access        : 0x0019 (PUBLIC STATIC FINAL)
      value         : "com.android.wm.shell.recents.IRecentTasks"
  Instance fields   -
  Direct methods    -
  Virtual methods   -
    #0              : (in Lcom/android/wm/shell/recents/IRecentTasks;)
      name          : 'getRecentTasks'
      type          : '(III)[Lcom/android/wm/shell/util/GroupedRecentTaskInfo;'
      access        : 0x0401 (PUBLIC ABSTRACT)
      code          : (none)

    #1              : (in Lcom/android/wm/shell/recents/IRecentTasks;)
      name          : 'registerRecentTasksListener'
      type          : '(Lcom/android/wm/shell/recents/IRecentTasksListener;)V'
      access        : 0x0401 (PUBLIC ABSTRACT)
      code          : (none)

  source_file_idx   : 13057 (IRecentTasks.java)

Class #549            -
  Class descriptor  : 'Lcom/android/wm/shell/recents/IRecentTasks$Stub$Proxy;'
  Access flags      : 0x0000 ()
  Superclass        : 'Ljava/lang/Object;'
  Static fields     -
  Instance fields   -
    #0              : (in Lcom/android/wm/shell/recents/IRecentTasks$Stub$Proxy;)
      name          : 'mRemote'
      type          : 'Landroid/os/IBinder;'
      access        : 0x0002 (PRIVATE)
  Direct methods    -
  Virtual methods   -
    #2              : (in Lcom/android/wm/shell/recents/IRecentTasks$Stub$Proxy;)
      name          : 'getRecentTasks'
      type          : '(III)[Lcom/android/wm/shell/util/GroupedRecentTaskInfo;'
      access        : 0x0001 (PUBLIC)
      code          -
      registers     : 9
471860:                                        |[471860] com.android.wm.shell.recents.IRecentTasks$Stub$Proxy.getRecentTasks:(III)[Lcom/android/wm/shell/util/GroupedRecentTaskInfo;
4718ac: 7254 a002 3210                         |001e: invoke-interface {v2, v3, v0, v1, v4}, Landroid/os/IBinder;.transact:(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z // method@02a0
4718b4: 6e10 b502 0100                         |0022: invoke-virtual {v1}, Landroid/os/Parcel;.readException:()V // method@02b5
      catches       : 1

    #3              : (in Lcom/android/wm/shell/recents/IRecentTasks$Stub$Proxy;)
      name          : 'registerRecentTasksListener'
      type          : '(Lcom/android/wm/shell/recents/IRecentTasksListener;)V'
      access        : 0x0001 (PUBLIC)
      code          -
471a00: 7254 a002 3210                         |0010: invoke-interface {v2, v3, v0, v1, v4}, Landroid/os/IBinder;.transact:(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z // method@02a0
471a06: 6e10 c702 0000                         |0013: invoke-virtual {v0}, Landroid/os/Parcel;.recycle:()V // method@02c7
      catches       : 1

  source_file_idx   : 13057 (IRecentTasks.java)

Class #550            -
  Class descriptor  : 'Lcom/android/wm/shell/recents/IRecentTasks$Stub;'
  Access flags      : 0x0401 (PUBLIC ABSTRACT)
  Superclass        : 'Landroid/os/Binder;'
  Static fields     -
    #0              : (in Lcom/android/wm/shell/recents/IRecentTasks$Stub;)
      name          : 'TRANSACTION_getRecentTasks'
      type          : 'I'
      access        : 0x0018 (STATIC FINAL)
      value         : 4
    #1              : (in Lcom/android/wm/shell/recents/IRecentTasks$Stub;)
      name          : 'TRANSACTION_registerRecentTasksListener'
      type          : 'I'
      access        : 0x0018 (STATIC FINAL)
      value         : 2
  Instance fields   -
  Direct methods    -
    #0              : (in Lcom/android/wm/shell/recents/IRecentTasks$Stub;)
      name          : 'asInterface'
      type          : '(Landroid/os/IBinder;)Lcom/android/wm/shell/recents/IRecentTasks;'
      access        : 0x0009 (PUBLIC STATIC)
  source_file_idx   : 13057 (IRecentTasks.java)
"""

# The same, as R8 leaves it in Lineage's Launcher3 (dexdump -d): no DESCRIPTOR field, no
# TRANSACTION_* constants; the Proxy writes the descriptor and loads the code itself.
DEXDUMP_R8 = """Class #5577            -
  Class descriptor  : 'Lcom/android/wm/shell/shared/IShellTransitions;'
  Access flags      : 0x0601 (PUBLIC INTERFACE ABSTRACT)
  Static fields     -
  Instance fields   -
  Direct methods    -
  Virtual methods   -
    #0              : (in Lcom/android/wm/shell/shared/IShellTransitions;)
      name          : 'getOverviewOverlayContainer'
      type          : '(I)Landroid/view/SurfaceControl;'
      access        : 0x0401 (PUBLIC ABSTRACT)
      code          : (none)

    #1              : (in Lcom/android/wm/shell/shared/IShellTransitions;)
      name          : 'registerRemote'
      type          : '(Landroid/window/RemoteTransition;)V'
      access        : 0x0401 (PUBLIC ABSTRACT)
      code          : (none)

Class #5578            -
  Class descriptor  : 'Lcom/android/wm/shell/shared/IShellTransitions$Stub$Proxy;'
  Access flags      : 0x0011 (PUBLIC FINAL)
  Static fields     -
  Instance fields   -
  Direct methods    -
  Virtual methods   -
    #2              : (in Lcom/android/wm/shell/shared/IShellTransitions$Stub$Proxy;)
      name          : 'getOverviewOverlayContainer'
      type          : '(I)Landroid/view/SurfaceControl;'
      access        : 0x0001 (PUBLIC)
      code          -
38c1c0: 1a02 2976                              |000c: const-string v2, "com.android.wm.shell.shared.IShellTransitions" // string@7629
38c1c4: 6e20 b806 2000                         |000e: invoke-virtual {v0, v2}, Landroid/os/Parcel;.writeInterfaceToken:(Ljava/lang/String;)V // method@06b8
38c1ca: 6e20 b606 4000                         |0011: invoke-virtual {v0, v4}, Landroid/os/Parcel;.writeInt:(I)V // method@06b6
38c1d4: 1304 0900                              |0016: const/16 v4, #int 9 // #9
38c1d8: 1202                                   |0018: const/4 v2, #int 0 // #0
38c1da: 7252 7e06 4310                         |0019: invoke-interface {v3, v4, v0, v1, v2}, Landroid/os/IBinder;.transact:(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z // method@067e
38c1e0: 6e10 a306 0100                         |001c: invoke-virtual {v1}, Landroid/os/Parcel;.readException:()V // method@06a3

    #3              : (in Lcom/android/wm/shell/shared/IShellTransitions$Stub$Proxy;)
      name          : 'registerRemote'
      type          : '(Landroid/window/RemoteTransition;)V'
      access        : 0x0001 (PUBLIC)
      code          -
38c2c0: 1a01 2976                              |0008: const-string v1, "com.android.wm.shell.shared.IShellTransitions" // string@7629
38c2c4: 6e20 b806 1000                         |000a: invoke-virtual {v0, v1}, Landroid/os/Parcel;.writeInterfaceToken:(Ljava/lang/String;)V // method@06b8
38c2d4: 1214                                   |0016: const/4 v4, #int 1 // #1
38c2d6: 1221                                   |0017: const/4 v1, #int 2 // #2
38c2da: 7254 7e06 4310                         |0019: invoke-interface {v3, v1, v0, v2, v4}, Landroid/os/IBinder;.transact:(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z // method@067e

  source_file_idx   : 8361 (IShellTransitions.java)
"""

# A Stub alone (Lineage's Launcher3 implements ILauncherProxy, SystemUI's R8 inlined its Proxy):
# the codes are the keys of onTransact's switch, its payload decoded by decode_switch_data; a
# case that writes no reply is oneway.
DEXDUMP_STUB = """Class #5575            -
  Class descriptor  : 'Lx/IFoo;'
  Access flags      : 0x0601 (PUBLIC INTERFACE ABSTRACT)
  Virtual methods   -
    #0              : (in Lx/IFoo;)
      name          : 'ping'
      type          : '(I)V'
      access        : 0x0401 (PUBLIC ABSTRACT)
    #1              : (in Lx/IFoo;)
      name          : 'toggle'
      type          : '()V'
      access        : 0x0401 (PUBLIC ABSTRACT)
    #2              : (in Lx/IFoo;)
      name          : 'ask'
      type          : '()I'
      access        : 0x0401 (PUBLIC ABSTRACT)

Class #5576            -
  Class descriptor  : 'Lx/IFoo$Stub;'
  Access flags      : 0x0401 (PUBLIC ABSTRACT)
  Virtual methods   -
    #1              : (in Lx/IFoo$Stub;)
      name          : 'onTransact'
      type          : '(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z'
      access        : 0x0001 (PUBLIC)
      code          -
37f37c: 1a01 1176                              |0008: const-string v1, "x.IFoo" // string@7611
37f380: 6e20 9b06 1400                         |000a: invoke-virtual {v4, v1}, Landroid/os/Parcel;.enforceInterface:(Ljava/lang/String;)V // method@069b
37f386: 2b03 a101 0000                         |000d: packed-switch v3, 00000030 // +00000023
37f38c: 6f56 2f06 3254                         |0010: invoke-super {v2, v3, v4, v5, v6}, Landroid/os/Binder;.onTransact:(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z // method@062f
37f392: 0f02                                   |0013: return v2
37f394: 6e10 a506 0400                         |0015: invoke-virtual {v4}, Landroid/os/Parcel;.readInt:()I // method@06a5
37f3b4: 7230 a0a4 3205                         |0018: invoke-interface {v2, v3}, Lx/IFoo;.ping:(I)V // method@a4a0
37f3ba: 2900 8601                              |001b: goto/16 002d // +0012
37f3bc: 7210 aea4 0200                         |001d: invoke-interface {v2}, Lx/IFoo;.ask:()I // method@a4ae
37f3c0: 6e10 a506 0500                         |0020: invoke-virtual {v5}, Landroid/os/Parcel;.writeNoException:()V // method@06a5
37f3c4: 2900 8601                              |0023: goto/16 002d // +000a
37f3c6: 7210 aea4 0200                         |0025: invoke-interface {v2}, Lx/IFoo;.toggle:()V // method@a4ae
37f3cc: 0f00                                   |002d: return v0
37f3ce: 0001 0400 0700 0000 0800 0000 1000 ... |0030: switch-data 7:8 8:3 9:16 10:24
"""


class ParseAidlTest(unittest.TestCase):
    def test_explicit_ids_are_offset_by_first_call_transaction(self):
        name, iface = parse_aidl(OVERVIEW_AIDL)
        self.assertEqual(name, "com.android.systemui.shared.recents.IOverviewProxy")
        self.assertEqual(
            iface,
            {
                "onActiveNavBarRegionChanges": Method(12, ("Landroid/graphics/Region;",), "V", True),
                "onInitialize": Method(13, ("Landroid/os/Bundle;",), "V", True),
                "onOverviewHidden": Method(9, ("Z", "Z"), "V", True),
                "onAssistantVisibilityChanged": Method(15, ("F",), "V", True),
            },
        )

    def test_implicit_ids_follow_declaration_order_and_nested_types_use_dollar(self):
        name, iface = parse_aidl(LISTENER_AIDL)
        self.assertEqual(name, "com.android.wm.shell.recents.IRecentTasksListener")
        self.assertEqual(iface["onRecentTasksChanged"], Method(1, (), "V", True))
        self.assertEqual(
            iface["onRunningTaskAppeared"],
            Method(2, ("Landroid/app/ActivityManager$RunningTaskInfo;",), "V", True),
        )

    def test_method_oneway_arrays_builtins_and_same_package_types(self):
        _, iface = parse_aidl(RECENT_TASKS_AIDL)
        self.assertEqual(
            iface["registerRecentTasksListener"],
            Method(2, ("Lcom/android/wm/shell/recents/IRecentTasksListener;",), "V", True),
        )
        self.assertEqual(
            iface["getRecentTasks"],
            Method(4, ("I", "I", "I"), "[Lcom/android/wm/shell/util/GroupedRecentTaskInfo;", False),
        )
        self.assertEqual(
            iface["startRecentsTransition"],
            Method(6, ("Landroid/app/PendingIntent;", "Landroid/app/IApplicationThread;", "J"), "V", True),
        )
        self.assertEqual(iface["getShellApplyToken"], Method(7, (), "Landroid/os/IBinder;", False))
        self.assertEqual(iface["setOverrides"], Method(8, ("[I", "Ljava/lang/String;"), "V", True))

    def test_declarations_without_body_are_not_interfaces(self):
        self.assertIsNone(parse_aidl("package android.app;\ninterface IApplicationThread;\n"))
        self.assertIsNone(parse_aidl("package android.window;\nparcelable RemoteTransition;\n"))


class ParseDexdumpTest(unittest.TestCase):
    def test_reads_codes_signatures_and_oneway_from_stub_and_proxy(self):
        found = parse_dexdump(DEXDUMP.splitlines(), {"com.android.wm.shell.recents.IRecentTasks"})
        self.assertEqual(
            found,
            {
                "com.android.wm.shell.recents.IRecentTasks": Interface(
                    "com.android.wm.shell.recents.IRecentTasks",
                    {
                        "getRecentTasks": Method(
                            4, ("I", "I", "I"), "[Lcom/android/wm/shell/util/GroupedRecentTaskInfo;", False
                        ),
                        "registerRecentTasksListener": Method(
                            2, ("Lcom/android/wm/shell/recents/IRecentTasksListener;",), "V", True
                        ),
                    },
                )
            },
        )

    def test_without_proxy_oneway_is_unknown_and_a_gap(self):
        no_proxy = re.sub(r"Class #549.*?(?=Class #550)", "", DEXDUMP, flags=re.S)
        found = parse_dexdump(no_proxy.splitlines(), {"com.android.wm.shell.recents.IRecentTasks"})
        device = found["com.android.wm.shell.recents.IRecentTasks"]
        self.assertIsNone(device.methods["registerRecentTasksListener"].oneway)
        repo = {"registerRecentTasksListener": Method(
            2, ("Lcom/android/wm/shell/recents/IRecentTasksListener;",), "V", True)}
        device = Interface(device.descriptor, {"registerRecentTasksListener": device.methods["registerRecentTasksListener"]})
        _, gaps = compare("com.android.wm.shell.recents.IRecentTasks", repo, device)
        self.assertEqual(gaps, [
            "com.android.wm.shell.recents.IRecentTasks.registerRecentTasksListener: "
            "oneway unknown on device (no Stub$Proxy)"
        ])

    def test_r8_codes_and_descriptor_come_from_the_proxy_code(self):
        fq = "com.android.wm.shell.shared.IShellTransitions"
        self.assertEqual(parse_dexdump(DEXDUMP_R8.splitlines(), {fq}), {fq: Interface(fq, {
            "getOverviewOverlayContainer": Method(9, ("I",), "Landroid/view/SurfaceControl;", False),
            "registerRemote": Method(2, ("Landroid/window/RemoteTransition;",), "V", True),
        })})

    def test_best_prefers_the_apk_that_holds_the_proxy(self):
        fq = "com.android.wm.shell.shared.IShellTransitions"
        with_proxy = parse_dexdump(DEXDUMP_R8.splitlines(), {fq})[fq]
        stub_only = Interface(None, {m: Method(None, x.params, x.ret, None) for m, x in with_proxy.methods.items()})
        self.assertEqual(best([stub_only, with_proxy]), with_proxy)
        self.assertEqual(best([with_proxy, stub_only]), with_proxy)
        self.assertIsNone(best([]))

    def test_stub_alone_codes_come_from_the_switch_and_oneway_from_the_reply(self):
        self.assertEqual(parse_dexdump(DEXDUMP_STUB.splitlines(), {"x.IFoo"}), {"x.IFoo": Interface("x.IFoo", {
            "ping": Method(7, ("I",), "V", True),
            "ask": Method(9, (), "I", False),
            "toggle": Method(10, (), "V", True),
        })})

    def test_switch_payloads_are_decoded_from_the_dex(self):
        dex = bytes(0x20) + bytes.fromhex("0001 0200 0700 0000 0800 0000 1000 0000".replace(" ", "")) \
            + bytes.fromhex("0002 0200 0700 0000 6600 0000 0800 0000 1000 0000".replace(" ", ""))
        line = "000020: 0001 0200 0700 0000 0800 0000 1000 ... |0030: packed-switch-data (8 units)\n"
        self.assertEqual(decode_switch_data(line, dex), "000020: |0030: switch-data 7:8 8:16\n")
        line = "000030: 0002 0200 0700 0000 6600 0000 0800 ... |0040: sparse-switch-data (10 units)\n"
        self.assertEqual(decode_switch_data(line, dex), "000030: |0040: switch-data 7:8 102:16\n")
        self.assertEqual(decode_switch_data("|0000: nop\n", dex), "|0000: nop\n")

    def test_small_stub_compares_the_code_instead_of_a_switch(self):
        dump = DEXDUMP_STUB.replace(
            "|000d: packed-switch v3, 00000030 // +00000023",
            "|000c: const/4 v0, #int 7 // #7\n|000d: if-eq v3, v0, 0015 // +0008")
        dump = re.sub(r"\n.*switch-data.*", "", dump)
        found = parse_dexdump(dump.splitlines(), {"x.IFoo"})["x.IFoo"]
        self.assertEqual(found.methods["ping"], Method(7, ("I",), "V", True))
        self.assertIsNone(found.methods["ask"].code)

    def test_absent_interface_is_absent(self):
        self.assertEqual(parse_dexdump(DEXDUMP.splitlines(), {"a.b.IMissing"}), {})


class CompareTest(unittest.TestCase):
    BASE = {"m": Method(2, ("I",), "V", True), "n": Method(3, (), "Landroid/os/IBinder;", False)}

    def test_identical_has_no_gap(self):
        rows, gaps = compare("x.IFoo", self.BASE, Interface("x.IFoo", dict(self.BASE)))
        self.assertEqual(gaps, [])
        self.assertEqual([r[1] for r in rows], ["m", "n"])

    def test_each_kind_of_gap_is_reported(self):
        device = {
            "m": Method(4, ("Z",), "I", False),
            "onlyOnDevice": Method(9, (), "V", True),
        }
        repo = dict(self.BASE)
        _, gaps = compare("x.IFoo", repo, Interface("x.IFoo", device))
        text = "\n".join(gaps)
        self.assertIn("x.IFoo.m: transaction 2 in repo, 4 on device", text)
        self.assertIn("x.IFoo.m: parameters (I) in repo, (Z) on device", text)
        self.assertIn("x.IFoo.m: returns V in repo, I on device", text)
        self.assertIn("x.IFoo.m: oneway in repo, not oneway on device", text)
        self.assertIn("x.IFoo.n: missing on device", text)
        self.assertIn("x.IFoo.onlyOnDevice: missing in repo", text)
        self.assertEqual(len(gaps), 6)

    def test_descriptor_mismatch(self):
        _, gaps = compare("x.IFoo", self.BASE, Interface("y.IFoo", dict(self.BASE)))
        self.assertEqual(gaps, ["x.IFoo: descriptor y.IFoo on device"])

    def test_interface_missing_on_device(self):
        _, gaps = compare("x.IFoo", self.BASE, None)
        self.assertEqual(gaps, ["x.IFoo: interface missing on device"])


if __name__ == "__main__":
    unittest.main()
