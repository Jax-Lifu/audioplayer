//
// Created by Administrator on 2025/11/25.
//

#ifndef QYPLAYER_CPUAFFINITY_H
#define QYPLAYER_CPUAFFINITY_H

#include <sched.h>
#include <unistd.h>
#include <linux/resource.h>
#include <sys/resource.h>
#include "Logger.h"

inline void setCpuAffinity(int coreCount) {
    cpu_set_t mask;
    CPU_ZERO(&mask);

    if (coreCount == 1) {
        CPU_SET(3, &mask);
    } else {
        CPU_SET(1, &mask);
        CPU_SET(2, &mask);
    }
    pid_t pid = getpid();

    if (sched_setaffinity(pid, sizeof(mask), &mask) != 0) {
        LOGE("sched_setaffinity\n");
    }
    setpriority(PRIO_PROCESS, 0, -19);
    LOGD("set %d cpu core for pid: %d", coreCount, pid);
}

#endif //QYPLAYER_CPUAFFINITY_H
