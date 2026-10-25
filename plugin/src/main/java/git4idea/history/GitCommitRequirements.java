// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/*
 * Copyright 2013-2026 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */package git4idea.history;

import consulo.project.Project;
import consulo.versionControlSystem.change.Change;
import git4idea.config.GitVersionSpecialty;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Allows controlling how changes are reported when reading {@link git4idea.GitCommit} instances from {@code git log}.
 *
 * @see git4idea.GitCommit
 * @see GitDetailsCollector
 * @see GitLogUtil#readFullDetailsForHashes
 */
public final class GitCommitRequirements {
    public static final GitCommitRequirements DEFAULT = new GitCommitRequirements();

    /**
     * whether to include changes for initial commits
     */
    private final boolean myIncludeRootChanges;
    /**
     * how to detect renames
     */
    private final DiffRenames myDiffRenames;
    /**
     * how to report changes for merge commits
     */
    private final DiffInMergeCommits myDiffInMergeCommits;

    public GitCommitRequirements() {
        this(true, DiffRenames.Limit.Default.INSTANCE, DiffInMergeCommits.COMBINED_DIFF);
    }

    public GitCommitRequirements(boolean includeRootChanges, DiffRenames diffRenames, DiffInMergeCommits diffInMergeCommits) {
        myIncludeRootChanges = includeRootChanges;
        myDiffRenames = diffRenames;
        myDiffInMergeCommits = diffInMergeCommits;
    }

    public DiffRenames getDiffRenames() {
        return myDiffRenames;
    }

    public DiffInMergeCommits getDiffInMergeCommits() {
        return myDiffInMergeCommits;
    }

    public List<String> configParameters() {
        List<String> result = new ArrayList<>();
        if (myDiffRenames instanceof DiffRenames.Limit limit) {
            Integer value = limit.getLimit();
            if (value != null) {
                result.add(renameLimit(value));
            }
        }
        else if (myDiffRenames == DiffRenames.NoRenames.INSTANCE) {
            result.add("diff.renames=false");
        }

        if (!myIncludeRootChanges) {
            result.add("log.showRoot=false");
        }
        return result;
    }

    public List<String> commandParameters(Project project) {
        List<String> result = new ArrayList<>();
        if (myDiffRenames != DiffRenames.NoRenames.INSTANCE) {
            if (myDiffRenames instanceof DiffRenames.Limit limit && limit.getSimilarityIndexThreshold() != null) {
                result.add("-M" + Math.clamp(limit.getSimilarityIndexThreshold(), 0, 100) + "%");
            }
            else {
                result.add("-M");
            }
        }
        result.addAll(myDiffInMergeCommits.commandParameters(project));
        return result;
    }

    private static String renameLimit(int limit) {
        return "diff.renameLimit=" + limit;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GitCommitRequirements that
            && myIncludeRootChanges == that.myIncludeRootChanges
            && myDiffRenames.equals(that.myDiffRenames)
            && myDiffInMergeCommits == that.myDiffInMergeCommits;
    }

    @Override
    public int hashCode() {
        return Objects.hash(myIncludeRootChanges, myDiffRenames, myDiffInMergeCommits);
    }

    /**
     * Regulates how renames are detected
     *
     * @see <a href="https://git-scm.com/docs/git-config#Documentation/git-config.txt-diffrenameLimit">diff.renameLimit</a>
     * @see <a href="https://book.git-scm.com/docs/git-diff/2.6.7#Documentation/git-diff.txt--Mltngt">git diff -M</a>
     */
    public abstract static sealed class DiffRenames permits DiffRenames.Limit, DiffRenames.NoRenames {
        /**
         * Enable renames detection
         */
        public abstract static sealed class Limit extends DiffRenames
            permits Limit.Value, Limit.Infinity, Limit.Exact, Limit.Default {
            /**
             * the number of files to consider in the exhaustive portion of copy/rename detection
             */
            private final @Nullable Integer myLimit;
            /**
             * threshold on the similarity index for rename detection (in percent)
             */
            private final @Nullable Integer mySimilarityIndexThreshold;

            Limit(@Nullable Integer limit, @Nullable Integer similarityIndexThreshold) {
                myLimit = limit;
                mySimilarityIndexThreshold = similarityIndexThreshold;
            }

            public @Nullable Integer getLimit() {
                return myLimit;
            }

            public @Nullable Integer getSimilarityIndexThreshold() {
                return mySimilarityIndexThreshold;
            }

            /**
             * Use specified values for rename limit and similarity threshold
             */
            public static final class Value extends Limit {
                public Value(@Nullable Integer limit, @Nullable Integer similarity) {
                    super(limit, similarity);
                }

                public Value(@Nullable Integer limit) {
                    this(limit, null);
                }
            }

            /**
             * Use zero value for the diff.renameLimit to always detect inexact renames
             */
            public static final class Infinity extends Limit {
                public Infinity(@Nullable Integer similarity) {
                    super(0, similarity);
                }

                public Infinity() {
                    this(null);
                }
            }

            /**
             * Detect only exact renames
             */
            public static final class Exact extends Limit {
                public static final Exact INSTANCE = new Exact();

                private Exact() {
                    super(1, 100);
                }
            }

            /**
             * Use diff.renameLimit value set in users git.config and default similarity threshold value
             */
            public static final class Default extends Limit {
                public static final Default INSTANCE = new Default();

                private Default() {
                    super(null, null);
                }
            }
        }

        /**
         * Disable renames detection
         */
        public static final class NoRenames extends DiffRenames {
            public static final NoRenames INSTANCE = new NoRenames();

            private NoRenames() {
            }
        }
    }

    /**
     * Regulates how changes are reported for merge commits
     *
     * @see <a href="https://git-scm.com/docs/git-log#_diff_formatting">Diff Formatting</a>
     */
    public enum DiffInMergeCommits {
        /**
         * Do not report changes for merge commits
         */
        NO_DIFF {
            @Override
            public List<String> commandParameters(Project project) {
                return List.of();
            }
        },

        /**
         * Report combined changes (same as {@code git log -c})
         */
        COMBINED_DIFF {
            @Override
            public List<String> commandParameters(Project project) {
                return List.of("-c");
            }
        },

        /**
         * Report changes to each parent (same as {@code git log --diff-merges=separate} or {@code git log -m} in older git versions)
         */
        DIFF_TO_PARENTS {
            @Override
            public List<String> commandParameters(Project project) {
                if (GitVersionSpecialty.DIFF_MERGES_M_USES_DEFAULT_SETTING.existsIn(project)) {
                    return List.of("--diff-merges=separate");
                }
                else {
                    return List.of("-m");
                }
            }
        },

        /**
         * Report changes to the first parent only (same as {@code git log --diff-merges=first-parent}).
         * Works only since git 2.31.0.
         */
        FIRST_PARENT {
            @Override
            public List<String> commandParameters(Project project) {
                if (GitVersionSpecialty.DIFF_MERGES_SUPPORTS_FIRST_PARENT.existsIn(project)) {
                    return List.of("--diff-merges=first-parent");
                }
                else {
                    // if the required option does not exist in this git version, get changes to each parent
                    return DIFF_TO_PARENTS.commandParameters(project);
                }
            }
        };

        public abstract List<String> commandParameters(Project project);
    }
}
