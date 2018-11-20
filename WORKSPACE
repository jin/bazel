workspace(name = "io_bazel")
load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive", "http_file")

# Protobuf expects an //external:python_headers label which would contain the
# Python headers if fast Python protos is enabled. Since we are not using fast
# Python protos, bind python_headers to a dummy target.
bind(
    name = "python_headers",
    actual = "//:dummy",
)

# Protobuf code generation for GRPC requires three external labels:
# //external:grpc-java_plugin
# //external:grpc-jar
# //external:guava
bind(
    name = "grpc-java-plugin",
    actual = "//third_party/grpc:grpc-java-plugin",
)

bind(
    name = "grpc-jar",
    actual = "//third_party/grpc:grpc-jar",
)

bind(
    name = "guava",
    actual = "//third_party:guava",
)

# Used by //third_party/protobuf:protobuf_python
bind(
    name = "six",
    actual = "//third_party/py/six",
)

http_archive(
    name = "bazel_j2objc",
    # Computed using "shasum -a 256 j2objc-2.0.3.zip"
    sha256 = "a36bac432d0dbd8c98249e484b2b69dd5720afa4abb58711a3c3def1c0bfa21d",
    strip_prefix = "j2objc-2.0.3",
    url = "https://github.com/google/j2objc/releases/download/2.0.3/j2objc-2.0.3.zip",
)

# For src/test/shell/bazel:test_srcs
load("//src/test/shell/bazel:list_source_repository.bzl", "list_source_repository")

list_source_repository(name = "local_bazel_source_list")

# To run the Android integration tests in //src/test/shell/bazel/android:all or
# build the Android sample app in //examples/android/java/bazel:hello_world
#
#   1. Install an Android SDK and NDK from https://developer.android.com
#   2. Set the $ANDROID_HOME and $ANDROID_NDK_HOME environment variables
#   3. Uncomment the two lines below
#
# android_sdk_repository(name = "androidsdk")
# android_ndk_repository(name = "androidndk")

# In order to run //src/test/shell/bazel:maven_skylark_test, follow the
# instructions above for the Android integration tests and uncomment the
# following lines:
# load("//tools/build_defs/repo:maven_rules.bzl", "maven_dependency_plugin")
# maven_dependency_plugin()

# This allows rules written in skylark to locate apple build tools.
bind(
    name = "xcrunwrapper",
    actual = "@bazel_tools//tools/objc:xcrunwrapper",
)

new_local_repository(
    name = "com_google_protobuf",
    build_file = "./third_party/protobuf/3.6.1/BUILD",
    path = "./third_party/protobuf/3.6.1/",
)

local_repository(
    name = "googleapis",
    path = "./third_party/googleapis/",
)

local_repository(
    name = "remoteapis",
    path = "./third_party/remoteapis/",
)

http_archive(
    name = "desugar_jdk_libs",
    # Computed using "shasum -a 256 <zip>"
    sha256 = "43b8fcc56a180e178d498f375fbeb95e8b65b9bf6c2da91ae3ae0332521a1a12",
    strip_prefix = "desugar_jdk_libs-fd937f4180c1b557805219af4482f1a27eb0ff2b",
    url = "https://github.com/google/desugar_jdk_libs/archive/fd937f4180c1b557805219af4482f1a27eb0ff2b.zip",
)

load("//:distdir.bzl", "distdir_tar")

