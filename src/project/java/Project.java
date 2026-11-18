
/*
 * Copyright (C) 2026 The DIANA Development Team
 *
 * Licensed under the MIT License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          https://opensource.org/licenses/MIT
 */
import static bee.api.License.*;

public class Project extends bee.api.Project {
    {
        product("io.github.teletha", "diana", "1.0");
        license(MIT);

        require("io.github.teletha", "sinobu");
        require("io.github.teletha", "psychopath");
        require("com.formdev", "flatlaf");
    }
}