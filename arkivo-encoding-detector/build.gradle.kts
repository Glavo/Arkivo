// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

dependencies {
    "tier2TestImplementation"(project(":arkivo-archive-zip"))
}

tasks.withType<Jar>().configureEach {
    from("NOTICE") { into("META-INF") }
    from("UPSTREAM.properties") { into("META-INF/chardetng") }
    from(rootProject.file("LICENSES/Chardetng-MIT.txt")) { into("META-INF/LICENSES") }
}