distdir_tar(
    name = "additional_distfiles",
    archives = [
        "fd937f4180c1b557805219af4482f1a27eb0ff2b.zip",
        "7490380c6bbf9a5a060df78dc2222e7de6ffae5c.tar.gz",
    ],
    dirname = "derived/distdir",
    sha256 = {
        "fd937f4180c1b557805219af4482f1a27eb0ff2b.zip": "43b8fcc56a180e178d498f375fbeb95e8b65b9bf6c2da91ae3ae0332521a1a12",
        "7490380c6bbf9a5a060df78dc2222e7de6ffae5c.tar.gz": "3528fc6012a78da6291c00854373ea43f7f8b6c4046320be5f0884f5b3385b14",
    },
    urls = {
        "fd937f4180c1b557805219af4482f1a27eb0ff2b.zip": ["https://github.com/google/desugar_jdk_libs/archive/fd937f4180c1b557805219af4482f1a27eb0ff2b.zip"],
        "7490380c6bbf9a5a060df78dc2222e7de6ffae5c.tar.gz": ["https://github.com/bazelbuild/bazel-skylib/archive/7490380c6bbf9a5a060df78dc2222e7de6ffae5c.tar.gz"],
    },
)

# OpenJDK distributions used to create a version of Bazel bundled with the OpenJDK.
http_file(
    name = "openjdk_linux",
    sha256 = "f27cb933de4f9e7fe9a703486cf44c84bc8e9f138be0c270c9e5716a32367e87",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu-9.0.7.1-jdk9.0.7/zulu9.0.7.1-jdk9.0.7-linux_x64-allmodules.tar.gz",
    ],
    downloaded_file_path="zulu-linux.tar.gz",
)

# Used by CI to test Bazel on platforms without an installed system JDK.
# TODO(twerth): Migrate to @remotejdk when https://github.com/bazelbuild/bazel/pull/6216 is merged.
http_archive(
    name = "openjdk_linux_archive",
    sha256 = "f27cb933de4f9e7fe9a703486cf44c84bc8e9f138be0c270c9e5716a32367e87",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu-9.0.7.1-jdk9.0.7/zulu9.0.7.1-jdk9.0.7-linux_x64-allmodules.tar.gz",
    ],
    strip_prefix = "zulu9.0.7.1-jdk9.0.7-linux_x64-allmodules",
    build_file_content = "java_runtime(name = 'runtime', srcs =  glob(['**']), visibility = ['//visibility:public'])",
)

http_file(
    name = "openjdk_macos",
    sha256 = "404e7058ff91f956612f47705efbee8e175a38b505fb1b52d8c1ea98718683de",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu-9.0.7.1-jdk9.0.7/zulu9.0.7.1-jdk9.0.7-macosx_x64-allmodules.tar.gz",
    ],
    downloaded_file_path="zulu-macos.tar.gz",
)

http_file(
    name = "openjdk_win",
    sha256 = "e738829017f107e7a7cd5069db979398ec3c3f03ef56122f89ba38e7374f63ed",
    urls = [
        "https://mirror.bazel.build/openjdk/azul-zulu-9.0.7.1-jdk9.0.7/zulu9.0.7.1-jdk9.0.7-win_x64-allmodules.zip",
    ],
    downloaded_file_path="zulu-win.zip",
)

# The source-code for this OpenJDK can be found at:
# https://openjdk.linaro.org/releases/jdk9-src-1708.tar.xz
http_file(
    name = "openjdk_linux_aarch64",
    sha256 = "72e7843902b0395e2d30e1e9ad2a5f05f36a4bc62529828bcbc698d54aec6022",
    urls = [
        # When you update this, also update the link to the source-code above.
        "https://mirror.bazel.build/openjdk.linaro.org/releases/jdk9-server-release-1708.tar.xz",
        "http://openjdk.linaro.org/releases/jdk9-server-release-1708.tar.xz",
    ],
)

http_archive(
    name = "bazel_toolchains",
    sha256 = "fa1459abc7d89db728da424176f5f424e78cb8ad7a3d03d8bfa0c5c4a56b7398",
    strip_prefix = "bazel-toolchains-42619b5476b7c8a2f5117f127d5772cc46da2d1d",
    urls = [
        "https://mirror.bazel.build/github.com/bazelbuild/bazel-toolchains/archive/42619b5476b7c8a2f5117f127d5772cc46da2d1d.tar.gz",
        "https://github.com/bazelbuild/bazel-toolchains/archive/42619b5476b7c8a2f5117f127d5772cc46da2d1d.tar.gz",
    ],
)

