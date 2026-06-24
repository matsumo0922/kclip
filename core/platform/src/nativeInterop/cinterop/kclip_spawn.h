#ifndef KCLIP_SPAWN_H
#define KCLIP_SPAWN_H

#include <fcntl.h>
#include <spawn.h>
#include <sys/types.h>
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

#endif
