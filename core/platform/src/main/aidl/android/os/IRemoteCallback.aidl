// Import-only declaration: the real interface is android.os.IRemoteCallback in the device's
// framework.jar (stubs/framework.jar). AGP still generates an empty interface from it, which
// core/platform/build.gradle.kts deletes right after generation, so the compilers use the
// framework class, not a copy.
package android.os;

interface IRemoteCallback;