# We're pinning to a commit because this project does not have a recent release.
# Nothing special about this commit, though.
http_archive(
    name = "com_google_googletest",
    sha256 = "0fb00ff413f6b9b80ccee44a374ca7a18af7315aea72a43c62f2acd1ca74e9b5",
    strip_prefix = "googletest-f13bbe2992d188e834339abe6f715b2b2f840a77",
    urls = [
        "https://github.com/google/googletest/archive/f13bbe2992d188e834339abe6f715b2b2f840a77.tar.gz",
    ],
)

# For src/test/shell/bazel:bazel_sandboxing_test
http_file(
    name = "mount_path_toolchain",
    sha256 = "dd8088d3543a86fd91a9ccde6e40102aff6eaf3d048aa73cc18eff05cc2053d5",
    urls = ["https://asci-toolchain.appspot.com.storage.googleapis.com/toolchain-testing/mount_path_toolchain.tar.gz"],
    downloaded_file_path="mount_path_toolchain.tar.gz",
)

http_archive(
    name = "bazel_skylib",
    sha256 = "3528fc6012a78da6291c00854373ea43f7f8b6c4046320be5f0884f5b3385b14",
    strip_prefix = "bazel-skylib-7490380c6bbf9a5a060df78dc2222e7de6ffae5c",
    urls = [
        "https://github.com/bazelbuild/bazel-skylib/archive/7490380c6bbf9a5a060df78dc2222e7de6ffae5c.tar.gz",
    ],
)

http_archive(
    name = "skydoc",
    sha256 = "cfbfcc107f5c9853dc5b2b81f1fe90fc326bd1c61f76c9aac2b4201dff75b91d",
    strip_prefix = "skydoc-d34c44c3f4102eb94beaf2636c6cf532f0ec1ee8",
    urls = [
        "https://github.com/bazelbuild/skydoc/archive/d34c44c3f4102eb94beaf2636c6cf532f0ec1ee8.tar.gz",
    ],
)

