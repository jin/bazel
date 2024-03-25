// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.vfs;

import static com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec.stringCodec;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.CommandLineItem;
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec;
import com.google.devtools.build.lib.skyframe.serialization.SerializationDependencyProvider;
import com.google.devtools.build.lib.skyframe.serialization.SerializationException;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import com.google.devtools.build.lib.util.FileType;
import com.google.errorprone.annotations.Immutable;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import java.io.IOException;
import javax.annotation.Nullable;

/**
 * A path segment representing a path fragment using the host machine's path style. That is; If you
 * are running on a Unix machine, the path style will be unix, on Windows it is the windows path
 * style.
 *
 * <p>Path fragments are either absolute or relative.
 *
 * <p>Strings are normalized with '.' and '..' removed and resolved (if possible), any multiple
 * slashes ('/') removed, and any trailing slash also removed. Windows drive letters are uppercased.
 * The current implementation does not touch the incoming path string unless the string actually
 * needs to be normalized.
 *
 * <p>There is some limited support for Windows-style paths. Most importantly, drive identifiers in
 * front of a path (c:/abc) are supported and such paths are correctly recognized as absolute, as
 * are paths with backslash separators (C:\\foo\\bar). However, advanced Windows-style features like
 * \\\\network\\paths and \\\\?\\unc\\paths are not supported. We are currently using forward
 * slashes ('/') even on Windows.
 *
 * <p>Mac and Windows path fragments are case insensitive.
 */
