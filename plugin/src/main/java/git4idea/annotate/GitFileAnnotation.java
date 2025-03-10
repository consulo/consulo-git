/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
 */
package git4idea.annotate;

import consulo.application.util.DateFormatUtil;
import consulo.git.localize.GitLocalize;
import consulo.ide.impl.idea.openapi.vcs.annotate.ShowAllAffectedGenericAction;
import consulo.localize.LocalizeValue;
import consulo.logging.Logger;
import consulo.project.Project;
import consulo.versionControlSystem.VcsException;
import consulo.versionControlSystem.VcsKey;
import consulo.versionControlSystem.annotate.AnnotationSourceSwitcher;
import consulo.versionControlSystem.annotate.FileAnnotation;
import consulo.versionControlSystem.annotate.LineAnnotationAspect;
import consulo.versionControlSystem.annotate.LineAnnotationAspectAdapter;
import consulo.versionControlSystem.history.VcsFileRevision;
import consulo.versionControlSystem.history.VcsRevisionNumber;
import consulo.virtualFileSystem.VirtualFile;
import git4idea.GitRevisionNumber;
import git4idea.GitVcs;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.*;

/**
 * Git file annotation implementation
 * <p/>
 * Based on the JetBrains SVNAnnotationProvider.
 */
public class GitFileAnnotation extends FileAnnotation {
    private final static Logger LOG = Logger.getInstance(GitFileAnnotation.class);

    /**
     * annotated content
     */
    private final StringBuffer myContentBuffer = new StringBuffer();
    /**
     * The currently annotated lines
     */
    private final ArrayList<LineInfo> myLines = new ArrayList<>();
    /**
     * The project reference
     */
    private final Project myProject;
    private final VcsRevisionNumber myBaseRevision;
    /**
     * Map from revision numbers to revisions
     */
    private final Map<VcsRevisionNumber, VcsFileRevision> myRevisionMap = new HashMap<>();

    /**
     * the virtual file for which annotations are generated
     */
    private final VirtualFile myFile;

    private final LineAnnotationAspect DATE_ASPECT = new GitAnnotationAspect(GitAnnotationAspect.DATE, true) {
        @Override
        public String doGetValue(LineInfo info) {
            final Date date = info.getDate();
            return date == null ? "" : DateFormatUtil.formatPrettyDate(date);
        }
    };

    private final LineAnnotationAspect REVISION_ASPECT = new GitAnnotationAspect(GitAnnotationAspect.REVISION, false) {
        @Override
        protected String doGetValue(LineInfo lineInfo) {
            final GitRevisionNumber revision = lineInfo.getRevision();
            return revision == null ? "" : String.valueOf(revision.getShortRev());
        }
    };

    private final LineAnnotationAspect AUTHOR_ASPECT = new GitAnnotationAspect(GitAnnotationAspect.AUTHOR, true) {
        @Override
        protected String doGetValue(LineInfo lineInfo) {
            final String author = lineInfo.getAuthor();
            return author == null ? "" : author;
        }
    };
    private final GitVcs myVcs;

    /**
     * A constructor
     *
     * @param project     the project of annotation provider
     * @param file        the git root
     * @param monitorFlag if false the file system will not be listened for changes (used for annotated files from the repository).
     * @param revision
     */
    public GitFileAnnotation(
        @Nonnull final Project project,
        @Nonnull VirtualFile file,
        final boolean monitorFlag,
        final VcsRevisionNumber revision
    ) {
        super(project);
        myProject = project;
        myVcs = GitVcs.getInstance(myProject);
        myFile = file;
        myBaseRevision = revision == null ? (myVcs.getDiffProvider().getCurrentRevision(file)) : revision;
    }

