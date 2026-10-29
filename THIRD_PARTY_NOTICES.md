# Third-Party Notices

Scanner's own code is MIT-licensed. Dependencies and build tooling are not
relicensed by the project's MIT license. Their original attribution is retained.
No proprietary device-vendor SDK is included.

## Direct Runtime Dependencies

| Component | Version | License | Source |
| --- | --- | --- | --- |
| AndroidX Core | 1.16.0 | Apache 2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX AppCompat | 1.7.0 | Apache 2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Activity | 1.9.3 | Apache 2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX RecyclerView | 1.3.2 | Apache 2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Material Components for Android | 1.12.0 | Apache 2.0 | https://github.com/material-components/material-components-android |
| RE2/J | 1.8 | BSD-style Go license | https://github.com/google/re2j |

The full Apache 2.0 and RE2/J license texts are included in
[`app/src/main/assets/licenses/`](app/src/main/assets/licenses/) and bundled as APK assets.
Transitive dependencies retain the notices supplied in their distributions.
The resolved dependency graph can be inspected with:

```sh
bash ./gradlew :app:dependencies --configuration debugRuntimeClasspath
```

## Tests and Build Tooling

The repository uses the Gradle wrapper, Android Gradle Plugin, Kotlin Gradle
Plugin, AndroidX Test, and JUnit. These tools retain their own license terms and
notices. Tests and their dependencies are not included in the app's runtime APK.
