#ifndef KCLIP_SPAWN_H
#define KCLIP_SPAWN_H

#include <fcntl.h>
#include <errno.h>
#include <spawn.h>
#include <stddef.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

static inline int kclip_spawn_with_files(
    pid_t *process_id,
    const char *executable,
    char *const argv[],
    char *const envp[],
    const char *stdin_path,
    const char *stdout_path,
    const char *stderr_path
) {
    posix_spawn_file_actions_t file_actions;
    int result = posix_spawn_file_actions_init(&file_actions);
    if (result != 0) {
        return result;
    }

    result = posix_spawn_file_actions_addopen(&file_actions, STDIN_FILENO, stdin_path, O_RDONLY, 0);
    if (result == 0) {
        result = posix_spawn_file_actions_addopen(&file_actions, STDOUT_FILENO, stdout_path, O_WRONLY | O_TRUNC, 0);
    }
    if (result == 0) {
        result = posix_spawn_file_actions_addopen(&file_actions, STDERR_FILENO, stderr_path, O_WRONLY | O_TRUNC, 0);
    }
    if (result == 0) {
        result = posix_spawn(process_id, executable, &file_actions, NULL, argv, envp);
    }

    int destroy_result = posix_spawn_file_actions_destroy(&file_actions);
    if (result == 0 && destroy_result != 0) {
        return destroy_result;
    }

    return result;
}

static inline int kclip_spawn_background_with_config_fd(
    pid_t *process_id,
    const char *executable,
    char *const argv[],
    char *const envp[],
    int config_fd,
    int child_config_fd,
    const char *stdout_path,
    const char *stderr_path
) {
    posix_spawn_file_actions_t file_actions;
    int result = posix_spawn_file_actions_init(&file_actions);
    if (result != 0) {
        return result;
    }

    result = posix_spawn_file_actions_addopen(&file_actions, STDIN_FILENO, "/dev/null", O_RDONLY, 0);
    if (result == 0) {
        result = posix_spawn_file_actions_addopen(&file_actions, STDOUT_FILENO, stdout_path, O_WRONLY | O_CREAT | O_APPEND, 0600);
    }
    if (result == 0) {
        result = posix_spawn_file_actions_addopen(&file_actions, STDERR_FILENO, stderr_path, O_WRONLY | O_CREAT | O_APPEND, 0600);
    }
    if (result == 0) {
        result = posix_spawn_file_actions_adddup2(&file_actions, config_fd, child_config_fd);
    }
    if (result == 0 && config_fd != child_config_fd) {
        result = posix_spawn_file_actions_addclose(&file_actions, config_fd);
    }
    if (result == 0) {
        result = posix_spawn(process_id, executable, &file_actions, NULL, argv, envp);
    }

    int destroy_result = posix_spawn_file_actions_destroy(&file_actions);
    if (result == 0 && destroy_result != 0) {
        return destroy_result;
    }

    return result;
}

static inline int kclip_fill_unix_addr(
    const char *path,
    struct sockaddr_un *address,
    socklen_t *address_length
) {
    size_t path_length = strlen(path);
    if (path_length >= sizeof(address->sun_path)) {
        errno = ENAMETOOLONG;
        return -1;
    }

    memset(address, 0, sizeof(*address));
    address->sun_family = AF_UNIX;
    memcpy(address->sun_path, path, path_length + 1);
    *address_length = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + path_length + 1);

    return 0;
}

static inline int kclip_unix_connect(const char *path) {
    int file_descriptor = socket(AF_UNIX, SOCK_STREAM, 0);
    if (file_descriptor < 0) {
        return -1;
    }

    struct sockaddr_un address;
    socklen_t address_length;
    if (kclip_fill_unix_addr(path, &address, &address_length) != 0) {
        close(file_descriptor);
        return -1;
    }

    if (connect(file_descriptor, (struct sockaddr *)&address, address_length) != 0) {
        close(file_descriptor);
        return -1;
    }

    return file_descriptor;
}

static inline int kclip_unix_bind_listener(const char *path, int backlog) {
    int file_descriptor = socket(AF_UNIX, SOCK_STREAM, 0);
    if (file_descriptor < 0) {
        return -1;
    }

    struct sockaddr_un address;
    socklen_t address_length;
    if (kclip_fill_unix_addr(path, &address, &address_length) != 0) {
        close(file_descriptor);
        return -1;
    }

    if (bind(file_descriptor, (struct sockaddr *)&address, address_length) != 0) {
        close(file_descriptor);
        return -1;
    }
    chmod(path, 0600);

    if (listen(file_descriptor, backlog) != 0) {
        close(file_descriptor);
        return -1;
    }

    return file_descriptor;
}

static inline int kclip_unix_accept(int listener_fd) {
    return accept(listener_fd, NULL, NULL);
}

static inline int kclip_verify_private_dir(const char *path) {
    struct stat stat_buffer;
    if (lstat(path, &stat_buffer) != 0) {
        return -1;
    }

    if (!S_ISDIR(stat_buffer.st_mode)) {
        errno = ENOTDIR;
        return -1;
    }

    if (stat_buffer.st_uid != getuid()) {
        errno = EACCES;
        return -1;
    }

    if ((stat_buffer.st_mode & 0777) != 0700) {
        errno = EACCES;
        return -1;
    }

    return 0;
}

static inline int kclip_ensure_private_dir(const char *path) {
    if (mkdir(path, 0700) == 0) {
        return kclip_verify_private_dir(path);
    }

    if (errno == EEXIST) {
        return kclip_verify_private_dir(path);
    }

    return -1;
}

static inline int kclip_open_private_write(const char *path) {
#ifdef O_NOFOLLOW
    int nofollow_flag = O_NOFOLLOW;
#else
    int nofollow_flag = 0;
#endif

    int file_descriptor = open(path, O_WRONLY | O_CREAT | O_TRUNC | nofollow_flag, 0600);
    if (file_descriptor < 0) {
        return -1;
    }

    struct stat stat_buffer;
    if (fstat(file_descriptor, &stat_buffer) != 0) {
        close(file_descriptor);
        return -1;
    }

    if (!S_ISREG(stat_buffer.st_mode) || stat_buffer.st_uid != getuid()) {
        close(file_descriptor);
        errno = EACCES;
        return -1;
    }

    if (fchmod(file_descriptor, 0600) != 0) {
        close(file_descriptor);
        return -1;
    }

    return file_descriptor;
}

static inline int kclip_stat_identity(
    const char *path,
    unsigned long long *device,
    unsigned long long *inode
) {
    struct stat stat_buffer;
    if (stat(path, &stat_buffer) != 0) {
        return -1;
    }

    *device = (unsigned long long)stat_buffer.st_dev;
    *inode = (unsigned long long)stat_buffer.st_ino;

    return 0;
}

static inline int kclip_current_uid(void) {
    return (int)getuid();
}

#endif
