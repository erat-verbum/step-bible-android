#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/ipc.h>
#include <linux/ashmem.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>

#define ASHMEM_NAME_LEN 256

#ifndef __ASHMEMIOC
#define __ASHMEMIOC 0x77
#endif
#define ASHMEM_SET_NAME _IOW(__ASHMEMIOC, 1, char[ASHMEM_NAME_LEN])
#define ASHMEM_GET_SIZE _IO(__ASHMEMIOC, 4)
#define ASHMEM_SET_SIZE _IOW(__ASHMEMIOC, 3, size_t)

// Functions expected by Termux's libjvm.so (from libandroid-shmem.so)
void *libandroid_shmat(int fd, const void *addr, int flags) {
    size_t size = (size_t)ioctl(fd, ASHMEM_GET_SIZE, NULL);
    if (size == 0) return (void*)-1;
    return mmap((void*)addr, size, PROT_READ | PROT_WRITE,
                MAP_SHARED, fd, 0);
}

int libandroid_shmdt(const void *addr) {
    return munmap((void*)addr, 0);
}

int libandroid_shmget(int fd, size_t size, int flags) {
    // fd is already open from shm_open, set its size
    if (ioctl(fd, ASHMEM_SET_SIZE, size) < 0) return -1;
    return fd;
}

int libandroid_shmctl(int fd, int cmd, void *buf) {
    switch (cmd) {
        case IPC_RMID:
            close(fd);
            return 0;
        default:
            return 0;
    }
}

// Standard POSIX shm interface (may also be needed)
int shm_open(const char *name, int oflag, mode_t mode) {
    int fd = open("/dev/ashmem", O_RDWR | O_CLOEXEC);
    if (fd < 0) return -1;
    if (name && name[0]) {
        char buf[ASHMEM_NAME_LEN];
        strncpy(buf, name, ASHMEM_NAME_LEN - 1);
        buf[ASHMEM_NAME_LEN - 1] = 0;
        ioctl(fd, ASHMEM_SET_NAME, buf);
    }
    return fd;
}

int shm_unlink(const char *name) {
    (void)name;
    return 0;
}
