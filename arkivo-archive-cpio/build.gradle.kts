/*
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: MPL-2.0
 */

dependencies {
    api(project(":arkivo-archive"))
    implementation(project(":arkivo-archive-codec"))
    implementation(project(":arkivo-base"))
    testImplementation("org.apache.commons:commons-compress:1.28.0")
}
