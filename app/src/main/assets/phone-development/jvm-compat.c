/*
 * jvm-compat.c - 避免 HotSpot 探测匿名页与线程栈映射合并；不改 mprotect 结果。
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <sys/mman.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <stdlib.h>

static void* (*real_mmap)(void*, size_t, int, int, int, off_t) = NULL;
static int (*real_munmap)(void*, size_t) = NULL;
static void* (*real_dlopen)(const char*, int) = NULL;
static long g_pagesize = -1;

__attribute__((constructor))
static void init_shim(void) {
    real_mmap = (void* (*)(void*, size_t, int, int, int, off_t))dlsym(RTLD_NEXT, "mmap");
    real_munmap = (int (*)(void*, size_t))dlsym(RTLD_NEXT, "munmap");
    real_dlopen = (void* (*)(const char*, int))dlsym(RTLD_NEXT, "dlopen");
    g_pagesize = sysconf(_SC_PAGESIZE);

}

void* mmap(void *addr, size_t length, int prot, int flags, int fd, off_t offset) {
    if (!real_mmap) {
        errno = ENOSYS;
        return MAP_FAILED;
    }

    if (g_pagesize > 0 &&
        addr == NULL && length == (size_t)g_pagesize &&
        prot == (PROT_READ | PROT_WRITE) &&
        (flags & (MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED)) == (MAP_PRIVATE | MAP_ANONYMOUS) &&
        fd == -1 && offset == 0) {

        void* caller = __builtin_return_address(0);
        Dl_info info;
        if (dladdr(caller, &info) && info.dli_fname) {
            const char* slash = strrchr(info.dli_fname, '/');
            const char* base = slash ? slash + 1 : info.dli_fname;
            if (strcmp(base, "libjvm.so") == 0) {
                void* guard = real_mmap(NULL, (size_t)g_pagesize, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
                void* ret = real_mmap(NULL, length, prot, flags, fd, offset);
                int saved_errno = errno;
                if (guard != MAP_FAILED && real_munmap) {
                    real_munmap(guard, (size_t)g_pagesize);
                }
                errno = saved_errno;
                return ret;
            }
        }
    }

    return real_mmap(addr, length, prot, flags, fd, offset);
}

void* dlopen(const char* filename, int flags) {
    if (!real_dlopen) {
        errno = ENOSYS;
        return NULL;
    }
    void* result = real_dlopen(filename, flags);
    if (result && filename) {
        const char* slash = strrchr(filename, '/');
        if (strcmp(slash ? slash + 1 : filename, "libjvm.so") == 0) {
            // 启动器已装入 JVM，且尚未缓存 Java 子进程环境。
            const char* original = getenv("OMNI_JVM_ORIGINAL_PRELOAD");
            if (original) {
                if (*original) setenv("LD_PRELOAD", original, 1);
                else unsetenv("LD_PRELOAD");
                unsetenv("OMNI_JVM_ORIGINAL_PRELOAD");
            }
        }
    }
    return result;
}