# For testing, have an distdir_tar with all the archives implicit in every
# WORKSPACE, to that they don't have to be refetched for every test
# calling `bazel sync`.
distdir_tar(
  name = "jdk_WORKSPACE_files",
  archives = [
      "zulu9.0.7.1-jdk9.0.7-linux_x64-allmodules.tar.gz",
      "zulu9.0.7.1-jdk9.0.7-macosx_x64-allmodules.tar.gz",
      "zulu9.0.7.1-jdk9.0.7-win_x64-allmodules.zip",
      "jdk9-server-release-1708.tar.xz",
      "zulu10.2+3-jdk10.0.1-linux_x64-allmodules.tar.gz",
      "zulu10.2+3-jdk10.0.1-macosx_x64-allmodules.tar.gz",
      "zulu10.2+3-jdk10.0.1-win_x64-allmodules.zip",
      "jdk10-server-release-1804.tar.xz",
  ],
  dirname = "jdk_WORKSPACE/distdir",
  sha256 = {
      "zulu9.0.7.1-jdk9.0.7-linux_x64-allmodules.tar.gz" : "f27cb933de4f9e7fe9a703486cf44c84bc8e9f138be0c270c9e5716a32367e87",
      "zulu9.0.7.1-jdk9.0.7-macosx_x64-allmodules.tar.gz" : "404e7058ff91f956612f47705efbee8e175a38b505fb1b52d8c1ea98718683de",
      "zulu9.0.7.1-jdk9.0.7-win_x64-allmodules.zip" : "e738829017f107e7a7cd5069db979398ec3c3f03ef56122f89ba38e7374f63ed",
      "jdk9-server-release-1708.tar.xz" : "72e7843902b0395e2d30e1e9ad2a5f05f36a4bc62529828bcbc698d54aec6022",
      "zulu10.2+3-jdk10.0.1-linux_x64-allmodules.tar.gz" : "57fad3602e74c79587901d6966d3b54ef32cb811829a2552163185d5064fe9b5",
      "zulu10.2+3-jdk10.0.1-macosx_x64-allmodules.tar.gz" : "e669c9a897413d855b550b4e39d79614392e6fb96f494e8ef99a34297d9d85d3",
      "zulu10.2+3-jdk10.0.1-win_x64-allmodules.zip" : "c39e7700a8d41794d60985df5a20352435196e78ecbc6a2b30df7be8637bffd5",
      "jdk10-server-release-1804.tar.xz" : "b7098b7aaf6ee1ffd4a2d0371a0be26c5a5c87f6aebbe46fe9a92c90583a84be",
  },
  urls = {
      "zulu9.0.7.1-jdk9.0.7-linux_x64-allmodules.tar.gz" : ["https://mirror.bazel.build/openjdk/azul-zulu-9.0.7.1-jdk9.0.7/zulu9.0.7.1-jdk9.0.7-linux_x64-allmodules.tar.gz"],
      "zulu9.0.7.1-jdk9.0.7-macosx_x64-allmodules.tar.gz" : ["https://mirror.bazel.build/openjdk/azul-zulu-9.0.7.1-jdk9.0.7/zulu9.0.7.1-jdk9.0.7-macosx_x64-allmodules.tar.gz"],
      "zulu9.0.7.1-jdk9.0.7-win_x64-allmodules.zip" : ["https://mirror.bazel.build/openjdk/azul-zulu-9.0.7.1-jdk9.0.7/zulu9.0.7.1-jdk9.0.7-win_x64-allmodules.zip"],
      "jdk9-server-release-1708.tar.xz" : ["https://mirror.bazel.build/openjdk.linaro.org/releases/jdk9-server-release-1708.tar.xz"],
      "zulu10.2+3-jdk10.0.1-linux_x64-allmodules.tar.gz" : ["https://mirror.bazel.build/openjdk/azul-zulu10.2+3-jdk10.0.1/zulu10.2+3-jdk10.0.1-linux_x64-allmodules.tar.gz"],
      "zulu10.2+3-jdk10.0.1-macosx_x64-allmodules.tar.gz" : ["https://mirror.bazel.build/openjdk/azul-zulu10.2+3-jdk10.0.1/zulu10.2+3-jdk10.0.1-macosx_x64-allmodules.tar.gz"],
      "zulu10.2+3-jdk10.0.1-win_x64-allmodules.zip" : ["https://mirror.bazel.build/openjdk/azul-zulu10.2+3-jdk10.0.1/zulu10.2+3-jdk10.0.1-win_x64-allmodules.zip" ],
      "jdk10-server-release-1804.tar.xz" : ["https://mirror.bazel.build/openjdk.linaro.org/releases/jdk10-server-release-1804.tar.xz"],
  },
)

DOCS_VERSIONS = [
    { "version": "0.17.1", "sha256": "02256ddd20eeaf70cf8fcfe9b2cdddd7be87aedd5848d549474fb0358e0031d3" },
    { "version": "0.17.2", "sha256": "13b35dd309a0d52f0a2518a1193f42729c75255f5fae40cea68e4d4224bfaa2e" },
    { "version": "0.18.1", "sha256": "98b77f48e37a50fc6f83100bf53f661e10732bb3ddbc226e02d0225cb7a9a7d8" },
    { "version": "0.19.1", "sha256": "ec892c59ba18bb8de1f9ae2bde937db144e45f28d6d1c32a2cee847ee81b134d" },
    { "version": "0.19.2", "sha256": "3c2d9f21ec2fd1c0b8a310f6eb6043027c838810cdfc2457d4346a0e5cdcaa7a" },
]

[http_file(
    name = "jekyll_tree_%s" % DOC_VERSION["version"].replace(".", "_"),
    urls = ["https://mirror.bazel.build/bazel_versioned_docs/jekyll-tree-%s.tar" % DOC_VERSION["version"]],
    sha256 = DOC_VERSION["sha256"],
) for DOC_VERSION in DOCS_VERSIONS]