@Immutable
public abstract class PathFragment
    implements Comparable<PathFragment>, FileType.HasFileType, CommandLineItem {
  private static final OsPathPolicy OS = OsPathPolicy.getFilePathOs();

  @SerializationConstant
  public static final PathFragment EMPTY_FRAGMENT = new RelativePathFragment("");

  public static final char SEPARATOR_CHAR = '/';
  private static final char ADDITIONAL_SEPARATOR_CHAR = OS.additionalSeparator();

  private static PathFragmentTrie trie = new PathFragmentTrie();

//  private final String normalizedPath;
  @SuppressWarnings("Immutable")
  private final PathFragmentTrie.PathFragmentNode trieNode;
  // DON'T add more fields here unless you know what you are doing. Adding another field will
  // increase the shallow heap of a PathFragment instance beyond the current value of 16 bytes.
  // Blaze's heap typically has many instances.

  /** Creates a new normalized path fragment. */
  public static PathFragment create(String path) {
    if (path.isEmpty()) {
      return EMPTY_FRAGMENT;
    }
    int normalizationLevel = OS.needsToNormalize(path);
    String normalizedPath =
        normalizationLevel != OsPathPolicy.NORMALIZED
            ? OS.normalize(path, normalizationLevel)
            : path;
    int driveStrLength = OS.getDriveStrLength(normalizedPath);
    return makePathFragment(normalizedPath, driveStrLength);
  }

  private static PathFragment makePathFragment(String normalizedPath, int driveStrLength) {
    switch (driveStrLength) {
      case 0:
        return new RelativePathFragment(normalizedPath);
      case 1:
        return new UnixStyleAbsolutePathFragment(normalizedPath);
      case 3:
        return new WindowsStyleAbsolutePathFragment(normalizedPath);
      default:
        throw new IllegalStateException(
            String.format(
                "normalizedPath: %s, driveStrLength: %s", normalizedPath, driveStrLength));
    }
  }

  /**
   * Creates a new path fragment, where the caller promises that the path is normalized.
   *
   * <p>WARNING! Make sure the path fragment is in fact already normalized. The rest of the code
   * assumes this is the case.
   */
  public static PathFragment createAlreadyNormalized(String normalizedPath) {
    return normalizedPath.isEmpty()
        ? EMPTY_FRAGMENT
        : makePathFragment(normalizedPath, OS.getDriveStrLength(normalizedPath));
  }

  /** This method expects path to already be normalized. */
  private PathFragment(String normalizedPath) {
    if (trie == null) {
      trie = new PathFragmentTrie();
    }
    this.trieNode = trie.insert(Preconditions.checkNotNull(normalizedPath));
//    this.normalizedPath = Preconditions.checkNotNull(normalizedPath);
  }

  public String getPathString() {
    return trieNode.getValue();
  }

  public boolean isEmpty() {
    return trieNode.getValue().isEmpty();
  }

  /**
   * Returns 0 for relative paths (e.g. "a/b"), 1 for Unix-style absolute paths (e.g. "/a/b"), and 3
   * for Windows-style absolute paths (e.g. "a:/b").
   */
  public abstract int getDriveStrLength();

  private static final class RelativePathFragment extends PathFragment {
    // DON'T add any fields here unless you know what you are doing. Adding another field will
    // increase the shallow heap of a RelativePathFragment instance beyond the current value of 16
    // bytes. Our heap typically has many instances.

    private RelativePathFragment(String normalizedPath) {
      super(normalizedPath);
    }

    @Override
    public int getDriveStrLength() {
      return 0;
    }
  }

  private static final class UnixStyleAbsolutePathFragment extends PathFragment {
    // DON'T add any fields here unless you know what you are doing. Adding another field will
    // increase the shallow heap of a UnixStyleAbsolutePathFragment instance beyond the current
    // value of 16 bytes. Our heap typically has many instances.

    private UnixStyleAbsolutePathFragment(String normalizedPath) {
      super(normalizedPath);
    }

    @Override
    public int getDriveStrLength() {
      return 1;
    }
  }

  private static final class WindowsStyleAbsolutePathFragment extends PathFragment {
    // DON'T add any fields here unless you know what you are doing. Adding another field will
    // increase the shallow heap of a WindowsStyleAbsolutePathFragment instance beyond the current
    // value of 16 bytes. Our heap typically has many instances (when Bazel is run on Windows).

    private WindowsStyleAbsolutePathFragment(String normalizedPath) {
      super(normalizedPath);
    }

    @Override
    public int getDriveStrLength() {
      return 3;
    }
  }

  /**
   * If called on a {@link PathFragment} instance for a mount name (eg. '/' or 'C:/'), the empty
   * string is returned.
   *
   * <p>This operation allocates a new string.
   */
  public String getBaseName() {
    String normalizedPath = trieNode.getValue();
    int lastSeparator = normalizedPath.lastIndexOf(SEPARATOR_CHAR);
    return lastSeparator < getDriveStrLength()
        ? normalizedPath.substring(getDriveStrLength())
        : normalizedPath.substring(lastSeparator + 1);
  }

  /**
   * Returns a {@link PathFragment} instance formed by resolving {@code other} relative to this
   * path. For example, if this path is "a" and other is "b", returns "a/b".
   *
   * <p>If the passed path is absolute it is returned untouched. This can be useful to resolve
   * symlinks.
   */
  public PathFragment getRelative(PathFragment other) {
    Preconditions.checkNotNull(other);
    // Fast-path: The path fragment is already normal, use cheaper normalization check
    String otherStr = other.trieNode.getValue();
    return getRelative(otherStr, other.getDriveStrLength(), OS.needsToNormalizeSuffix(otherStr));
  }

  /**
   * Returns a {@link PathFragment} instance formed by resolving {@code other} relative to this
   * path. For example, if this path is "a" and other is "b", returns "a/b".
   *
   * <p>See {@link #getRelative(PathFragment)} for details.
   */
  public PathFragment getRelative(String other) {
    Preconditions.checkNotNull(other);
    return getRelative(other, OS.getDriveStrLength(other), OS.needsToNormalize(other));
  }

  private PathFragment getRelative(String other, int otherDriveStrLength, int normalizationLevel) {
    String normalizedPath = trieNode.getValue();
    if (normalizedPath.isEmpty()) {
      return create(other);
    }
    if (other.isEmpty()) {
      return this;
    }
    // This is an absolute path, simply return it
    if (otherDriveStrLength > 0) {
      normalizedPath =
          normalizationLevel != OsPathPolicy.NORMALIZED
              ? OS.normalize(other, normalizationLevel)
              : other;
      return makePathFragment(normalizedPath, otherDriveStrLength);
    }
    String newPath;
    if (normalizedPath.length() == getDriveStrLength()) {
      newPath = normalizedPath + other;
    } else {
      newPath = normalizedPath + '/' + other;
    }
    newPath =
        normalizationLevel != OsPathPolicy.NORMALIZED
            ? OS.normalize(newPath, normalizationLevel)
            : newPath;
    return makePathFragment(newPath, getDriveStrLength());
  }

  public static boolean isNormalizedRelativePath(String path) {
    int driveStrLength = OS.getDriveStrLength(path);
    int normalizationLevel = OS.needsToNormalize(path);
    return driveStrLength == 0 && normalizationLevel == OsPathPolicy.NORMALIZED;
  }

  public static boolean containsSeparator(String path) {
    return path.lastIndexOf(SEPARATOR_CHAR) != -1;
  }

  public PathFragment getChild(String baseName) {
    checkBaseName(baseName);
    String newPath;
    String normalizedPath = trieNode.getValue();
    if (normalizedPath.length() == getDriveStrLength()) {
      newPath = normalizedPath + baseName;
    } else {
      newPath = normalizedPath + '/' + baseName;
    }
    return makePathFragment(newPath, getDriveStrLength());
  }

  /**
   * Returns the parent directory of this {@link PathFragment}.
   *
   * <p>If this is called on an single directory for a relative path, this returns an empty relative
   * path. If it's called on a root (like '/') or the empty string, it returns null.
   */
  @Nullable
  public PathFragment getParentDirectory() {
    String normalizedPath = trieNode.getValue();
    int lastSeparator = normalizedPath.lastIndexOf(SEPARATOR_CHAR);

    // For absolute paths we need to specially handle when we hit root
    // Relative paths can't hit this path as driveStrLength == 0
    if (getDriveStrLength() > 0) {
      if (lastSeparator < getDriveStrLength()) {
        if (normalizedPath.length() > getDriveStrLength()) {
          String newPath = normalizedPath.substring(0, getDriveStrLength());
          return makePathFragment(newPath, getDriveStrLength());
        } else {
          return null;
        }
      }
    } else {
      if (lastSeparator == -1) {
        if (!normalizedPath.isEmpty()) {
          return EMPTY_FRAGMENT;
        } else {
          return null;
        }
      }
    }
    String newPath = normalizedPath.substring(0, lastSeparator);
    return makePathFragment(newPath, getDriveStrLength());
  }

  /**
   * Returns the {@link PathFragment} relative to the base {@link PathFragment}.
   *
   * <p>For example, <code>
   * {@link PathFragment}.create("foo/bar/wiz").relativeTo({@link PathFragment}.create("foo"))
   * </code> returns <code>"bar/wiz"</code>.
   *
   * <p>If the {@link PathFragment} is not a child of the passed {@link PathFragment} an {@link
   * IllegalArgumentException} is thrown. In particular, this will happen whenever the two {@link
   * PathFragment} instances aren't both absolute or both relative.
   */
  public PathFragment relativeTo(PathFragment base) {
    Preconditions.checkNotNull(base);
    if (isAbsolute() != base.isAbsolute()) {
      throw new IllegalArgumentException(
          "Cannot relativize an absolute and a non-absolute path pair");
    }
    String basePath = base.trieNode.getValue();
    String normalizedPath = trieNode.getValue();
    if (!OS.startsWith(normalizedPath, basePath)) {
      throw new IllegalArgumentException(
          String.format("Path '%s' is not under '%s', cannot relativize", this, base));
    }
    int bn = basePath.length();
    if (bn == 0) {
      return this;
    }
    if (normalizedPath.length() == bn) {
      return EMPTY_FRAGMENT;
    }
    final int lastSlashIndex;
    if (basePath.charAt(bn - 1) == '/') {
      lastSlashIndex = bn - 1;
    } else {
      lastSlashIndex = bn;
    }
    if (normalizedPath.charAt(lastSlashIndex) != '/') {
      throw new IllegalArgumentException(
          String.format("Path '%s' is not under '%s', cannot relativize", this, base));
    }
    String newPath = normalizedPath.substring(lastSlashIndex + 1);
    return new RelativePathFragment(newPath);
  }

  public PathFragment relativeTo(String base) {
    return relativeTo(PathFragment.create(base));
  }

  /**
   * Returns true iff {@code other} is an ancestor of this path.
   *
   * <p>If this == other, true is returned.
   *
   * <p>An absolute path can never be an ancestor of a relative path, and vice versa.
   */
  public boolean startsWith(PathFragment other) {
    Preconditions.checkNotNull(other);
    if (other.trieNode.getValue().length() > trieNode.getValue().length()) {
      return false;
    }
    if (getDriveStrLength() != other.getDriveStrLength()) {
      return false;
    }
    if (!OS.startsWith(trieNode.getValue(), other.trieNode.getValue())) {
      return false;
    }
    return trieNode.getValue().length() == other.trieNode.getValue().length()
        || other.trieNode.getValue().length() == getDriveStrLength()
        || trieNode.getValue().charAt(other.trieNode.getValue().length()) == SEPARATOR_CHAR;
  }

  /**
   * Returns true iff {@code other}, considered as a list of path segments, is relative and a suffix
   * of {@code this}, or both are absolute and equal.
   *
   * <p>This is a reflexive, transitive, anti-symmetric relation (i.e. a partial order)
   */
  public boolean endsWith(PathFragment other) {
    Preconditions.checkNotNull(other);
    if (other.trieNode.getValue().length() > trieNode.getValue().length()) {
      return false;
    }
    if (other.isAbsolute()) {
      return this.equals(other);
    }
    if (!OS.endsWith(trieNode.getValue(), other.trieNode.getValue())) {
      return false;
    }
    return trieNode.getValue().length() == other.trieNode.getValue().length()
        || other.trieNode.getValue().isEmpty()
        || trieNode.getValue().charAt(trieNode.getValue().length() - other.trieNode.getValue().length() - 1)
            == SEPARATOR_CHAR;
  }

  public boolean isAbsolute() {
    return getDriveStrLength() > 0;
  }

  public static boolean isAbsolute(String path) {
    return OS.getDriveStrLength(path) > 0;
  }

  @Override
  public String toString() {
    return trieNode.getValue();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    return OS.equals(this.trieNode.getValue(), ((PathFragment) o).trieNode.getValue());
  }

  @Override
  public int hashCode() {
    return OS.hash(this.trieNode.getValue());
  }

  @Override
  public int compareTo(PathFragment o) {
    return OS.compare(this.trieNode.getValue(), o.trieNode.getValue());
  }

  ////////////////////////////////////////////////////////////////////////

  /**
   * Returns the number of segments in this path, excluding the drive string for absolute paths.
   *
   * <p>This operation is O(N) on the length of the string.
   */
  public int segmentCount() {
    int n = trieNode.getValue().length();
    int segmentCount = 0;
    int i;
    for (i = getDriveStrLength(); i < n; ++i) {
      if (trieNode.getValue().charAt(i) == SEPARATOR_CHAR) {
        ++segmentCount;
      }
    }
    // Add last segment if one exists.
    if (i > getDriveStrLength()) {
      ++segmentCount;
    }
    return segmentCount;
  }

  /**
   * Determines whether this path consists of a single segment, excluding the drive string for
   * absolute paths.
   *
   * <p>Prefer this method over {@link #segmentCount} when it is not necessary to know the exact
   * number of segments, as this short-circuits as soon as {@link #SEPARATOR_CHAR} is found.
   */
  public boolean isSingleSegment() {
    return trieNode.getValue().length() > getDriveStrLength() && !isMultiSegment();
  }

  /**
   * Determines whether this path consists of multiple segments, excluding the drive string for
   * absolute paths.
   *
   * <p>Prefer this method over {@link #segmentCount} when it is not necessary to know the exact
   * number of segments, as this short-circuits as soon as {@link #SEPARATOR_CHAR} is found.
   */
  public boolean isMultiSegment() {
    return trieNode.getValue().indexOf(SEPARATOR_CHAR, getDriveStrLength()) >= 0;
  }

  /**
   * Returns the specified segment of this path; index must be non-negative and less than {@code
   * segmentCount()}.
   *
   * <p>This operation is O(N) on the length of the string.
   */
  public String getSegment(int index) {
    int n = trieNode.getValue().length();
    int segmentCount = 0;
    int i;
    for (i = getDriveStrLength(); i < n && segmentCount < index; ++i) {
      if (trieNode.getValue().charAt(i) == SEPARATOR_CHAR) {
        ++segmentCount;
      }
    }
    int starti = i;
    for (; i < n; ++i) {
      if (trieNode.getValue().charAt(i) == SEPARATOR_CHAR) {
        break;
      }
    }
    // Add last segment if one exists.
    if (i > getDriveStrLength()) {
      ++segmentCount;
    }
    int endi = i;
    if (index < 0 || index >= segmentCount) {
      throw new IllegalArgumentException("Illegal segment index: " + index);
    }
    return trieNode.getValue().substring(starti, endi);
  }

  /**
   * Returns a new path fragment that is a sub fragment of this one. The sub fragment begins at the
   * specified <code>beginIndex</code> segment and ends at the segment at index <code>endIndex - 1
   * </code>. Thus the number of segments in the new PathFragment is <code>endIndex - beginIndex
   * </code>.
   *
   * <p>If the path is absolute and <code>beginIndex</code> is zero, the returned path is absolute.
   * Otherwise, if the path is relative or <code>beginIndex> is greater than zero, the returned path
   * is relative.
   *
   * <p>This operation is O(N) on the length of the string.
   *
   * @param beginIndex the beginning index, inclusive.
   * @param endIndex the ending index, exclusive.
   * @return the specified sub fragment, never null.
   * @exception IndexOutOfBoundsException if the <code>beginIndex</code> is negative, or <code>
   *     endIndex</code> is larger than the length of this <code>String</code> object, or <code>
   *     beginIndex</code> is larger than <code>endIndex</code>.
   */
  public PathFragment subFragment(int beginIndex, int endIndex) {
    if (beginIndex < 0 || beginIndex > endIndex) {
      throw new IndexOutOfBoundsException(
          String.format("path: %s, beginIndex: %d endIndex: %d", toString(), beginIndex, endIndex));
    }
    return subFragmentImpl(beginIndex, endIndex);
  }

  public PathFragment subFragment(int beginIndex) {
    if (beginIndex < 0) {
      throw new IndexOutOfBoundsException(
          String.format("path: %s, beginIndex: %d", toString(), beginIndex));
    }
    return subFragmentImpl(beginIndex, -1);
  }

  private PathFragment subFragmentImpl(int beginIndex, int endIndex) {
    int n = trieNode.getValue().length();
    int segmentIndex = 0;
    int i;
    for (i = getDriveStrLength(); i < n && segmentIndex < beginIndex; ++i) {
      if (trieNode.getValue().charAt(i) == SEPARATOR_CHAR) {
        ++segmentIndex;
      }
    }
    int starti = i;
    if (segmentIndex < endIndex) {
      for (; i < n; ++i) {
        if (trieNode.getValue().charAt(i) == SEPARATOR_CHAR) {
          ++segmentIndex;
          if (segmentIndex == endIndex) {
            break;
          }
        }
      }
    } else if (endIndex == -1) {
      i = trieNode.getValue().length();
    }
    int endi = i;
    // Add last segment if one exists for verification
    if (i == n && i > getDriveStrLength()) {
      ++segmentIndex;
    }
    if (beginIndex > segmentIndex || endIndex > segmentIndex) {
      throw new IndexOutOfBoundsException(
          String.format("path: %s, beginIndex: %d endIndex: %d", toString(), beginIndex, endIndex));
    }
    // If beginIndex is 0, we include the drive string.
    int driveStrLength = 0;
    if (beginIndex == 0) {
      starti = 0;
      driveStrLength = this.getDriveStrLength();
      endi = Math.max(endi, driveStrLength);
    }
    return makePathFragment(trieNode.getValue().substring(starti, endi), driveStrLength);
  }

  /**
   * Returns an {@link Iterable} that lazily yields the segments of this path.
   *
   * <p>When iterating over the segments of a path fragment, prefer this method to {@link
   * #splitToListOfSegments} as it performs a single, lazy traversal over the path string without
   * the overhead of creating a list.
   */
  public Iterable<String> segments() {
    return () -> PathSegmentIterator.create(trieNode.getValue(), getDriveStrLength());
  }

  /**
   * Splits this path fragment into a list of segments.
   *
   * <p>This operation is O(N) on the length of the string. If it is not necessary to store the
   * segments in list form, consider using {@link #segments}.
   */
  public ImmutableList<String> splitToListOfSegments() {
    ImmutableList.Builder<String> segments = ImmutableList.builderWithExpectedSize(segmentCount());
    int nexti = getDriveStrLength();
    int n = trieNode.getValue().length();
    for (int i = getDriveStrLength(); i < n; ++i) {
      if (trieNode.getValue().charAt(i) == SEPARATOR_CHAR) {
        segments.add(trieNode.getValue().substring(nexti, i));
        nexti = i + 1;
      }
    }
    // Add last segment if one exists.
    if (nexti < n) {
      segments.add(trieNode.getValue().substring(nexti));
    }
    return segments.build();
  }

  /** Returns the path string, or '.' if the path is empty. */
  public String getSafePathString() {
    return !trieNode.getValue().isEmpty() ? trieNode.getValue(): ".";
  }

  /**
   * Returns the path string using '/' as the name-separator character, but do so in a way
   * unambiguously recognizable as path. In other words, return "." for relative and empty paths,
   * and prefix relative paths with one segment by "./".
   *
   * <p>In this way, a shell will always interpret such a string as path (absolute or relative to
   * the working directory) and not as command to be searched for in the search path.
   */
  public String getCallablePathString() {
    if (isAbsolute()) {
      return trieNode.getValue();
    } else if (trieNode.getValue().isEmpty()) {
      return ".";
    } else if (trieNode.getValue().indexOf(SEPARATOR_CHAR) == -1) {
      return "." + SEPARATOR_CHAR + trieNode.getValue();
    } else {
      return trieNode.getValue();
    }
  }

  /**
   * Returns the file extension of this path, excluding the period, or "" if there is no extension.
   */
  public String getFileExtension() {
    int n = trieNode.getValue().length();
    for (int i = n - 1; i > getDriveStrLength(); --i) {
      char c = trieNode.getValue().charAt(i);
      if (c == '.') {
        return trieNode.getValue().substring(i + 1, n);
      } else if (c == SEPARATOR_CHAR) {
        break;
      }
    }
    return "";
  }

  /**
   * Returns a new PathFragment formed by appending {@code newName} to the parent directory. Null is
   * returned iff this method is called on a PathFragment with zero segments. If {@code newName}
   * designates an absolute path, the value of {@code this} will be ignored and a PathFragment
   * corresponding to {@code newName} will be returned. This behavior is consistent with the
   * behavior of {@link #getRelative(String)}.
   */
  @Nullable
  public PathFragment replaceName(String newName) {
    PathFragment parent = getParentDirectory();
    return parent != null ? parent.getRelative(newName) : null;
  }

  /**
   * Returns the drive for an absolute path fragment.
   *
   * <p>On unix, this will return "/". On Windows it will return the drive letter, like "C:/".
   */
  public String getDriveStr() {
    Preconditions.checkArgument(isAbsolute());
    return trieNode.getValue().substring(0, getDriveStrLength());
  }

  /**
   * Returns a relative PathFragment created from this absolute PathFragment using the
   * same segments and drive letter.
   */
  public PathFragment toRelative() {
    Preconditions.checkArgument(isAbsolute());
    return makePathFragment(trieNode.getValue().substring(getDriveStrLength()), 0);
  }

  /**
   * Returns true if this path contains uplevel references "..".
   *
   * <p>Since path fragments are normalized, this implies that the uplevel reference is at the start
   * of the path fragment.
   */
  public boolean containsUplevelReferences() {
    // Path is normalized, so any ".." would have to be the first segment.
    return trieNode.getValue().startsWith("..")
        && (trieNode.getValue().length() == 2 || trieNode.getValue().charAt(2) == SEPARATOR_CHAR);
  }

  /**
   * Returns true if the passed path contains uplevel references "..".
   *
   * <p>This is useful to check a string for '..' segments before constructing a PathFragment, since
   * these are always normalized and will throw uplevel references away.
   */
  public static boolean containsUplevelReferences(String path) {
    return !isNormalizedImpl(path, /* lookForSameLevelReferences= */ false);
  }

  /**
   * Returns true if the passed path does not contain uplevel references ("..") or single-dot
   * references (".").
   *
   * <p>This is useful to check a string for normalization before constructing a PathFragment, since
   * these are always normalized and will throw uplevel references away.
   */
  public static boolean isNormalized(String path) {
    return isNormalizedImpl(path, /* lookForSameLevelReferences= */ true);
  }

  private enum NormalizedImplState {
    Base, /* No particular state, eg. an 'a' or 'L' character */
    Separator, /* We just saw a separator */
    Dot, /* We just saw a dot after a separator */
    DotDot, /* We just saw two dots after a separator */
  }

  private static boolean isNormalizedImpl(String path, boolean lookForSameLevelReferences) {
    // Starting state is equivalent to having just seen a separator
    NormalizedImplState state = NormalizedImplState.Separator;
    int n = path.length();
    for (int i = 0; i < n; ++i) {
      char c = path.charAt(i);
      boolean isSeparator = OS.isSeparator(c);
      switch (state) {
        case Base:
          if (isSeparator) {
            state = NormalizedImplState.Separator;
          } else {
            state = NormalizedImplState.Base;
          }
          break;
        case Separator:
          if (isSeparator) {
            state = NormalizedImplState.Separator;
          } else if (c == '.') {
            state = NormalizedImplState.Dot;
          } else {
            state = NormalizedImplState.Base;
          }
          break;
        case Dot:
          if (isSeparator) {
            if (lookForSameLevelReferences) {
              // "." segment found
              return false;
            }
            state = NormalizedImplState.Separator;
          } else if (c == '.') {
            state = NormalizedImplState.DotDot;
          } else {
            state = NormalizedImplState.Base;
          }
          break;
        case DotDot:
          if (isSeparator) {
            // ".." segment found
            return false;
          } else {
            state = NormalizedImplState.Base;
          }
          break;
        default:
          throw new IllegalStateException("Unhandled state: " + state);
      }
    }
    // The character just after the string is equivalent to a separator
    switch (state) {
      case Dot:
        if (lookForSameLevelReferences) {
          // "." segment found
          return false;
        }
        break;
      case DotDot:
        return false;
      default:
    }
    return true;
  }

  /**
   * Throws {@link IllegalArgumentException} if {@code paths} contains any paths that are equal to
   * {@code startingWithPath} or that are not beneath {@code startingWithPath}.
   */
  public static void checkAllPathsAreUnder(
      Iterable<PathFragment> paths, PathFragment startingWithPath) {
    for (PathFragment path : paths) {
      Preconditions.checkArgument(
          !path.equals(startingWithPath) && path.startsWith(startingWithPath),
          "%s is not beneath %s",
          path,
          startingWithPath);
    }
  }

  @Override
  public String filePathForFileTypeMatcher() {
    return trieNode.getValue();
  }

  @Override
  public String expandToCommandLine() {
    return trieNode.getValue();
  }

  private static void checkBaseName(String baseName) {
    if (baseName.isEmpty()) {
      throw new IllegalArgumentException("Child must not be empty string ('')");
    }
    if (baseName.equals(".") || baseName.equals("..")) {
      throw new IllegalArgumentException("baseName must not be '" + baseName + "'");
    }
    try {
      checkSeparators(baseName);
    } catch (InvalidBaseNameException e) {
      throw new IllegalArgumentException("baseName " + e.getMessage() + ": '" + baseName + "'", e);
    }
  }

  public static void checkSeparators(String baseName) throws InvalidBaseNameException {
    if (baseName.indexOf(SEPARATOR_CHAR) != -1) {
      throw new InvalidBaseNameException("must not contain " + SEPARATOR_CHAR);
    }
    if (ADDITIONAL_SEPARATOR_CHAR != 0) {
      if (baseName.indexOf(ADDITIONAL_SEPARATOR_CHAR) != -1) {
        throw new InvalidBaseNameException("must not contain " + ADDITIONAL_SEPARATOR_CHAR);
      }
    }
  }

  /** Indicates that a path fragment's base name had invalid characters. */
  public static final class InvalidBaseNameException extends Exception {
    private InvalidBaseNameException(String message) {
      super(message);
    }
  }

  @SuppressWarnings("unused") // found by CLASSPATH-scanning magic
  private static class Codec extends LeafObjectCodec<PathFragment> {
    @Override
    public Class<PathFragment> getEncodedClass() {
      return PathFragment.class;
    }

    @Override
    public void serialize(
        SerializationDependencyProvider dependencies, PathFragment obj, CodedOutputStream codedOut)
        throws SerializationException, IOException {
      stringCodec().serialize(dependencies, obj.trieNode.getValue(), codedOut);
    }

    @Override
    public PathFragment deserialize(
        SerializationDependencyProvider dependencies, CodedInputStream codedIn)
        throws SerializationException, IOException {
      return createAlreadyNormalized(stringCodec().deserialize(dependencies, codedIn));
    }
  }
}
