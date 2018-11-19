#!/bin/bash

set -eux

readonly SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
readonly DOC_VERSION=$(git describe --tags --exact-match 2>/dev/null || echo "master")

sed -i.bak "s/master/$DOC_VERSION/" $SCRIPT_DIR/../../site/_config.yml
bazel build //site:jekyll-tree --action_env=DOC_VERSION=$DOC_VERSION

# only copy to GCS if we're in a git tag
if [[ $DOC_VERSION != "master" ]]; then
    gsutil cp -n -a public-read bazel-genfiles/site/jekyll-tree.tar gs://bazel-mirror/bazel_versioned_docs/jekyll-tree-$DOC_VERSION.tar
fi

mv $SCRIPT_DIR/../../site/_config.yml{.bak,}
