//
// Created by Administrator on 2025/11/25.
//

#ifndef QYPLAYER_SYSTEMPROPERTIES_H
#define QYPLAYER_SYSTEMPROPERTIES_H

#include <string>
#include "sys/system_properties.h"

class SystemProperties {
public:
    inline static std::string getSystemProperty(const char *key, const char *def = "") {
        char value[PROP_VALUE_MAX] = {0};
        if (__system_property_get(key, value) > 0) {
            return {value};
        }
        return {def};
    }

    inline static bool is4ChannelSupported() {
        std::string prop = getSystemProperty("persist.sys.audio.i2s", "false");
        return (prop == "1" || prop == "true" || prop == "True");
    }
};


#endif //QYPLAYER_SYSTEMPROPERTIES_H
