#define _GNU_SOURCE
#include <string.h>
#include <sys/utsname.h>
#include <dlfcn.h>

// LD_PRELOAD hook: intercept uname() so code-server thinks it's on Linux
int uname(struct utsname *buf) {
    // Call real uname first to get actual machine/hostname etc.
    static int (*real_uname)(struct utsname *) = NULL;
    if (!real_uname) real_uname = dlsym(RTLD_NEXT, "uname");
    int ret = real_uname ? real_uname(buf) : -1;

    // Override only sysname — the rest stays authentic
    strncpy(buf->sysname, "Linux", sizeof(buf->sysname));
    return ret;
}
