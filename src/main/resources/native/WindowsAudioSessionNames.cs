// Names the real Java audio renderer's Core Audio session, not the silent SMTC helper's session.
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace KittyTuneSmtc {
    sealed class WindowsAudioSessionNames : IDisposable {
        readonly object gate = new object();
        readonly int ownerPid;
        readonly Process owner;
        readonly string iconPath;
        readonly Stopwatch clock = Stopwatch.StartNew();
        readonly Action<string> log;
        string mediaKey = "";
        bool wasPlaying;
        bool disposed;
        bool running;
        bool loggedFailure;
        int remaining = 8;
        int generation;
        long nextAttempt;

        public WindowsAudioSessionNames(int requestedPid, string launcher, Action<string> log) {
            this.log = log;
            // Never trust a supplied PID alone: this bridge was started directly by the JVM it serves.
            if (requestedPid <= 0 || ParentProcessId() != requestedPid) return;
            try {
                owner = Process.GetProcessById(requestedPid);
                // Hold the original process handle until Dispose; HasExited then refers to this
                // process object even if its numeric PID is eventually reused by Windows.
                if (owner.Handle == IntPtr.Zero || owner.HasExited) { owner.Dispose(); owner = null; return; }
                ownerPid = requestedPid;
                if (!String.IsNullOrEmpty(launcher) && File.Exists(launcher)) iconPath = launcher;
            } catch (Exception ex) {
                if (owner != null) owner.Dispose();
                owner = null;
                log("ERROR:audio-owner:" + ex.Message);
            }
        }

        public void Update(bool playing, string title, string artist) {
            int attemptGeneration;
            lock (gate) {
                if (disposed || owner == null) return;
                string key = title + "\n" + artist;
                if ((playing && !wasPlaying) || key != mediaKey) {
                    remaining = 8;
                    generation++;
                    nextAttempt = 0;
                    mediaKey = key;
                }
                wasPlaying = playing;
                if (!playing || running || remaining <= 0 || clock.ElapsedMilliseconds < nextAttempt) return;
                remaining--;
                nextAttempt = clock.ElapsedMilliseconds + 5000;
                running = true;
                attemptGeneration = generation;
            }
            ThreadPool.QueueUserWorkItem(delegate {
                try {
                    if (!owner.HasExited && RenameOwnedSessions()) lock (gate) {
                        if (attemptGeneration == generation) remaining = 0;
                    }
                } catch (Exception ex) {
                    lock (gate) {
                        if (!loggedFailure) { loggedFailure = true; log("ERROR:audio-session:" + ex.Message); }
                    }
                } finally {
                    lock (gate) {
                        running = false;
                        if (disposed) owner.Dispose();
                    }
                }
            });
        }

        bool RenameOwnedSessions() {
            // ThreadPool threads are MTA; all RCWs are created and released in this worker invocation.
            IMMDeviceEnumerator enumerator = null;
            IMMDeviceCollection endpoints = null;
            bool found = false;
            try {
                enumerator = (IMMDeviceEnumerator)new MMDeviceEnumerator();
                if (enumerator.EnumAudioEndpoints(0 /* eRender */, 1 /* DEVICE_STATE_ACTIVE */, out endpoints) < 0) return false;
                uint endpointCount;
                if (endpoints.GetCount(out endpointCount) < 0) return false;
                int sessionBudget = 256;
                for (uint i = 0; i < Math.Min(endpointCount, 16U) && sessionBudget > 0; i++) {
                    IMMDevice endpoint = null;
                    object managerObject = null;
                    IAudioSessionEnumerator sessions = null;
                    try {
                        if (endpoints.Item(i, out endpoint) < 0) continue;
                        Guid managerId = new Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F");
                        if (endpoint.Activate(ref managerId, 23 /* CLSCTX_ALL */, IntPtr.Zero, out managerObject) < 0) continue;
                        var manager = (IAudioSessionManager2)managerObject;
                        if (manager.GetSessionEnumerator(out sessions) < 0) continue;
                        int sessionCount;
                        if (sessions.GetCount(out sessionCount) < 0) continue;
                        for (int j = 0; j < sessionCount && sessionBudget-- > 0; j++) {
                            object sessionObject = null;
                            try {
                                if (sessions.GetSession(j, out sessionObject) < 0) continue;
                                var session = sessionObject as IAudioSessionControl2;
                                if (session == null) continue;
                                uint pid;
                                // S_OK only: AUDCLNT_S_NO_SINGLE_PROCESS sessions may include other applications.
                                if (session.GetProcessId(out pid) != 0 || pid != ownerPid || session.IsSystemSoundsSession() != 1) continue;
                                if (owner.HasExited) return found;
                                Guid context = Guid.Empty;
                                string name;
                                if (session.GetDisplayName(out name) < 0 || name != "KittyTune")
                                    if (session.SetDisplayName("KittyTune", ref context) < 0) continue;
                                if (iconPath != null) session.SetIconPath(iconPath + ",0", ref context);
                                found = true;
                            } finally { Release(sessionObject); }
                        }
                    } finally { Release(sessions); Release(managerObject); Release(endpoint); }
                }
                return found;
            } finally { Release(endpoints); Release(enumerator); }
        }

        static void Release(object value) { if (value != null && Marshal.IsComObject(value)) Marshal.FinalReleaseComObject(value); }

        public void Dispose() {
            lock (gate) { disposed = true; if (!running && owner != null) owner.Dispose(); }
        }

        static int ParentProcessId() {
            IntPtr snapshot = CreateToolhelp32Snapshot(2 /* TH32CS_SNAPPROCESS */, 0);
            if (snapshot == new IntPtr(-1)) return 0;
            try {
                var entry = new ProcessEntry { size = (uint)Marshal.SizeOf(typeof(ProcessEntry)) };
                if (!Process32FirstW(snapshot, ref entry)) return 0;
                int current = Process.GetCurrentProcess().Id;
                for (int n = 0; n < 4096; n++) {
                    if (entry.processId == current) return (int)entry.parentProcessId;
                    if (!Process32NextW(snapshot, ref entry)) break;
                }
                return 0;
            } finally { CloseHandle(snapshot); }
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct ProcessEntry {
            public uint size, usage, processId;
            public UIntPtr defaultHeap;
            public uint moduleId, threads, parentProcessId;
            public int priority;
            public uint flags;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string exeFile;
        }
        [DllImport("kernel32.dll", SetLastError = true)] static extern IntPtr CreateToolhelp32Snapshot(uint flags, uint processId);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool Process32FirstW(IntPtr snapshot, ref ProcessEntry entry);
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool Process32NextW(IntPtr snapshot, ref ProcessEntry entry);
        [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);

        [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumerator { }
        [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDeviceEnumerator {
            [PreserveSig] int EnumAudioEndpoints(int flow, uint state, out IMMDeviceCollection devices);
        }
        [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDeviceCollection {
            [PreserveSig] int GetCount(out uint count);
            [PreserveSig] int Item(uint index, out IMMDevice device);
        }
        [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IMMDevice {
            [PreserveSig] int Activate(ref Guid iid, uint context, IntPtr activation, [MarshalAs(UnmanagedType.IUnknown)] out object instance);
        }
        [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IAudioSessionManager2 {
            [PreserveSig] int GetAudioSessionControl(ref Guid session, uint flags, out IntPtr control);
            [PreserveSig] int GetSimpleAudioVolume(ref Guid session, uint flags, out IntPtr volume);
            [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator enumerator);
        }
        [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IAudioSessionEnumerator {
            [PreserveSig] int GetCount(out int count);
            [PreserveSig] int GetSession(int index, [MarshalAs(UnmanagedType.IUnknown)] out object session);
        }
        [ComImport, Guid("BFB7FF88-7239-4FC9-8FA2-07C950BE9C6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IAudioSessionControl2 {
            [PreserveSig] int GetState(out int state);
            [PreserveSig] int GetDisplayName([MarshalAs(UnmanagedType.LPWStr)] out string name);
            [PreserveSig] int SetDisplayName([MarshalAs(UnmanagedType.LPWStr)] string name, ref Guid context);
            [PreserveSig] int GetIconPath([MarshalAs(UnmanagedType.LPWStr)] out string path);
            [PreserveSig] int SetIconPath([MarshalAs(UnmanagedType.LPWStr)] string path, ref Guid context);
            [PreserveSig] int GetGroupingParam(out Guid grouping);
            [PreserveSig] int SetGroupingParam(ref Guid grouping, ref Guid context);
            [PreserveSig] int RegisterAudioSessionNotification(IntPtr callback);
            [PreserveSig] int UnregisterAudioSessionNotification(IntPtr callback);
            [PreserveSig] int GetSessionIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string identifier);
            [PreserveSig] int GetSessionInstanceIdentifier([MarshalAs(UnmanagedType.LPWStr)] out string identifier);
            [PreserveSig] int GetProcessId(out uint processId);
            [PreserveSig] int IsSystemSoundsSession();
            [PreserveSig] int SetDuckingPreference([MarshalAs(UnmanagedType.Bool)] bool optOut);
        }
    }
}