    /**
     * Add revisions to the list (from log)
     *
     * @param revisions revisions to add
     */
    public void addLogEntries(List<VcsFileRevision> revisions) {
        for (VcsFileRevision vcsFileRevision : revisions) {
            myRevisionMap.put(vcsFileRevision.getRevisionNumber(), vcsFileRevision);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dispose() {
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public LineAnnotationAspect[] getAspects() {
        return new LineAnnotationAspect[]{REVISION_ASPECT, DATE_ASPECT, AUTHOR_ASPECT};
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public LocalizeValue getToolTipValue(int lineNumber) {
        if (myLines.size() <= lineNumber || lineNumber < 0) {
            return LocalizeValue.empty();
        }
        final LineInfo info = myLines.get(lineNumber);
        if (info == null) {
            return LocalizeValue.empty();
        }
        VcsFileRevision fileRevision = myRevisionMap.get(info.getRevision());
        if (fileRevision != null) {
            return GitLocalize.annotationToolTip(
                info.getRevision().asString(),
                info.getAuthor(),
                info.getDate(),
                fileRevision.getCommitMessage()
            );
        }
        else {
            return LocalizeValue.empty();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getAnnotatedContent() {
        return myContentBuffer.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<VcsFileRevision> getRevisions() {
        final List<VcsFileRevision> result = new ArrayList<>(myRevisionMap.values());
        Collections.sort(result, (o1, o2) -> -1 * o1.getRevisionNumber().compareTo(o2.getRevisionNumber()));
        return result;
    }

    @Override
    public boolean revisionsNotEmpty() {
        return !myRevisionMap.isEmpty();
    }

    @Override
    public AnnotationSourceSwitcher getAnnotationSourceSwitcher() {
        return null;
    }

    @Override
    public int getLineCount() {
        return myLines.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public VcsRevisionNumber getLineRevisionNumber(final int lineNumber) {
        if (lineNumberCheck(lineNumber)) {
            return null;
        }
        final LineInfo lineInfo = myLines.get(lineNumber);
        return lineInfo == null ? null : lineInfo.getRevision();
    }

    private boolean lineNumberCheck(int lineNumber) {
        return myLines.size() <= lineNumber || lineNumber < 0 || myLines.get(lineNumber) == null;
    }

    @Override
    public Date getLineDate(int lineNumber) {
        if (lineNumberCheck(lineNumber)) {
            return null;
        }
        final LineInfo lineInfo = myLines.get(lineNumber);
        return lineInfo == null ? null : lineInfo.getDate();
    }

    /**
     * Get revision number for the line.
     */
    @Override
    public VcsRevisionNumber originalRevision(int lineNumber) {
        return getLineRevisionNumber(lineNumber);
    }

    /**
     * Append line info
     *
     * @param date       the revision date
     * @param revision   the revision number
     * @param author     the author
     * @param line       the line content
     * @param lineNumber the line number for revision
     * @throws VcsException in case when line could not be processed
     */
    public void appendLineInfo(
        final Date date,
        final GitRevisionNumber revision,
        final String author,
        final String line,
        final long lineNumber
    ) throws VcsException {
        int expectedLineNo = myLines.size() + 1;
        if (lineNumber != expectedLineNo) {
            throw new VcsException("Adding for info for line " + lineNumber + " but we are expecting it to be for " + expectedLineNo);
        }
        myLines.add(new LineInfo(date, revision, author));
        myContentBuffer.append(line);
    }

    public int getNumLines() {
        return myLines.size();
    }

    /**
     * Revision annotation aspect implementation
     */
    private abstract class GitAnnotationAspect extends LineAnnotationAspectAdapter {
        public GitAnnotationAspect(String id, boolean showByDefault) {
            super(id, showByDefault);
        }

        @Override
        public String getValue(int lineNumber) {
            return lineNumberCheck(lineNumber) ? "" : doGetValue(myLines.get(lineNumber));
        }

        protected abstract String doGetValue(LineInfo lineInfo);

        @Override
        protected void showAffectedPaths(int lineNum) {
            if (lineNum >= 0 && lineNum < myLines.size()) {
                final LineInfo info = myLines.get(lineNum);
                if (info != null) {
                    ShowAllAffectedGenericAction.showSubmittedFiles(myProject, info.getRevision(), myFile, GitVcs.getKey());
                }
            }
        }
    }

    /**
     * Line information
     */
    static class LineInfo {
        /**
         * date of the change
         */
        private final Date myDate;
        /**
         * revision number
         */
        private final GitRevisionNumber myRevision;
        /**
         * the author of the change
         */
        private final String myAuthor;

        /**
         * A constructor
         *
         * @param date     date of the change
         * @param revision revision number
         * @param author   the author of the change
         */
        public LineInfo(final Date date, final GitRevisionNumber revision, final String author) {
            myDate = date;
            myRevision = revision;
            myAuthor = author;
        }

        /**
         * @return the revision date
         */
        public Date getDate() {
            return myDate;
        }

        /**
         * @return the revision number
         */
        public GitRevisionNumber getRevision() {
            return myRevision;
        }

        /**
         * @return the author of the change
         */
        public String getAuthor() {
            return myAuthor;
        }
    }

    @Override
    public VirtualFile getFile() {
        return myFile;
    }

    @Nullable
    @Override
    public VcsRevisionNumber getCurrentRevision() {
        return myBaseRevision;
    }

    @Override
    public VcsKey getVcsKey() {
        return GitVcs.getKey();
    }

    @Override
    public boolean isBaseRevisionChanged(@Nonnull VcsRevisionNumber number) {
        final VcsRevisionNumber currentCurrentRevision = myVcs.getDiffProvider().getCurrentRevision(myFile);
        return myBaseRevision != null && !myBaseRevision.equals(currentCurrentRevision);
    }
}
