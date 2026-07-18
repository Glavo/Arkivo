/*
 * Copyright (c) 2026 Glavo
 * SPDX-License-Identifier: MPL-2.0
 */

dependencies {
    api(project(":arkivo-codec"))
    implementation(project(":arkivo-base"))
    implementation(project(":arkivo-codec-lzma"))
    testImplementation("org.tukaani:xz:1.12")
}
